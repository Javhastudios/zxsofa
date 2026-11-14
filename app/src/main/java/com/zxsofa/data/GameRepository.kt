package com.zxsofa.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.zxsofa.net.CoverCandidate
import com.zxsofa.net.CoverFinder
import com.zxsofa.net.ZxdbEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class GameRepository(private val context: Context) {

    private val db = GameDb(context)
    val finder = CoverFinder(context)

    private val gamesDir = File(context.filesDir, "games").apply { mkdirs() }
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
    private val screensDir = File(context.filesDir, "screens").apply { mkdirs() }
    private val statesDir = File(context.filesDir, "states").apply { mkdirs() }

    private val _games = MutableStateFlow<List<Game>>(emptyList())
    val games: StateFlow<List<Game>> = _games

    suspend fun refresh() = withContext(Dispatchers.IO) { _games.value = db.all() }

    suspend fun get(id: Long): Game? = withContext(Dispatchers.IO) { db.get(id) }

    // ------------------------------------------------------------ importar

    sealed class ImportResult {
        data class Added(val game: Game) : ImportResult()
        data class Duplicate(val game: Game) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    suspend fun importUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val name = displayName(uri) ?: uri.lastPathSegment ?: "juego.tap"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext ImportResult.Error("No se pudo leer $name")
            importBytes(name, bytes)
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "Error al importar")
        }
    }

    suspend fun importBytes(originalName: String, data: ByteArray): ImportResult = withContext(Dispatchers.IO) {
        if (data.size > MAX_FILE_SIZE) return@withContext ImportResult.Error("$originalName es demasiado grande")
        var name = originalName.substringAfterLast('/').substringAfterLast('\\')
        var bytes = data

        if (name.lowercase().endsWith(".zip")) {
            val inner = extractFromZip(bytes) ?: return@withContext ImportResult.Error("$name no contiene ningún juego de Spectrum")
            name = inner.first
            bytes = inner.second
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in SUPPORTED) return@withContext ImportResult.Error("$name: formato .$ext no soportado")

        val md5 = md5(bytes)
        db.findByMd5(md5)?.let { return@withContext ImportResult.Duplicate(it) }

        val safeName = name.replace(Regex("[^A-Za-z0-9._()\\- ]"), "_")
        val file = uniqueFile(gamesDir, safeName)
        file.writeBytes(bytes)

        val game = Game(
            title = guessTitle(name),
            fileName = name,
            filePath = file.absolutePath,
            md5 = md5,
            machine = if (Regex("(^|[^0-9])128([^0-9]|$)").containsMatchIn(name)) Machine.SPEC128 else Machine.DEFAULT,
        )
        val id = db.insert(game)
        _games.value = db.all()
        ImportResult.Added(game.copy(id = id))
    }

    private fun extractFromZip(bytes: ByteArray): Pair<String, ByteArray>? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            // Preferimos cintas y snapshots por este orden
            val found = mutableListOf<Pair<String, ByteArray>>()
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name.substringAfterLast('/')
                val ext = n.substringAfterLast('.', "").lowercase()
                if (ext in SUPPORTED) found += n to zip.readBytes()
            }
            return found.minByOrNull { SUPPORTED.indexOf(it.first.substringAfterLast('.').lowercase()) }
        }
    }

    // ------------------------------------------------------ identificar

    /**
     * Intenta identificar el juego y bajar su portada automáticamente:
     * 1) por la huella MD5 del fichero en ZXDB, 2) por el título en ZXDB, 3) carátula de libretro.
     * Devuelve un texto con el resultado para mostrarlo al usuario.
     */
    suspend fun autoIdentify(game: Game): String {
        val byHash = finder.identifyByMd5(game.md5)
        if (byHash != null) {
            applyEntry(game, byHash, null, null)
            return "Identificado en ZXDB: ${byHash.title}"
        }
        val target = CoverFinder.normalize(game.title)
        val byTitle = finder.search(game.title).firstOrNull { CoverFinder.normalize(it.title) == target }
        if (byTitle != null) {
            applyEntry(game, byTitle, null, null)
            return "Encontrado por título en ZXDB: ${byTitle.title}"
        }
        val lr = finder.libretroCandidates(game.title, 1).firstOrNull()
        if (lr != null && CoverFinder.normalize(lr.title) == target) {
            setCover(game, lr.coverUrls)
            return "Portada encontrada en libretro"
        }
        return "No se encontró \"${game.title}\". Prueba a buscar la portada a mano."
    }

    /** Aplica la ficha de ZXDB al juego y baja carátula y pantalla de carga. */
    suspend fun applyEntry(game: Game, entry: ZxdbEntry, coverUrls: List<String>?, screenUrls: List<String>?): Game {
        val current = withContext(Dispatchers.IO) { db.get(game.id) } ?: game
        var g = current.copy(
            title = entry.title.ifBlank { game.title },
            year = entry.year ?: game.year,
            publisher = entry.publisher ?: game.publisher,
            genre = entry.genre ?: game.genre,
            zxdbId = entry.id,
            machine = Machine.fromZxdb(entry.machineType)?.let { m ->
                // Si ZXDB dice 48K mantenemos 128K salvo que el usuario lo cambie: casi todo funciona en 128K
                if (m == Machine.SPEC48 || m == Machine.SPEC16) game.machine else m
            } ?: game.machine,
        )
        withContext(Dispatchers.IO) { db.update(g) }

        val screen = screenUrls ?: entry.screen?.let { finder.mediaUrls(it) }
        if (!screen.isNullOrEmpty()) g = setScreen(g, screen)

        val cover = coverUrls ?: entry.inlays.firstOrNull()?.let { finder.mediaUrls(it) } ?: screen
        if (!cover.isNullOrEmpty()) g = setCover(g, cover)
        refresh()
        return g
    }

    suspend fun applyCandidate(game: Game, c: CoverCandidate, withMetadata: Boolean): Game {
        val entry = c.entry
        return if (withMetadata && entry != null) {
            applyEntry(game, entry, c.coverUrls, c.screenUrls.ifEmpty { null })
        } else {
            setCover(game, c.coverUrls).also { refresh() }
        }
    }

    suspend fun setCover(game: Game, urls: List<String>): Game {
        val dest = File(coversDir, "${game.id}_${System.currentTimeMillis()}.img")
        if (!finder.download(urls, dest)) return game
        return replaceImage(game, dest, isCover = true)
    }

    private suspend fun setScreen(game: Game, urls: List<String>): Game {
        val dest = File(screensDir, "${game.id}_${System.currentTimeMillis()}.img")
        if (!finder.download(urls, dest)) return game
        return replaceImage(game, dest, isCover = false)
    }

    /** Portada elegida desde la galería / archivos del dispositivo. */
    suspend fun setCoverFromUri(game: Game, uri: Uri): Game = withContext(Dispatchers.IO) {
        val dest = File(coversDir, "${game.id}_${System.currentTimeMillis()}.img")
        context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            ?: return@withContext game
        replaceImage(game, dest, isCover = true).also { _games.value = db.all() }
    }

    private suspend fun replaceImage(game: Game, file: File, isCover: Boolean): Game = withContext(Dispatchers.IO) {
        val current = db.get(game.id) ?: game
        val old = if (isCover) current.coverPath else current.screenPath
        val updated = if (isCover) current.copy(coverPath = file.absolutePath) else current.copy(screenPath = file.absolutePath)
        db.update(updated)
        if (old != null && old != file.absolutePath) File(old).delete()
        updated
    }

    // ---------------------------------------------------------- editar

    suspend fun update(game: Game) = withContext(Dispatchers.IO) {
        db.update(game)
        _games.value = db.all()
    }

    suspend fun toggleFavorite(game: Game) = update(game.copy(favorite = !game.favorite))

    suspend fun markPlayed(gameId: Long) = withContext(Dispatchers.IO) {
        db.get(gameId)?.let { db.update(it.copy(lastPlayed = System.currentTimeMillis(), playCount = it.playCount + 1)) }
        _games.value = db.all()
    }

    suspend fun delete(game: Game) = withContext(Dispatchers.IO) {
        listOfNotNull(game.filePath, game.coverPath, game.screenPath).forEach { File(it).delete() }
        statesDir.listFiles { f -> f.name.startsWith("${game.id}_") }?.forEach { it.delete() }
        db.delete(game.id)
        _games.value = db.all()
    }

    // ------------------------------------------------------- partidas

    fun autoStateFile(gameId: Long) = File(statesDir, "${gameId}_auto.state")
    fun slotStateFile(gameId: Long, slot: Int = 1) = File(statesDir, "${gameId}_slot$slot.state")

    // --------------------------------------------------------- ayudas

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        while (f.exists()) f = File(dir, "$base ($i).$ext").also { i++ }
        return f
    }

    companion object {
        /** Formatos que acepta el núcleo Fuse, en orden de preferencia dentro de un zip. */
        val SUPPORTED = listOf("tzx", "tap", "pzx", "z80", "szx", "sna", "dsk", "trd", "scl", "rzx", "dck", "csw")
        const val MAX_FILE_SIZE = 32 * 1024 * 1024

        fun md5(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

        /** "Head Over Heels (1987)(Ocean)[128K].tzx" -> "Head Over Heels" */
        fun guessTitle(fileName: String): String {
            var t = fileName.substringBeforeLast('.')
            t = t.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            t = t.replace('_', ' ').replace(Regex("\\s+-\\s*(128k?|48k?|side [ab]|tape \\d)\\s*$", RegexOption.IGNORE_CASE), " ")
            t = t.replace(Regex("\\s+"), " ").trim()
            return t.ifBlank { fileName }
        }
    }
}
