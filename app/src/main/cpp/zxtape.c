/*
 * Catálogo de la cinta y carga desde un punto concreto (para cintas
 * recopilatorias con varios juegos grabados uno detrás de otro).
 * Usa funciones internas de Fuse, por eso va separado de zxcore.c.
 */
#include <config.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libspectrum.h>

#include "fuse.h"
#include "machine.h"
#include "phantom_typist.h"
#include "tape.h"

#include "zxcore.h"

typedef struct
{
   char *buf;
   size_t len, cap;
   int index;
} walk_t;

static char *catalog;

static void append(walk_t *w, const char *s)
{
   size_t n = strlen(s);
   if (w->len + n + 1 > w->cap)
   {
      size_t cap = (w->cap ? w->cap * 2 : 4096);
      char *nb;
      while (cap < w->len + n + 1) cap *= 2;
      nb = realloc(w->buf, cap);
      if (!nb) return;
      w->buf = nb;
      w->cap = cap;
   }
   memcpy(w->buf + w->len, s, n + 1);
   w->len += n;
}

/* Copia un texto quitando tabuladores/saltos y caracteres no imprimibles */
static void clean(char *dst, size_t size, const char *src, size_t max)
{
   size_t i, j = 0;
   for (i = 0; src[i] && i < max && j + 1 < size; i++)
   {
      unsigned char c = (unsigned char)src[i];
      dst[j++] = (c < 32 || c >= 127) ? ' ' : (char)c;
   }
   while (j > 0 && dst[j - 1] == ' ') j--;
   dst[j] = 0;
}

static void visit(libspectrum_tape_block *block, void *user_data)
{
   walk_t *w = (walk_t *)user_data;
   int idx = w->index++;
   char text[128];
   char line[192];

   switch (libspectrum_tape_block_type(block))
   {
      case LIBSPECTRUM_TAPE_BLOCK_ROM:
      case LIBSPECTRUM_TAPE_BLOCK_TURBO:
      {
         libspectrum_byte *data = libspectrum_tape_block_data(block);
         /* Cabecera estándar de 19 bytes: flag 0x00, tipo 0x00 = programa BASIC */
         if (libspectrum_tape_block_data_length(block) == 19 && data && data[0] == 0x00 && data[1] == 0x00)
         {
            clean(text, sizeof(text), (const char *)&data[2], 10);
            snprintf(line, sizeof(line), "%d\tP\t%s\n", idx, text[0] ? text : "(sin nombre)");
            append(w, line);
         }
         break;
      }

      case LIBSPECTRUM_TAPE_BLOCK_GROUP_START:
      case LIBSPECTRUM_TAPE_BLOCK_COMMENT:
      case LIBSPECTRUM_TAPE_BLOCK_MESSAGE:
      {
         const char *t = libspectrum_tape_block_text(block);
         if (t && *t)
         {
            clean(text, sizeof(text), t, 100);
            if (text[0])
            {
               snprintf(line, sizeof(line), "%d\t%c\t%s\n", idx,
                        libspectrum_tape_block_type(block) == LIBSPECTRUM_TAPE_BLOCK_GROUP_START ? 'G' : 'T', text);
               append(w, line);
            }
         }
         break;
      }

      default:
         break;
   }
}

const char *zx_tape_catalog(void)
{
   walk_t w;
   if (!zx_is_loaded() || !tape_present())
      return "";
   memset(&w, 0, sizeof(w));
   append(&w, "");
   tape_foreach(visit, &w);
   free(catalog);
   catalog = w.buf;
   return catalog ? catalog : "";
}

int zx_tape_load_block(int block)
{
   int error;
   if (!zx_is_loaded() || !tape_present() || block < 0)
      return 0;

   fuse_emulation_pause();
   /* Como el autoarranque de Fuse: reinicia, escribe LOAD "" (o elige
      "Tape Loader" en el 128K) y deja la cinta en el bloque pedido */
   machine_reset(0);
   phantom_typist_activate(machine_current->machine, 0);
   error = tape_select_block((size_t)block);
   fuse_emulation_unpause();
   return error == 0;
}

/* Posición de la cinta (bloque actual). Las partidas guardadas de Fuse no la
   incluyen, así que la guardamos aparte para que los juegos multicarga sigan
   encontrando la fase siguiente al continuar una partida. */
int zx_tape_block(void)
{
   if (!zx_is_loaded() || !tape_present())
      return -1;
   return tape_get_current_block();
}

int zx_tape_set_block(int block)
{
   if (!zx_is_loaded() || !tape_present() || block < 0)
      return 0;
   return tape_select_block((size_t)block) == 0;
}
