/*
 * zxcore: mini frontend libretro para el nucleo Fuse.
 * No depende de Android: se usa desde jni_bridge.c y desde test/zxtest.c.
 */
#ifndef ZXCORE_H
#define ZXCORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Tipos de joystick (coinciden con RETRO_DEVICE_* de fuse-libretro/src/externs.h) */
enum zx_joystick {
   ZX_JOY_CURSOR = 0,
   ZX_JOY_KEMPSTON = 1,
   ZX_JOY_SINCLAIR1 = 2,
   ZX_JOY_SINCLAIR2 = 3,
   ZX_JOY_FULLER = 6
};

/* Bits de la mascara del joypad (= RETRO_DEVICE_ID_JOYPAD_*) */
#define ZX_PAD_B      0
#define ZX_PAD_Y      1
#define ZX_PAD_SELECT 2
#define ZX_PAD_START  3
#define ZX_PAD_UP     4
#define ZX_PAD_DOWN   5
#define ZX_PAD_LEFT   6
#define ZX_PAD_RIGHT  7
#define ZX_PAD_A      8
#define ZX_PAD_X      9
#define ZX_PAD_L      10
#define ZX_PAD_R      11

typedef void (*zx_log_fn)(int level, const char *msg);

int  zx_init(const char *system_dir, const char *save_dir, zx_log_fn log);
void zx_deinit(void);

/* Opciones del nucleo (fuse_machine, fuse_size_border, ...). Aplican en la
   siguiente carga o en el siguiente fotograma segun la opcion. */
void zx_set_option(const char *key, const char *value);
void zx_clear_options(void);

int  zx_load(const char *path);
int  zx_load_empty(void);
void zx_unload(void);
int  zx_is_loaded(void);
void zx_reset(void);
void zx_run_frame(void);

/* Ultimo fotograma, RGB565. Devuelve NULL si aun no hay ninguno. */
const uint16_t *zx_frame(int *width, int *height, int *pitch_pixels);
unsigned zx_frame_counter(void);

/* Audio estereo int16 a 44100 Hz. Devuelve frames estereo copiados. */
size_t zx_audio_read(int16_t *dst, size_t max_frames);
double zx_fps(void);
double zx_sample_rate(void);

void zx_set_joystick_type(int type);
void zx_set_joypad(unsigned mask);
void zx_set_key(unsigned retrok, int down);
void zx_release_all(void);

int  zx_state_save(const char *path);
int  zx_state_load(const char *path);

/* Cintas (caras) cargadas desde un .m3u. zx_tape_select cambia de cinta sin reiniciar. */
int  zx_tape_count(void);
int  zx_tape_index(void);
int  zx_tape_select(unsigned index);

/* Catálogo de la cinta actual: una línea por entrada "bloque\ttipo\ttexto",
   tipo P = programa BASIC, G = inicio de grupo (TZX), T = texto/comentario. */
const char *zx_tape_catalog(void);
/* Reinicia y carga la cinta a partir del bloque indicado. */
int  zx_tape_load_block(int block);

/* Bloque actual de la cinta (o -1) y situar la cinta en un bloque sin reiniciar. */
int  zx_tape_block(void);
int  zx_tape_set_block(int block);

/* POKEs (.pok). zx_pok_load devuelve el número de trucos o -1.
   zx_pok_list: una línea por truco "indice\tactivo\tpide_valor\troto\tnombre". */
int  zx_pok_load(const char *path);
void zx_pok_clear(void);
const char *zx_pok_list(void);
int  zx_pok_set(int index, int active, int value);

/* Guardar la cinta (con lo grabado con SAVE) en un fichero. */
int  zx_tape_write(const char *path);
int  zx_tape_present(void);

/* Mensaje pendiente del nucleo (o NULL). Se vacia al leerlo. */
const char *zx_take_message(void);

#ifdef __cplusplus
}
#endif
#endif
