package com.zxsofa.net

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Ficha de un juego en ZXDB (a través de la API pública de ZXInfo). */
data class ZxdbEntry(
    val id: String,
    val title: String,
    val year: String?,
    val publisher: String?,
    val genre: String?,
    val machineType: String?,
    /** Rutas de carátulas, la portada frontal primero. */
    val inlays: List<String>,
    /** Pantalla de carga (o la primera captura disponible). */
    val screen: String?,
    /** Instrucciones (TXT, PDF o imágenes). */
    val instructions: List<String> = emptyList(),
)

/** Una portada candidata para mostrar en el buscador. */
data class CoverCandidate(
    val source: String,
    val title: String,
    val subtitle: String,
    /** Imagen a mostrar como miniatura (URLs alternativas, se usa la primera que funcione). */
    val previewUrls: List<String>,
    /** Imagen a guardar como portada. */
    val coverUrls: List<String>,
    val screenUrls: List<String> = emptyList(),
    val entry: ZxdbEntry? = null,
)

/**
 * Busca datos y portadas en dos bases de datos:
 *  - ZXDB vía ZXInfo (api.zxinfo.dk): identifica el fichero por su MD5 o busca por título.
 *  - libretro-thumbnails (carátulas de la comunidad de RetroArch).
 */
class CoverFinder(private val context: Context) {

    private val api = "https://api.zxinfo.dk/v3"

    // ------------------------------------------------------------ ZXInfo

    /** Identifica un fichero por su huella MD5. Devuelve null si ZXDB no lo conoce. */
    suspend fun identifyByMd5(md5: String): ZxdbEntry? = withContext(Dispatchers.IO) {
        val json = getJson("$api/filecheck/$md5") ?: return@withContext null
        val id = json.optString("entry_id").ifBlank { return@withContext null }
        getEntry(id)
    }

    suspend fun getEntry(id: String): ZxdbEntry? = withContext(Dispatchers.IO) {
        val json = getJson("$api/games/$id?mode=compact") ?: return@withContext null
        val src = json.optJSONObject("_source") ?: return@withContext null
        parseEntry(json.optString("_id", id), src)
    }

