/*
 * zxcore: mini frontend libretro para el nucleo Fuse (enlazado estaticamente).
 * Un unico emulador por proceso; todas las llamadas salvo zx_set_joypad,
 * zx_set_key y zx_release_all deben hacerse desde el mismo hilo.
 */
#include "zxcore.h"

#include <libretro.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_OPTIONS 64
#define MAX_W 640
#define MAX_H 480
#define AUDIO_RING (44100) /* frames estereo: ~1 s */

typedef struct { char key[64]; char value[64]; } zx_option;

static zx_option options[MAX_OPTIONS];
static int num_options;
static volatile int options_dirty;

static char system_dir[1024];
static char save_dir[1024];
static zx_log_fn log_fn;

static int initialized;
static int loaded;
static void *content_data;

static uint16_t frame_buf[MAX_W * MAX_H];
static int frame_w, frame_h;
static unsigned frame_count;

static int16_t audio_ring[AUDIO_RING * 2];
static size_t audio_head, audio_count;

static volatile unsigned pad_mask;
static volatile uint8_t keys[RETROK_LAST];
static int joystick_type = ZX_JOY_KEMPSTON;

static char message[256];
static int has_message;

static struct retro_system_av_info av_info;

/* Control de "discos" del nucleo: lo usamos para cambiar de cinta (caras A/B) */
static struct retro_disk_control_callback disk_cb;
static int has_disk_cb;

/* ------------------------------------------------------------------ log */

static void zx_logf(int level, const char *fmt, ...)
{
   char buf[1024];
   va_list ap;
   va_start(ap, fmt);
   vsnprintf(buf, sizeof(buf), fmt, ap);
   va_end(ap);
   if (log_fn)
      log_fn(level, buf);
   else
      fprintf(stderr, "[zxcore] %s", buf);
}

static void RETRO_CALLCONV core_log(enum retro_log_level level, const char *fmt, ...)
{
   char buf[1024];
   va_list ap;
   va_start(ap, fmt);
   vsnprintf(buf, sizeof(buf), fmt, ap);
   va_end(ap);
   if (log_fn)
      log_fn((int)level, buf);
   else
      fprintf(stderr, "[fuse] %s", buf);
}

static void set_message(const char *msg)
{
   if (!msg)
      return;
   strncpy(message, msg, sizeof(message) - 1);
   message[sizeof(message) - 1] = 0;
   has_message = 1;
}

/* ------------------------------------------------------- environment */

static const char *find_option(const char *key)
{
   int i;
   for (i = 0; i < num_options; i++)
      if (strcmp(options[i].key, key) == 0)
         return options[i].value;
   return NULL;
}

static bool RETRO_CALLCONV environment_cb(unsigned cmd, void *data)
{
   switch (cmd & ~RETRO_ENVIRONMENT_EXPERIMENTAL)
   {
      case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
         return *(const enum retro_pixel_format *)data == RETRO_PIXEL_FORMAT_RGB565;

      case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
         *(const char **)data = system_dir;
         return true;

      case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
         *(const char **)data = save_dir;
         return true;

      case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
         ((struct retro_log_callback *)data)->log = core_log;
         return true;

      case RETRO_ENVIRONMENT_GET_VARIABLE:
      {
         struct retro_variable *var = (struct retro_variable *)data;
         var->value = find_option(var->key);
         return var->value != NULL;
      }

      case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
         *(bool *)data = options_dirty != 0;
         options_dirty = 0;
         return true;

      case RETRO_ENVIRONMENT_SET_MESSAGE:
         set_message(((const struct retro_message *)data)->msg);
         return true;

      case RETRO_ENVIRONMENT_SET_MESSAGE_EXT:
         set_message(((const struct retro_message_ext *)data)->msg);
         return true;

      case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION:
         *(unsigned *)data = 0;
         return true;

      case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
         disk_cb = *(const struct retro_disk_control_callback *)data;
         has_disk_cb = 1;
         return true;

      case RETRO_ENVIRONMENT_SET_GEOMETRY:
      case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
      case RETRO_ENVIRONMENT_SET_VARIABLES:
      case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
      case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
      case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
      case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
         return true;

      case RETRO_ENVIRONMENT_GET_CAN_DUPE:
         *(bool *)data = true;
         return true;

      default:
         /* GET_VFS_INTERFACE, GET_CORE_OPTIONS_VERSION, etc: no soportados.
            El nucleo tiene alternativas para todos ellos. */
         return false;
   }
}

/* --------------------------------------------------------- callbacks */

