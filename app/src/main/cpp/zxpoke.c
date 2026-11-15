/*
 * POKEs (trucos) desde ficheros .pok, usando el módulo pokemem de Fuse.
 */
#include <config.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libspectrum.h>

#include "pokefinder/pokemem.h"

#include "zxcore.h"

static char *listing;

int zx_pok_load(const char *path)
{
   if (!zx_is_loaded())
      return -1;
   if (pokemem_read_from_file(path))
      return -1;
   {
      int n = 0;
      GSList *it;
      for (it = trainer_list; it; it = it->next) n++;
      return n;
   }
}

void zx_pok_clear(void)
{
   pokemem_clear();
}

/* Una línea por truco: "indice\tactivo\tpide_valor\troto\tnombre" */
const char *zx_pok_list(void)
{
   GSList *it;
   size_t cap = 256, len = 0;
   int i = 0;
   char *buf = malloc(cap);
   if (!buf)
      return "";
   buf[0] = 0;
   for (it = trainer_list; it; it = it->next, i++)
   {
      trainer_t *t = (trainer_t *)it->data;
      char line[320];
      size_t n;
      snprintf(line, sizeof(line), "%d\t%d\t%d\t%d\t%s\n", i, t->active ? 1 : 0, t->ask_value ? 1 : 0,
               t->disabled ? 1 : 0, t->name ? t->name : "?");
      n = strlen(line);
      if (len + n + 1 > cap)
      {
         char *nb;
         while (len + n + 1 > cap) cap *= 2;
         nb = realloc(buf, cap);
         if (!nb) break;
         buf = nb;
      }
      memcpy(buf + len, line, n + 1);
      len += n;
   }
   free(listing);
   listing = buf;
   return listing;
}

int zx_pok_set(int index, int active, int value)
{
   GSList *it = trainer_list;
   trainer_t *t;
   while (it && index-- > 0) it = it->next;
   t = it ? (trainer_t *)it->data : NULL;
   if (!t || t->disabled || !zx_is_loaded())
      return 0;
   if (active)
   {
      if (t->ask_value)
         t->value = (libspectrum_byte)(value & 0xff);
      return pokemem_trainer_activate(t) == 0;
   }
   return pokemem_trainer_deactivate(t) == 0;
}