    suspend fun search(query: String): List<ZxdbEntry> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val json = getJson("$api/search?query=$q&mode=compact&size=20&offset=0&sort=rel_desc&titlesonly=true&contenttype=SOFTWARE")
            ?: return@withContext emptyList()
        val hits = json.optJSONObject("hits")?.optJSONArray("hits") ?: return@withContext emptyList()
        (0 until hits.length()).mapNotNull { i ->
            val h = hits.optJSONObject(i) ?: return@mapNotNull null
            val src = h.optJSONObject("_source") ?: return@mapNotNull null
            parseEntry(h.optString("_id"), src)
        }
    }

    private fun parseEntry(id: String, src: JSONObject): ZxdbEntry {
        val publishers = src.optJSONArray("publishers")
        val publisher = publishers?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                .distinct().joinToString(", ")
        }?.takeIf { it.isNotBlank() }

        val year = src.opt("originalYearOfRelease")?.toString()?.takeIf { it.isNotBlank() && it != "null" }

        val genre = src.optString("genre").takeIf { it.isNotBlank() && it != "null" }
            ?: listOf(src.optString("genreType"), src.optString("genreSubType"))
                .filter { it.isNotBlank() && it != "null" }.joinToString(": ").takeIf { it.isNotBlank() }

        // Carátulas: additionalDownloads de tipo "Inlay" que sean imágenes; la frontal primero
        val inlays = mutableListOf<Pair<Int, String>>()
        src.optJSONArray("additionalDownloads")?.forEachObject { d ->
            val type = d.optString("type")
            val format = d.optString("format")
            val path = d.optString("path")
            if (path.isBlank() || !type.contains("inlay", ignoreCase = true)) return@forEachObject
            if (!format.startsWith("Picture", ignoreCase = true) && !path.lowercase().let { it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".gif") }) return@forEachObject
            if (type.contains("back", ignoreCase = true) || path.contains("back", ignoreCase = true)) return@forEachObject
            val priority = when {
                type.contains("front", ignoreCase = true) -> 0
                else -> 1
            }
            inlays += priority to path
        }

        // Instrucciones y mapas en formatos que sabemos mostrar
        val docs = mutableListOf<String>()
        src.optJSONArray("additionalDownloads")?.forEachObject { d ->
            val type = d.optString("type").lowercase()
            val path = d.optString("path")
            val ext = path.substringAfterLast('.', "").lowercase()
            if (path.isNotBlank() && (type.contains("instruction") || type.contains("manual") || type.contains("map")) &&
                ext in listOf("txt", "pdf", "jpg", "jpeg", "png", "gif", "htm", "html")
            ) docs += path
        }

        // Pantallas: preferimos la de carga
        var screen: String? = null
        var loading: String? = null
        src.optJSONArray("screens")?.forEachObject { s ->
            val url = s.optString("url").ifBlank { s.optString("path") }
            if (url.isBlank()) return@forEachObject
            val fmt = s.optString("format")
            if (fmt.isNotBlank() && !fmt.startsWith("Picture", true) && !url.lowercase().let { it.endsWith(".png") || it.endsWith(".gif") || it.endsWith(".jpg") }) return@forEachObject
            if (loading == null && s.optString("type").contains("loading", ignoreCase = true)) loading = url
            if (screen == null) screen = url
        }

        return ZxdbEntry(
            id = id,
            title = src.optString("title"),
            year = year,
            publisher = publisher,
            genre = genre,
            machineType = src.optString("machineType").takeIf { it.isNotBlank() && it != "null" },
            inlays = inlays.sortedBy { it.first }.map { it.second }.distinct(),
            screen = loading ?: screen,
            instructions = docs.distinct(),
        )
    }

    /** Candidatos de ZXDB para el buscador de portadas. */
    fun candidatesFrom(entry: ZxdbEntry): List<CoverCandidate> {
        val screenUrls = entry.screen?.let { mediaUrls(it) } ?: emptyList()
        val subtitle = listOfNotNull(entry.year, entry.publisher).joinToString(" · ")
        if (entry.inlays.isEmpty()) {
            // Sin carátula: ofrecemos la pantalla de carga como portada
            return if (screenUrls.isEmpty()) emptyList() else listOf(
                CoverCandidate("ZXDB (pantalla)", entry.title, subtitle, screenUrls, screenUrls, screenUrls, entry)
            )
        }
        return entry.inlays.take(4).map { path ->
            val urls = mediaUrls(path)
            CoverCandidate("ZXDB", entry.title, subtitle, urls, urls, screenUrls, entry)
        }
    }

    /** Las rutas de ZXDB se sirven desde varios espejos; probamos en orden. */
    fun mediaUrls(path: String): List<String> {
        if (path.startsWith("http")) return listOf(path)
        val p = if (path.startsWith("/")) path else "/$path"
        val encoded = p.split("/").joinToString("/") { seg -> URLEncoder.encode(seg, "UTF-8").replace("+", "%20") }
        return listOf(
            "https://zxinfo.dk/media$encoded",
            "https://spectrumcomputing.co.uk$encoded",
        )
    }

    // ----------------------------------------------------- libretro

    private val libretroNames: List<String> by lazy {
        try {
            context.assets.open("libretro_boxarts.txt").bufferedReader().readLines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Busca carátulas de libretro cuyo nombre se parezca al título. */
    fun libretroCandidates(title: String, max: Int = 6): List<CoverCandidate> {
        val target = normalize(title)
        if (target.isBlank()) return emptyList()
        val targetTokens = target.split(" ").toSet()
        return libretroNames.asSequence()
            .map { name -> name to score(target, targetTokens, normalize(name.substringBefore(" ("))) }
            .filter { it.second >= 45 }
            .sortedByDescending { it.second }
            .take(max)
            .map { (name, _) ->
                val urls = libretroUrls(name)
                val publisher = Regex("\\(([^)]*)\\)").find(name)?.groupValues?.get(1) ?: ""
                CoverCandidate("libretro", name.substringBefore(" ("), publisher, urls, urls)
            }
            .toList()
    }

    private fun libretroUrls(name: String): List<String> {
        // libretro sustituye &*/:`<>?\| por _ en los nombres de fichero (ya vienen así en la lista)
        val enc = URLEncoder.encode("$name.png", "UTF-8").replace("+", "%20")
        return listOf(
            "https://raw.githubusercontent.com/libretro-thumbnails/Sinclair_-_ZX_Spectrum/master/Named_Boxarts/$enc",
            "https://thumbnails.libretro.com/Sinclair%20-%20ZX%20Spectrum/Named_Boxarts/$enc",
        )
    }

    private fun score(target: String, targetTokens: Set<String>, candidate: String): Int {
        if (candidate.isBlank()) return 0
        if (candidate == target) return 100
        if (candidate.startsWith(target) || target.startsWith(candidate)) return 80
        val tokens = candidate.split(" ").toSet()
        val inter = tokens.intersect(targetTokens).size
        val union = tokens.union(targetTokens).size
        return if (union == 0) 0 else (inter * 75) / union
    }

    // ---------------------------------------------------- POKEs (Tipshop)

    private val pokNames: List<String> by lazy {
        try {
            context.assets.open("tipshop_pokes.txt").bufferedReader().readLines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Ficheros .pok de The Tipshop cuyo título se parece; el más probable primero. */
    fun pokCandidates(title: String, year: String? = null, max: Int = 8): List<String> {
        val target = normalize(title)
        if (target.isBlank()) return emptyList()
        val tokens = target.split(" ").toSet()
        return pokNames.asSequence()
            .map { path ->
                val name = path.substringAfterLast('/').removeSuffix(".pok")
                var sc = score(target, tokens, normalize(name.substringBefore(" (")))
                if (year != null && name.contains("($year)")) sc += 5
                path to sc
            }
            .filter { it.second >= 45 }
            .sortedByDescending { it.second }
            .take(max)
            .map { it.first }
            .toList()
    }

    fun pokUrl(path: String): String =
        "https://raw.githubusercontent.com/ladyeklipse/all-tipshop-pokes/master/" +
            path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    fun pokScore(title: String, path: String): Int {
        val target = normalize(title)
        return score(target, target.split(" ").toSet(), normalize(path.substringAfterLast('/').removeSuffix(".pok").substringBefore(" (")))
    }

    /** Descarga un fichero cualquiera (instrucciones, .pok...). */
    suspend fun downloadAny(urls: List<String>, dest: File): Boolean = withContext(Dispatchers.IO) {
        for (url in urls) {
            try {
                val bytes = getBytes(url) ?: continue
                if (bytes.isEmpty()) continue
                dest.parentFile?.mkdirs()
                dest.writeBytes(bytes)
                return@withContext true
            } catch (e: Exception) {
            }
        }
        false
    }

    // ------------------------------------------------------- utilidades

    /** Descarga la primera URL que devuelva una imagen válida. */
    suspend fun download(urls: List<String>, dest: File): Boolean = withContext(Dispatchers.IO) {
        for (url in urls) {
            try {
                val bytes = getBytes(url) ?: continue
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) continue
                dest.parentFile?.mkdirs()
                dest.writeBytes(bytes)
                return@withContext true
            } catch (e: Exception) {
                // siguiente espejo
            }
        }
        false
    }

    private fun getJson(url: String): JSONObject? = try {
        getBytes(url)?.let { JSONObject(String(it, Charsets.UTF_8)) }
    } catch (e: Exception) {
        null
    }

    private fun getBytes(url: String, redirects: Int = 4): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "ZXSofa/1.0 (Android; uso personal)")
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code in 300..399 && redirects > 0) {
                // HttpURLConnection no sigue redirecciones entre http y https
                val loc = conn.getHeaderField("Location") ?: return null
                return getBytes(URL(URL(url), loc).toString(), redirects - 1)
            }
            if (code != 200) return null
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun normalize(s: String): String =
            s.lowercase()
                .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
                .replace("&", " and ")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
                .removePrefix("the ")
                .replace(Regex("\\s+"), " ")
    }
}

private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(block)
}