static void RETRO_CALLCONV video_cb(const void *data, unsigned width, unsigned height, size_t pitch)
{
   unsigned y;
   if (!data)
      return; /* fotograma duplicado: conservamos el anterior */
   if (width > MAX_W) width = MAX_W;
   if (height > MAX_H) height = MAX_H;
   for (y = 0; y < height; y++)
      memcpy(frame_buf + y * width, (const uint8_t *)data + y * pitch, width * 2);
   frame_w = (int)width;
   frame_h = (int)height;
   frame_count++;
}

static size_t RETRO_CALLCONV audio_batch_cb(const int16_t *data, size_t frames)
{
   size_t i;
   for (i = 0; i < frames; i++)
   {
      size_t pos = (audio_head + audio_count) % AUDIO_RING;
      audio_ring[pos * 2] = data[i * 2];
      audio_ring[pos * 2 + 1] = data[i * 2 + 1];
      if (audio_count < AUDIO_RING)
         audio_count++;
      else
         audio_head = (audio_head + 1) % AUDIO_RING; /* desborde: descarta lo mas viejo */
   }
   return frames;
}

static void RETRO_CALLCONV audio_sample_cb(int16_t left, int16_t right)
{
   int16_t s[2] = { left, right };
   audio_batch_cb(s, 1);
}

static void RETRO_CALLCONV input_poll_cb(void) {}

static int16_t RETRO_CALLCONV input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id)
{
   (void)index;
   switch (device & RETRO_DEVICE_MASK)
   {
      case RETRO_DEVICE_JOYPAD:
         if (port != 0 || id > 15)
            return 0;
         return (pad_mask >> id) & 1;
      case RETRO_DEVICE_KEYBOARD:
         return id < RETROK_LAST ? keys[id] : 0;
      default:
         return 0;
   }
}

/* --------------------------------------------------------------- API */

int zx_init(const char *sys, const char *save, zx_log_fn log)
{
   if (initialized)
      return 1;
   log_fn = log;
   strncpy(system_dir, sys ? sys : ".", sizeof(system_dir) - 1);
   strncpy(save_dir, save ? save : ".", sizeof(save_dir) - 1);

   retro_set_environment(environment_cb);
   retro_set_video_refresh(video_cb);
   retro_set_audio_sample(audio_sample_cb);
   retro_set_audio_sample_batch(audio_batch_cb);
   retro_set_input_poll(input_poll_cb);
   retro_set_input_state(input_state_cb);
   retro_init();
   initialized = 1;
   return 1;
}

void zx_deinit(void)
{
   if (!initialized)
      return;
   zx_unload();
   retro_deinit();
   initialized = 0;
}

void zx_clear_options(void)
{
   num_options = 0;
   options_dirty = 1;
}

void zx_set_option(const char *key, const char *value)
{
   int i;
   for (i = 0; i < num_options; i++)
   {
      if (strcmp(options[i].key, key) == 0)
      {
         strncpy(options[i].value, value, sizeof(options[i].value) - 1);
         options_dirty = 1;
         return;
      }
   }
   if (num_options >= MAX_OPTIONS)
      return;
   memset(&options[num_options], 0, sizeof(zx_option));
   strncpy(options[num_options].key, key, sizeof(options[0].key) - 1);
   strncpy(options[num_options].value, value, sizeof(options[0].value) - 1);
   num_options++;
   options_dirty = 1;
}

static void *read_file(const char *path, size_t *size)
{
   FILE *f = fopen(path, "rb");
   long len;
   void *buf;
   if (!f)
      return NULL;
   fseek(f, 0, SEEK_END);
   len = ftell(f);
   fseek(f, 0, SEEK_SET);
   if (len <= 0)
   {
      fclose(f);
      return NULL;
   }
   buf = malloc((size_t)len);
   if (buf && fread(buf, 1, (size_t)len, f) != (size_t)len)
   {
      free(buf);
      buf = NULL;
   }
   fclose(f);
   *size = (size_t)len;
   return buf;
}

static void apply_ports(void)
{
   /* Puerto 0: joystick (mando/tactil). Puerto 1: nada. Puerto 2: teclado. */
   retro_set_controller_port_device(0, RETRO_DEVICE_SUBCLASS(RETRO_DEVICE_JOYPAD, joystick_type));
   retro_set_controller_port_device(1, RETRO_DEVICE_NONE);
   retro_set_controller_port_device(2, RETRO_DEVICE_SUBCLASS(RETRO_DEVICE_KEYBOARD, 0));
}

