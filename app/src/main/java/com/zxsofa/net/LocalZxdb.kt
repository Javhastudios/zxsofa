package com.zxsofa.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.zip.GZIPInputStream

/** Una ficha del índice de ZXDB que lleva la app (assets/zxdb_index.tsv.gz). */
data class LocalEntry(
    val id: Int,
    val title: String,
    val year: String?,
    val publisher: String?,
    val genre: String?,
    val machine: String?,
    val inlay: String?,
    val screen: String?,
    val docs: List<String>,
    val pok: String?,
) {
    /** Mismo formato que las fichas de la API de ZXInfo (id de 7 cifras). */
    fun toEntry() = ZxdbEntry(
        id = id.toString().padStart(7, '0'),
        title = title,
        year = year,
        publisher = publisher,
        genre = genre,
        machineType = machine,
        inlays = listOfNotNull(inlay),
        screen = screen,
        instructions = docs,
        pok = pok,
    )
}

/**
 * Índice de ZXDB incluido en la app: permite identificar juegos y conocer año,
 * compañía, género, carátula, instrucciones y POKEs sin depender de ninguna API.
 * Las imágenes y documentos se descargan después de Spectrum Computing.
 */
object LocalZxdb {
    private val lock = Mutex()
    private var entries: List<LocalEntry> = emptyList()
    private var byKey: Map<String, List<Int>> = emptyMap()   // clave -> posiciones en entries
    private var byId: Map<Int, Int> = emptyMap()
    private var titleKeys: Array<String> = emptyArray()

    fun key(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private suspend fun ensureLoaded(context: Context) = lock.withLock {
        if (entries.isNotEmpty()) return@withLock
        withContext(Dispatchers.IO) { loadFrom(GZIPInputStream(context.assets.open("zxdb_index.tsv.gz"))) }
    }

    /** Carga el índice (separado para poder probarlo fuera de Android). */
    fun loadFrom(input: java.io.InputStream) {
        run {
            val list = ArrayList<LocalEntry>(31000)
            val keys = HashMap<String, MutableList<Int>>(80000)
            val ids = HashMap<Int, Int>(31000)
            val titles = ArrayList<String>(31000)
            input.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val p = line.split('\t')
                    if (p.size < 11) return@forEach
                    val e = LocalEntry(
                        id = p[0].toIntOrNull() ?: return@forEach,
                        title = p[1],
                        year = p[2].ifBlank { null },
                        publisher = p[3].ifBlank { null },
                        genre = p[4].ifBlank { null },
                        machine = p[5].ifBlank { null },
                        inlay = p[6].ifBlank { null },
                        screen = p[7].ifBlank { null },
                        docs = p[8].split('|').filter { it.isNotBlank() },
                        pok = p[9].ifBlank { null },
                    )
                    val idx = list.size
                    list += e
                    ids[e.id] = idx
                    val tk = key(e.title)
                    titles += tk
                    keys.getOrPut(tk) { mutableListOf() }.add(idx)
                    p[10].split('|').filter { it.isNotBlank() }.forEach { k -> keys.getOrPut(k) { mutableListOf() }.add(idx) }
                }
            }
            entries = list
            byKey = keys
            byId = ids
            titleKeys = titles.toTypedArray()
        }
    }

    suspend fun byId(context: Context, zxdbId: String): LocalEntry? {
        ensureLoaded(context)
        val i = byId[zxdbId.trimStart('0').toIntOrNull() ?: return null] ?: return null
        return entries[i]
    }

    /**
     * Identifica un fichero por su nombre. Prueba el nombre tal cual ("SabreWulf.tzx",
     * típico de World of Spectrum) y el título limpio ("Sabre Wulf (1984)(Ultimate).tzx", TOSEC).
     * Con varias coincidencias desempata por año y compañía del nombre.
     */
    suspend fun identify(context: Context, fileName: String, title: String): LocalEntry? {
        ensureLoaded(context)
        return identifyLoaded(fileName, title)
    }

    fun identifyLoaded(fileName: String, title: String): LocalEntry? {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.')
        val candidates = linkedSetOf<Int>()
        listOf(key(base), key(base.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), "")), key(title))
            .filter { it.length >= 3 }
            .forEach { k -> byKey[k]?.let { candidates.addAll(it) } }
        if (candidates.isEmpty()) return null

        val year = Regex("\\((19[78]\\d|20\\d\\d)\\)").find(fileName)?.groupValues?.get(1)
        val parens = Regex("\\(([^)]*)\\)").findAll(fileName).map { key(it.groupValues[1]) }.filter { it.length >= 3 }.toList()
        return candidates.map { entries[it] }.maxByOrNull { e ->
            var s = 0
            if (year != null && e.year == year) s += 10
            val pk = key(e.publisher ?: "")
            if (pk.isNotEmpty() && parens.any { pk.startsWith(it) || it.startsWith(pk) }) s += 8
            if (key(e.title) == key(title)) s += 5
            if (e.inlay != null) s += 2
            if (e.genre?.contains("Game") == true) s += 1
            s
        }
    }

    /** Búsqueda por título (para el buscador de portadas), sin conexión. */
    suspend fun search(context: Context, query: String, max: Int = 30): List<LocalEntry> {
        ensureLoaded(context)
        return searchLoaded(query, max)
    }

    fun searchLoaded(query: String, max: Int = 30): List<LocalEntry> {
        val q = key(query)
        if (q.length < 2) return emptyList()
        val exact = byKey[q].orEmpty()
        val starts = ArrayList<Int>()
        val contains = ArrayList<Int>()
        for (i in titleKeys.indices) {
            val t = titleKeys[i]
            if (t == q) continue
            if (t.startsWith(q)) starts += i else if (t.contains(q)) contains += i
            if (starts.size > max * 2) break
        }
        return (exact + starts + contains).distinct().take(max).map { entries[it] }
    }
}
