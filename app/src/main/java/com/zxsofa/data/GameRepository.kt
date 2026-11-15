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
        /** Era otra cara/cinta de un juego que ya estaba: se ha unido a él. */
        data class AddedSide(val game: Game) : ImportResult()
        data class Error(val message: String) : ImportResult()
        /** Era una copia de seguridad de ZX Sofá: se han añadido [added] juegos. */
        data class Backup(val added: Int, val skipped: Int) : ImportResult()
    }

    suspend fun importUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: uri.lastPathSegment ?: "juego.tap"
        val tmp = File.createTempFile("import", ".bin", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: return@withContext ImportResult.Error("No se pudo leer $name")
            importFile(name, tmp)
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "Error al importar")
        } finally {
            tmp.delete()
        }
    }

    /** Importa un fichero ya guardado en disco: un juego o una copia de seguridad completa. */
    suspend fun importFile(name: String, file: File): ImportResult = withContext(Dispatchers.IO) {
        if (isBackup(file)) {
            val (added, skipped) = importBackup(file)
            return@withContext ImportResult.Backup(added, skipped)
        }
        if (file.length() > MAX_FILE_SIZE) return@withContext ImportResult.Error("$name es demasiado grande")
        importBytes(name, file.readBytes())
    }

    suspend fun importBytes(originalName: String, data: ByteArray): ImportResult = withContext(Dispatchers.IO) {
        if (data.size > MAX_FILE_SIZE) return@withContext ImportResult.Error("$originalName es demasiado grande")
        val name = originalName.substringAfterLast('/').substringAfterLast('\\')

        // Un zip puede traer varias cintas o caras: se guardan todas como un único juego
        val files: List<Pair<String, ByteArray>> = if (name.lowercase().endsWith(".zip")) {
            extractAllFromZip(data).ifEmpty { return@withContext ImportResult.Error("$name no contiene ningún juego de Spectrum") }
        } else {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in SUPPORTED) return@withContext ImportResult.Error("$name: formato .$ext no soportado")
            listOf(name to data)
        }

        val first = files.first()
        val md5 = md5(first.second)
        db.findByMd5(md5)?.let { return@withContext ImportResult.Duplicate(it) }

        // ¿Es otra cara de un juego que ya tenemos? ("Robocop (Side B)" junto a "Robocop (Side A)")
        if (files.size == 1 && Sides.hasMarker(first.first)) {
            val key = groupKey(first.first)
            val owner = db.all().firstOrNull { g -> g.allFiles.any { Sides.hasMarker(it) } && groupKey(g.fileName) == key }
            if (owner != null) {
                if (owner.allFiles.any { File(it).name.equals(safeName(first.first), ignoreCase = true) }) {
                    return@withContext ImportResult.Duplicate(owner)
                }
                val file = uniqueFile(gamesDir, safeName(first.first))
                file.writeBytes(first.second)
                val updated = withSortedFiles(owner, owner.allFiles + file.absolutePath)
                db.update(updated)
                _games.value = db.all()
                return@withContext ImportResult.AddedSide(updated)
            }
        }

        val saved = files
            .map { (n, bytes) -> n to uniqueFile(gamesDir, safeName(n)).also { it.writeBytes(bytes) }.absolutePath }
            .sortedWith { a, b -> Sides.compare(a.first, b.first) }
        val primaryName = saved.first().first
        val sorted = saved.map { it.second }

        val game = Game(
            title = guessTitle(primaryName),
            fileName = primaryName,
            filePath = sorted.first(),
            extraFiles = sorted.drop(1),
            md5 = md5(File(sorted.first()).readBytes()),
            machine = if (Regex("(^|[^0-9])128([^0-9]|$)").containsMatchIn(name)) Machine.SPEC128 else Machine.DEFAULT,
        )
        val id = db.insert(game)
        _games.value = db.all()
        ImportResult.Added(game.copy(id = id))
    }

    /** Ordena las cintas (Cara A primero) y recalcula la principal y su huella. */
    private fun withSortedFiles(g: Game, paths: List<String>): Game {
        val sorted = paths.distinct().sortedWith { a, b -> Sides.compare(File(a).name, File(b).name) }
        val primary = sorted.first()
        return if (primary == g.filePath) {
            g.copy(extraFiles = sorted.drop(1))
        } else {
            g.copy(
                filePath = primary,
                fileName = File(primary).name,
                extraFiles = sorted.drop(1),
                md5 = md5(File(primary).readBytes()),
            )
        }
    }

    /** Añade a mano otra cinta/cara a un juego (desde la pantalla Editar). */
    suspend fun addTapes(game: Game, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var g = db.get(game.id) ?: return@withContext 0
        var added = 0
        for (uri in uris) {
            val name = displayName(uri) ?: uri.lastPathSegment ?: continue
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
            val items = if (name.lowercase().endsWith(".zip")) extractAllFromZip(bytes)
            else if (name.substringAfterLast('.', "").lowercase() in SUPPORTED) listOf(name to bytes) else emptyList()
            for ((n, b) in items) {
                val file = uniqueFile(gamesDir, safeName(n))
                file.writeBytes(b)
                g = withSortedFiles(g, g.allFiles + file.absolutePath)
                added++
            }
        }
        db.update(g)
        _games.value = db.all()
        added
    }

    /** Quita una cinta extra del juego (no la principal). */
    suspend fun removeTape(game: Game, path: String) = withContext(Dispatchers.IO) {
        val g = db.get(game.id) ?: return@withContext
        if (path == g.filePath || path !in g.extraFiles) return@withContext
        File(path).delete()
        db.update(g.copy(extraFiles = g.extraFiles - path))
        _games.value = db.all()
    }

    /**
     * Fichero que hay que cargar en el emulador: el propio juego o, si tiene varias
     * cintas, una lista .m3u con todas (el emulador permite cambiar entre ellas).
     */
    fun launchPath(game: Game): String {
        if (game.extraFiles.isEmpty()) return game.filePath
        val m3u = File(context.cacheDir, "cintas_${game.id}.m3u")
        m3u.writeText(game.allFiles.joinToString("\n") + "\n")
        return m3u.absolutePath
    }

    private fun extractAllFromZip(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val found = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name.substringAfterLast('/')
                val ext = n.substringAfterLast('.', "").lowercase()
                if (ext in SUPPORTED) found += n to zip.readBytes()
            }
        }
        if (found.size <= 1) return found
        // Si hay cintas y también snapshots del mismo juego, nos quedamos con el formato preferido
        val bestExt = found.minOf { SUPPORTED.indexOf(it.first.substringAfterLast('.').lowercase()) }
        return found.filter { SUPPORTED.indexOf(it.first.substringAfterLast('.').lowercase()) == bestExt }
            .sortedWith { a, b -> Sides.compare(a.first, b.first) }
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
        (game.allFiles + listOfNotNull(game.coverPath, game.screenPath)).forEach { File(it).delete() }
        File(context.cacheDir, "cintas_${game.id}.m3u").delete()
        statesDir.listFiles { f -> f.name.startsWith("${game.id}_") }?.forEach { it.delete() }
        db.delete(game.id)
        _games.value = db.all()
    }

    // ------------------------------------------------------- partidas

    fun autoStateFile(gameId: Long) = File(statesDir, "${gameId}_auto.state")
    fun slotStateFile(gameId: Long, slot: Int = 1) = File(statesDir, "${gameId}_slot$slot.state")


    // ------------------------------------------------- copia de seguridad

    /**
     * Crea un .zip con toda la colección: un índice (zxsofa-backup.json) y, por
     * cada juego, sus cintas, carátula, pantalla de carga y partidas guardadas.
     */
    suspend fun exportBackup(out: java.io.OutputStream) = withContext(Dispatchers.IO) {
        val list = db.all()
        val index = org.json.JSONArray()
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(out)).use { zip ->
            fun put(entry: String, f: File) {
                if (!f.exists()) return
                zip.putNextEntry(java.util.zip.ZipEntry(entry))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            for (g in list) {
                val dir = "g${g.id}"
                val files = org.json.JSONArray()
                g.allFiles.forEachIndexed { i, path ->
                    val entry = "$dir/files/$i-${File(path).name}"
                    put(entry, File(path))
                    files.put(entry)
                }
                val states = org.json.JSONArray()
                statesDir.listFiles { f -> f.name.startsWith("${g.id}_") }?.forEach { f ->
                    val entry = "$dir/states/${f.name.removePrefix("${g.id}_")}"
                    put(entry, f)
                    states.put(entry)
                }
                g.coverPath?.let { put("$dir/cover.img", File(it)) }
                g.screenPath?.let { put("$dir/screen.img", File(it)) }
                index.put(org.json.JSONObject().apply {
                    put("title", g.title); put("fileName", g.fileName); put("md5", g.md5)
                    put("files", files); put("states", states)
                    put("cover", if (g.coverPath != null && File(g.coverPath).exists()) "$dir/cover.img" else null)
                    put("screen", if (g.screenPath != null && File(g.screenPath).exists()) "$dir/screen.img" else null)
                    put("year", g.year); put("publisher", g.publisher); put("genre", g.genre)
                    put("machine", g.machine.coreValue); put("controls", g.controls.serialize())
                    put("zxdbId", g.zxdbId); put("favorite", g.favorite); put("addedAt", g.addedAt)
                    put("lastPlayed", g.lastPlayed); put("playCount", g.playCount); put("notes", g.notes)
                })
            }
            zip.putNextEntry(java.util.zip.ZipEntry(BACKUP_INDEX))
            zip.write(org.json.JSONObject().put("app", "ZX Sofa").put("version", 1).put("games", index).toString(1).toByteArray())
            zip.closeEntry()
        }
        list.size
    }

    /** Copia de seguridad en un fichero temporal (para enviarla por Wi-Fi o descargarla). */
    suspend fun exportBackupToFile(): File = withContext(Dispatchers.IO) {
        val f = File(context.cacheDir, "coleccion-zxsofa.zip")
        f.outputStream().use { exportBackup(it) }
        f
    }

    fun isBackup(file: File): Boolean = try {
        java.util.zip.ZipFile(file).use { it.getEntry(BACKUP_INDEX) != null }
    } catch (e: Exception) {
        false
    }

    /** Añade los juegos de una copia de seguridad que no estén ya (se compara la huella MD5). */
    suspend fun importBackup(file: File): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var added = 0
        var skipped = 0
        java.util.zip.ZipFile(file).use { zip ->
            val root = org.json.JSONObject(zip.getInputStream(zip.getEntry(BACKUP_INDEX)).use { String(it.readBytes()) })
            val arr = root.optJSONArray("games") ?: org.json.JSONArray()
            fun extract(entry: String?, dest: File): Boolean {
                if (entry.isNullOrBlank()) return false
                val e = zip.getEntry(entry) ?: return false
                dest.parentFile?.mkdirs()
                zip.getInputStream(e).use { input -> dest.outputStream().use { input.copyTo(it) } }
                return true
            }
            fun JSONObjectStr(o: org.json.JSONObject, k: String): String? =
                if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }

            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val md5 = o.optString("md5")
                if (md5.isNotBlank() && db.findByMd5(md5) != null) { skipped++; continue }
                val entries = o.optJSONArray("files") ?: continue
                val paths = (0 until entries.length()).mapNotNull { j ->
                    val entry = entries.getString(j)
                    val name = entry.substringAfterLast('/').substringAfter('-')
                    val dest = uniqueFile(gamesDir, safeName(name))
                    if (extract(entry, dest)) dest.absolutePath else null
                }
                if (paths.isEmpty()) continue
                val base = Game(
                    title = o.optString("title").ifBlank { guessTitle(File(paths[0]).name) },
                    fileName = o.optString("fileName").ifBlank { File(paths[0]).name },
                    filePath = paths[0],
                    extraFiles = paths.drop(1),
                    md5 = md5.ifBlank { md5(File(paths[0]).readBytes()) },
                    year = JSONObjectStr(o, "year"),
                    publisher = JSONObjectStr(o, "publisher"),
                    genre = JSONObjectStr(o, "genre"),
                    machine = Machine.fromCore(JSONObjectStr(o, "machine")),
                    controls = ControlConfig.parse(JSONObjectStr(o, "controls")),
                    zxdbId = JSONObjectStr(o, "zxdbId"),
                    favorite = o.optBoolean("favorite"),
                    addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                    lastPlayed = o.optLong("lastPlayed"),
                    playCount = o.optInt("playCount"),
                    notes = JSONObjectStr(o, "notes"),
                )
                val id = db.insert(base)
                val stamp = System.currentTimeMillis()
                val cover = File(coversDir, "${id}_$stamp.img").takeIf { extract(JSONObjectStr(o, "cover"), it) }
                val screen = File(screensDir, "${id}_$stamp.img").takeIf { extract(JSONObjectStr(o, "screen"), it) }
                o.optJSONArray("states")?.let { st ->
                    for (j in 0 until st.length()) {
                        val entry = st.getString(j)
                        extract(entry, File(statesDir, "${id}_${entry.substringAfterLast('/')}"))
                    }
                }
                db.update(base.copy(id = id, coverPath = cover?.absolutePath, screenPath = screen?.absolutePath))
                added++
            }
        }
        _games.value = db.all()
        added to skipped
    }

    // --------------------------------------------------------- ayudas

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._()\\- ]"), "_")

    private fun groupKey(fileName: String) = CoverFinder.normalize(Sides.stripMarkers(guessTitle(fileName)))

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
        const val BACKUP_INDEX = "zxsofa-backup.json"

        fun md5(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

        /** "Head Over Heels (1987)(Ocean)[128K].tzx" -> "Head Over Heels" */
        fun guessTitle(fileName: String): String {
            var t = fileName.substringBeforeLast('.')
            t = t.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            t = Sides.stripMarkers(t)
            t = t.replace('_', ' ').replace(Regex("\\s+-\\s*(128k?|48k?|side [ab]|tape \\d)\\s*$", RegexOption.IGNORE_CASE), " ")
            t = t.replace(Regex("\\s+"), " ").trim().trimEnd('-', '_', ',', '.', ' ')
            return t.ifBlank { fileName }
        }
    }
}
