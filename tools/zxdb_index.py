#!/usr/bin/env python3
"""
Genera app/src/main/assets/zxdb_index.tsv.gz a partir de una base SQLite de ZXDB
(https://github.com/zxdb/ZXDB, convertida con scripts/ZXDB_to_SQLite.py).

Una línea por programa:
  id  título  año  compañía  género  modelo  carátula  pantalla  docs  pok  claves
- docs y claves separados por '|'
- claves: nombres de fichero de cintas/snapshots y títulos alternativos, normalizados
  (minúsculas, solo letras y números) para identificar los ficheros del usuario.

Uso: python3 tools/zxdb_index.py zxdb.sqlite app/src/main/assets/zxdb_index.tsv.gz
"""
import gzip
import re
import sqlite3
import sys
from collections import defaultdict

SPECTRUM_MACHINES = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 26, 30}
EXCLUDED_GENRES = set(range(82, 110)) | {112, 115}  # libros, hardware, revistas, publicidad...
TAPE_TYPES = (8, 10, 11, 17, 18, 21, 22)
VIEWABLE = re.compile(r"\.(txt|pdf|jpe?g|png|gif)$", re.I)


def key(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


def clean(s):
    return (s or "").replace("\t", " ").replace("\n", " ").replace("|", "/").strip()


def main(db_path, out_path):
    con = sqlite3.connect(db_path)
    genres = dict(con.execute("select id, text from genretypes"))
    machines = dict(con.execute("select id, text from machinetypes"))

    years = {}
    for eid, seq, year in con.execute("select entry_id, release_seq, release_year from releases where release_year is not null order by entry_id, release_seq"):
        years.setdefault(eid, year)

    pubs = defaultdict(list)
    for eid, name in con.execute(
        "select p.entry_id, l.name from publishers p join labels l on l.id = p.label_id "
        "where p.release_seq = 0 order by p.entry_id, p.publisher_seq"
    ):
        if name not in pubs[eid]:
            pubs[eid].append(name)

    downloads = defaultdict(list)
    for eid, link, ft in con.execute("select entry_id, file_link, filetype_id from downloads"):
        downloads[eid].append((link, ft))

    aliases = defaultdict(list)
    for eid, title in con.execute("select entry_id, title from aliases"):
        aliases[eid].append(title)

    n = 0
    with gzip.open(out_path, "wt", encoding="utf-8", compresslevel=9) as out:
        for eid, title, mt, gt in con.execute("select id, title, machinetype_id, genretype_id from entries order by id"):
            if mt is not None and mt not in SPECTRUM_MACHINES:
                continue
            if gt in EXCLUDED_GENRES:
                continue
            dl = downloads.get(eid, [])
            local = [l for l, _ in dl if not l.startswith("http")]

            def pick(ft, pred=lambda l: True):
                c = [l for l, t in dl if t == ft and not l.startswith("http") and pred(l)]
                orig = [l for l in c if "/Rereleases/" not in l]
                return (orig or c or [None])[0]

            inlay = pick(5, lambda l: VIEWABLE.search(l))
            screen = pick(1, lambda l: l.lower().endswith((".scr", ".png", ".gif"))) or pick(2, lambda l: VIEWABLE.search(l))
            docs = [l for l, t in dl if t in (28, 29, 31) and not l.startswith("http") and VIEWABLE.search(l)][:4]
            pok = pick(74)
            keys = set()
            for l, t in dl:
                if t in TAPE_TYPES:
                    base = l.rsplit("/", 1)[-1]
                    base = re.sub(r"\.zip$", "", base, flags=re.I)
                    base = base.rsplit(".", 1)[0]
                    k = key(base)
                    if len(k) >= 3:
                        keys.add(k)
            for a in aliases.get(eid, []):
                k = key(a)
                if len(k) >= 3:
                    keys.add(k)
            keys.discard(key(title))
            if not local and not keys:
                # sin ningún fichero conocido: casi nunca será un juego que el usuario tenga
                continue
            row = [
                str(eid), clean(title), str(years.get(eid, "")), clean(", ".join(pubs.get(eid, []))),
                clean(genres.get(gt, "")), clean(machines.get(mt, "")),
                inlay or "", screen or "", "|".join(docs), pok or "", "|".join(sorted(keys)),
            ]
            out.write("\t".join(row) + "\n")
            n += 1
    print(f"{n} programas -> {out_path}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