int zx_load(const char *path)
{
   struct retro_game_info info;
   size_t size = 0;

   if (!initialized)
      return 0;
   zx_unload();

   content_data = read_file(path, &size);
   if (!content_data)
   {
      zx_logf(RETRO_LOG_ERROR, "No se pudo leer %s\n", path);
      return 0;
   }

   memset(&info, 0, sizeof(info));
   info.path = path;
   info.data = content_data;
   info.size = size;

   zx_release_all();
   audio_head = audio_count = 0;
   frame_w = frame_h = 0;
   options_dirty = 1;

   if (!retro_load_game(&info))
   {
      zx_logf(RETRO_LOG_ERROR, "retro_load_game fallo para %s\n", path);
      free(content_data);
      content_data = NULL;
      return 0;
   }

   retro_get_system_av_info(&av_info);
   apply_ports();
   loaded = 1;
   return 1;
}

void zx_unload(void)
{
   if (loaded)
   {
      retro_unload_game();
      loaded = 0;
   }
   free(content_data);
   content_data = NULL;
}

int zx_is_loaded(void) { return loaded; }

void zx_reset(void)
{
   if (loaded)
      retro_reset();
}

void zx_run_frame(void)
{
   if (loaded)
      retro_run();
}

const uint16_t *zx_frame(int *width, int *height, int *pitch_pixels)
{
   if (frame_w == 0 || frame_h == 0)
      return NULL;
   if (width) *width = frame_w;
   if (height) *height = frame_h;
   if (pitch_pixels) *pitch_pixels = frame_w;
   return frame_buf;
}

unsigned zx_frame_counter(void) { return frame_count; }

size_t zx_audio_read(int16_t *dst, size_t max_frames)
{
   size_t n = audio_count < max_frames ? audio_count : max_frames;
   size_t i;
   for (i = 0; i < n; i++)
   {
      size_t pos = (audio_head + i) % AUDIO_RING;
      dst[i * 2] = audio_ring[pos * 2];
      dst[i * 2 + 1] = audio_ring[pos * 2 + 1];
   }
   audio_head = (audio_head + n) % AUDIO_RING;
   audio_count -= n;
   return n;
}

double zx_fps(void) { return av_info.timing.fps > 0 ? av_info.timing.fps : 50.0; }
double zx_sample_rate(void) { return av_info.timing.sample_rate > 0 ? av_info.timing.sample_rate : 44100.0; }

void zx_set_joystick_type(int type)
{
   joystick_type = type;
   if (loaded)
      retro_set_controller_port_device(0, RETRO_DEVICE_SUBCLASS(RETRO_DEVICE_JOYPAD, joystick_type));
}

void zx_set_joypad(unsigned mask) { pad_mask = mask; }

void zx_set_key(unsigned retrok, int down)
{
   if (retrok < RETROK_LAST)
      keys[retrok] = down ? 1 : 0;
}

void zx_release_all(void)
{
   pad_mask = 0;
   memset((void *)keys, 0, sizeof(keys));
}

int zx_state_save(const char *path)
{
   size_t size;
   void *buf;
   FILE *f;
   int ok = 0;

   if (!loaded)
      return 0;
   size = retro_serialize_size();
   if (size == 0)
      return 0;
   buf = malloc(size);
   if (!buf)
      return 0;
   if (retro_serialize(buf, size))
   {
      f = fopen(path, "wb");
      if (f)
      {
         ok = fwrite(buf, 1, size, f) == size;
         fclose(f);
      }
   }
   free(buf);
   return ok;
}

int zx_state_load(const char *path)
{
   size_t size = 0;
   void *buf;
   int ok;

   if (!loaded)
      return 0;
   buf = read_file(path, &size);
   if (!buf)
      return 0;
   ok = retro_unserialize(buf, size) ? 1 : 0;
   free(buf);
   return ok;
}

int zx_tape_count(void)
{
   if (!loaded || !has_disk_cb || !disk_cb.get_num_images)
      return 0;
   return (int)disk_cb.get_num_images();
}

int zx_tape_index(void)
{
   if (!loaded || !has_disk_cb || !disk_cb.get_image_index)
      return 0;
   return (int)disk_cb.get_image_index();
}

int zx_tape_select(unsigned index)
{
   if (!loaded || !has_disk_cb || index >= (unsigned)zx_tape_count())
      return 0;
   /* Mismo protocolo que un frontend libretro: expulsar, elegir, insertar */
   if (!disk_cb.set_eject_state(true))
      return 0;
   if (!disk_cb.set_image_index(index))
   {
      disk_cb.set_eject_state(false);
      return 0;
   }
   return disk_cb.set_eject_state(false) ? 1 : 0;
}

const char *zx_take_message(void)
{
   if (!has_message)
      return NULL;
   has_message = 0;
   return message;
}
