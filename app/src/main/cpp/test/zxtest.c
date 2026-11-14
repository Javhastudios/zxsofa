/* Prueba en escritorio del nucleo: carga un juego, ejecuta N fotogramas,
   vuelca el ultimo fotograma (PPM) y el audio (WAV), y prueba savestates.
   Uso: zxtest <juego> <maquina> <fotogramas> <prefijo_salida> [tecla_frame tecla_retrok ...] */
#include "zxcore.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void write_ppm(const char *path)
{
   int w, h, p, x, y;
   const uint16_t *f = zx_frame(&w, &h, &p);
   FILE *o;
   if (!f) { fprintf(stderr, "sin fotograma\n"); return; }
   o = fopen(path, "wb");
   fprintf(o, "P6\n%d %d\n255\n", w, h);
   for (y = 0; y < h; y++)
      for (x = 0; x < w; x++)
      {
         uint16_t c = f[y * p + x];
         unsigned char rgb[3] = { (unsigned char)(((c >> 11) & 31) << 3),
                                  (unsigned char)(((c >> 5) & 63) << 2),
                                  (unsigned char)((c & 31) << 3) };
         fwrite(rgb, 1, 3, o);
      }
   fclose(o);
   printf("fotograma %dx%d -> %s\n", w, h, path);
}

static void wr32(FILE *f, unsigned v) { fwrite(&v, 4, 1, f); }
static void wr16(FILE *f, unsigned short v) { fwrite(&v, 2, 1, f); }

int main(int argc, char **argv)
{
   char out[512];
   int frames, i, k;
   int16_t *audio;
   size_t audio_frames = 0, cap;
   long nonzero = 0;
   FILE *wav;

   if (argc < 5)
   {
      fprintf(stderr, "uso: %s juego maquina fotogramas prefijo [frame tecla]...\n", argv[0]);
      return 1;
   }
   frames = atoi(argv[3]);
   zx_init(".", ".", NULL);
   zx_set_option("fuse_machine", argv[2]);
   zx_set_option("fuse_auto_load", "enabled");
   zx_set_option("fuse_fast_load", "enabled");
   if (!zx_load(argv[1]))
   {
      fprintf(stderr, "fallo al cargar\n");
      return 2;
   }
   printf("fps=%.2f rate=%.0f\n", zx_fps(), zx_sample_rate());

   cap = (size_t)frames * 1000;
   audio = malloc(cap * 4);
   for (i = 0; i < frames; i++)
   {
      /* pulsaciones programadas: pares (fotograma, retrok); se mantiene 5 fotogramas */
      for (k = 5; k + 1 < argc; k += 2)
      {
         int at = atoi(argv[k]), key = atoi(argv[k + 1]);
         if (i == at) zx_set_key((unsigned)key, 1);
         if (i == at + 5) zx_set_key((unsigned)key, 0);
      }
      zx_run_frame();
      audio_frames += zx_audio_read(audio + audio_frames * 2, cap - audio_frames);
      {
         const char *m = zx_take_message();
         if (m) printf("mensaje: %s\n", m);
      }
   }
   for (i = 0; i < (int)audio_frames * 2; i++) if (audio[i]) nonzero++;
   printf("audio: %zu frames (%.2f por fotograma), muestras no nulas %ld\n",
          audio_frames, (double)audio_frames / frames, nonzero);

   snprintf(out, sizeof out, "%s.ppm", argv[4]);
   write_ppm(out);

   snprintf(out, sizeof out, "%s.wav", argv[4]);
   wav = fopen(out, "wb");
   fwrite("RIFF", 1, 4, wav); wr32(wav, 36 + audio_frames * 4); fwrite("WAVEfmt ", 1, 8, wav);
   wr32(wav, 16); wr16(wav, 1); wr16(wav, 2); wr32(wav, 44100); wr32(wav, 44100 * 4); wr16(wav, 4); wr16(wav, 16);
   fwrite("data", 1, 4, wav); wr32(wav, audio_frames * 4); fwrite(audio, 4, audio_frames, wav); fclose(wav);

   /* savestate: guardar, avanzar, cargar y comprobar que el fotograma vuelve */
   snprintf(out, sizeof out, "%s.state", argv[4]);
   printf("save state: %s\n", zx_state_save(out) ? "ok" : "FALLO");
   for (i = 0; i < 100; i++) { zx_run_frame(); zx_audio_read(audio, cap); }
   printf("load state: %s\n", zx_state_load(out) ? "ok" : "FALLO");
   zx_run_frame();
   snprintf(out, sizeof out, "%s_after_load.ppm", argv[4]);
   write_ppm(out);

   /* recarga de otro contenido sin deinit */
   printf("recarga: %s\n", zx_load(argv[1]) ? "ok" : "FALLO");
   for (i = 0; i < 10; i++) zx_run_frame();
   zx_deinit();
   free(audio);
   return 0;
}
