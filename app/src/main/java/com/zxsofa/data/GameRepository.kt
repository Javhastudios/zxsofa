package com.zxsofa.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.zxsofa.net.CoverCandidate
import com.zxsofa.net.CoverFinder
import com.zxsofa.net.ZxdbEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
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
        /** Era un fichero .pok: se ha asignado a [game]. */
        data class PokAttached(val game: Game) : ImportResult()
        /** Era una revista en PDF: se ha añadido a la biblioteca de revistas. */
        data class MagazineAdded(val magazine: Magazine) : ImportResult()
        /** Era una copia de seguridad de ZX Sofá: se han añadido [added] juegos. */
        data class Backup(val added: Int, val updated: Int, val skipped: Int) : ImportResult()
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
        if (name.lowercase().endsWith(".pok")) {
            return@withContext attachPokByName(name, file)
        }
        if (name.lowercase().endsWith(".pdf") || isPdf(file)) {
            return@withContext importMagazine(name, file)
        }
        if (isBackup(file)) {
            val (added, updated, skipped) = importBackup(file)
            return@withContext ImportResult.Backup(added, updated, skipped)
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

    /** Saca una cinta de un juego y la convierte en un juego aparte (por si se agrupó mal). */
    suspend fun splitTape(game: Game, path: String): Game? = withContext(Dispatchers.IO) {
        val g = db.get(game.id) ?: return@withContext null
        if (path !in g.allFiles || g.allFiles.size < 2) return@withContext null
        val remaining = withSortedFiles(g, g.allFiles - path)
        db.update(remaining)
        val name = File(path).name
        val newGame = Game(
            title = guessTitle(name),
            fileName = name,
            filePath = path,
            md5 = md5(File(path).readBytes()),
        )
        val id = db.insert(newGame)
        _games.value = db.all()
        newGame.copy(id = id)
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
        // 1) Índice de ZXDB incluido en la app: no necesita conexión para los datos
        val local = try { com.zxsofa.net.LocalZxdb.identify(context, game.fileName, game.title) } catch (e: Exception) { null }
        if (local != null) {
            applyEntry(game, local.toEntry(), null, null)
            return "Identificado: ${local.title}" + (local.year?.let { " ($it)" } ?: "")
        }
        // 2) API de ZXInfo (por huella del fichero o por título)
        val byHash = try { finder.identifyByMd5(game.md5) } catch (e: Exception) { null }
        if (byHash != null) {
            applyEntry(game, byHash, null, null)
            return "Identificado en ZXDB: ${byHash.title}"
        }
        val target = CoverFinder.normalize(game.title)
        val byTitle = try { finder.search(game.title) } catch (e: Exception) { emptyList() }.firstOrNull { CoverFinder.normalize(it.title) == target }
        if (byTitle != null) {
            applyEntry(game, byTitle, null, null)
            return "Encontrado por título en ZXDB: ${byTitle.title}"
        }
        val lr = finder.libretroCandidates(game.title, 1).firstOrNull()
        if (lr != null && CoverFinder.normalize(lr.title) == target && game.coverPath == null) {
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
        // Los datos se ven al momento; las imágenes y documentos llegan después
        withContext(Dispatchers.IO) {
            db.update(g)
            _games.value = db.all()
        }

        // Las imágenes automáticas nunca sustituyen a las que ya tiene el juego (pueden ser tuyas);
        // solo se cambian cuando eliges un candidato en «Buscar portada».
        val manual = coverUrls != null
        val screen = screenUrls ?: entry.screen?.let { finder.mediaUrls(it) }
        val cover = coverUrls ?: entry.inlays.firstOrNull()?.let { finder.mediaUrls(it) } ?: screen
        val wantScreen = !screen.isNullOrEmpty() && (screenUrls != null || g.screenPath == null)
        val wantCover = !cover.isNullOrEmpty() && (manual || g.coverPath == null)
        // Carátula y pantalla de carga a la vez
        kotlinx.coroutines.coroutineScope {
            val c = if (wantCover) async { setCover(g, cover!!) } else null
            if (wantScreen) setScreen(g, screen!!)
            c?.await()
        }
        g = withContext(Dispatchers.IO) { db.get(g.id) } ?: g
        withContext(Dispatchers.IO) { _games.value = db.all() }
        // Instrucciones de ZXDB, si el juego aún no tiene ninguna
        if (g.docs.isEmpty() && entry.instructions.isNotEmpty()) {
            try { g = downloadInstructions(g, entry) } catch (_: Exception) {}
        }
        // POKEs: primero los de ZXDB, si no los de Tipshop
        if (g.pokPath == null && entry.pok != null) {
            val dest = File(pokesDir, "${g.id}.pok")
            if (finder.downloadAny(finder.mediaUrls(entry.pok), dest)) {
                g = g.copy(pokPath = dest.absolutePath)
                withContext(Dispatchers.IO) { db.update(g) }
            }
        }
        if (g.pokPath == null) {
            try { g = autoPokes(g) ?: g } catch (_: Exception) {}
        }
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

    // Carátula y pantalla se descargan a la vez: la escritura en la base de datos va de una en una
    private val imageLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun replaceImage(game: Game, file: File, isCover: Boolean): Game = imageLock.withLock { withContext(Dispatchers.IO) {
        val current = db.get(game.id) ?: game
        val old = if (isCover) current.coverPath else current.screenPath
        val updated = if (isCover) current.copy(coverPath = file.absolutePath) else current.copy(screenPath = file.absolutePath)
        db.update(updated)
        if (old != null && old != file.absolutePath) File(old).delete()
        updated
    } }

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
        (game.allFiles + game.docs + listOfNotNull(game.coverPath, game.screenPath, game.pokPath)).forEach { File(it).delete() }
        File(docsRoot, game.id.toString()).delete()
        File(context.cacheDir, "cintas_${game.id}.m3u").delete()
        statesDir.listFiles { f -> f.name.startsWith("${game.id}_") }?.forEach { it.delete() }
        db.delete(game.id)
        _games.value = db.all()
    }

    // ------------------------------------------------------- partidas

    fun autoStateFile(gameId: Long) = File(statesDir, "${gameId}_auto.state")
    fun slotStateFile(gameId: Long, slot: Int = 1) = File(statesDir, "${gameId}_slot$slot.state")


    // ------------------------------------------------ instrucciones y POKEs

    private val docsRoot = File(context.filesDir, "docs").apply { mkdirs() }
    private val pokesDir = File(context.filesDir, "pokes").apply { mkdirs() }
    private fun docsDir(id: Long) = File(docsRoot, id.toString()).apply { mkdirs() }

    /** Baja de ZXDB las instrucciones (y mapas) del juego. */
    suspend fun downloadInstructions(game: Game, entry: ZxdbEntry): Game = withContext(Dispatchers.IO) {
        var g = db.get(game.id) ?: game
        for (path in entry.instructions.take(4)) {
            val name = safeName(path.substringAfterLast('/'))
            if (g.docs.any { File(it).name == name }) continue
            val dest = uniqueFile(docsDir(g.id), name)
            if (finder.downloadAny(finder.mediaUrls(path), dest)) g = g.copy(docs = g.docs + dest.absolutePath)
        }
        db.update(g)
        g
    }

    /** Busca instrucciones en ZXDB (identificando el juego si hace falta). Devuelve cuántas se añadieron. */
    suspend fun fetchInstructions(game: Game): Int {
        val entry = game.zxdbId?.let { com.zxsofa.net.LocalZxdb.byId(context, it)?.toEntry() }
            ?: com.zxsofa.net.LocalZxdb.identify(context, game.fileName, game.title)?.toEntry()
            ?: try { game.zxdbId?.let { finder.getEntry(it) } ?: finder.identifyByMd5(game.md5) } catch (e: Exception) { null }
            ?: return -1
        val before = game.docs.size
        val g = downloadInstructions(game, entry)
        refresh()
        return g.docs.size - before
    }

    suspend fun addDocs(game: Game, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var g = db.get(game.id) ?: return@withContext 0
        var n = 0
        for (uri in uris) {
            val name = safeName(displayName(uri) ?: "documento")
            val dest = uniqueFile(docsDir(g.id), name)
            context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: continue
            g = g.copy(docs = g.docs + dest.absolutePath)
            n++
        }
        db.update(g)
        _games.value = db.all()
        n
    }

    suspend fun removeDoc(game: Game, path: String) = withContext(Dispatchers.IO) {
        val g = db.get(game.id) ?: return@withContext
        File(path).delete()
        db.update(g.copy(docs = g.docs - path))
        _games.value = db.all()
    }

    /** Descarga un .pok de Tipshop y lo asigna al juego. */
    suspend fun setPokFromTipshop(game: Game, tipshopPath: String): Game? = withContext(Dispatchers.IO) {
        val dest = File(pokesDir, "${game.id}.pok")
        if (!finder.downloadAny(listOf(finder.pokUrl(tipshopPath)), dest)) return@withContext null
        val g = (db.get(game.id) ?: game).copy(pokPath = dest.absolutePath)
        db.update(g)
        _games.value = db.all()
        g
    }

    /** Busca y asigna automáticamente el .pok si el título coincide claramente. */
    suspend fun autoPokes(game: Game): Game? {
        val best = finder.pokCandidates(game.title, game.year, 1).firstOrNull() ?: return null
        if (finder.pokScore(game.title, best) < 80) return null
        return setPokFromTipshop(game, best)
    }

    suspend fun setPokFromUri(game: Game, uri: Uri): Game? = withContext(Dispatchers.IO) {
        val dest = File(pokesDir, "${game.id}.pok")
        context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: return@withContext null
        val g = (db.get(game.id) ?: game).copy(pokPath = dest.absolutePath)
        db.update(g)
        _games.value = db.all()
        g
    }

    suspend fun removePok(game: Game) = withContext(Dispatchers.IO) {
        val g = db.get(game.id) ?: return@withContext
        g.pokPath?.let { File(it).delete() }
        db.update(g.copy(pokPath = null))
        _games.value = db.all()
    }

    /** Un .pok recibido suelto (Wi-Fi o +): se asigna al juego cuyo título coincide. */
    private fun attachPokByName(name: String, file: File): ImportResult {
        val alnum = { t: String -> t.lowercase().replace(Regex("[^a-z0-9]"), "") }
        val key = alnum(name.substringBeforeLast('.').substringBefore(" ("))
        val g = db.all().firstOrNull { alnum(it.title) == key || alnum(guessTitle(it.fileName)) == key }
            ?: return ImportResult.Error("$name: no hay ningún juego con ese título. Añádelo desde la ficha del juego (POKEs).")
        val dest = File(pokesDir, "${g.id}.pok")
        file.copyTo(dest, overwrite = true)
        db.update(g.copy(pokPath = dest.absolutePath))
        _games.value = db.all()
        return ImportResult.PokAttached(g)
    }

    /** Número de trucos de un .pok (líneas que empiezan por N). */
    fun pokCount(path: String?): Int = try {
        if (path == null) 0 else File(path).readLines(Charsets.ISO_8859_1).count { it.startsWith("N") }
    } catch (e: Exception) {
        0
    }

    // ------------------------------------------------------------ revistas

    private val magazinesDir = File(context.filesDir, "magazines").apply { mkdirs() }
    private val _magazines = MutableStateFlow<List<Magazine>>(emptyList())
    val magazines: StateFlow<List<Magazine>> = _magazines

    suspend fun refreshMagazines() = withContext(Dispatchers.IO) { _magazines.value = db.magazines() }

    suspend fun magazine(id: Long): Magazine? = withContext(Dispatchers.IO) { db.magazine(id) }

    private fun isPdf(file: File): Boolean = try {
        file.inputStream().use { val b = ByteArray(5); it.read(b) == 5 && String(b) == "%PDF-" }
    } catch (e: Exception) {
        false
    }

    private suspend fun importMagazine(name: String, file: File): ImportResult = withContext(Dispatchers.IO) {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val dest = uniqueFile(magazinesDir, safeName(base.ifBlank { "revista.pdf" }).let { if (it.lowercase().endsWith(".pdf")) it else "$it.pdf" })
        file.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }

        // Comprobamos que Android puede abrirla, contamos páginas y hacemos la miniatura de la portada
        var pages = 0
        var thumb: File? = null
        try {
            android.os.ParcelFileDescriptor.open(dest, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { r ->
                    pages = r.pageCount
                    if (pages > 0) {
                        r.openPage(0).use { page ->
                            val w = 360
                            val h = (w * page.height.toFloat() / page.width).toInt().coerceIn(1, 1200)
                            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val t = File(magazinesDir, "${dest.nameWithoutExtension}_portada.jpg")
                            t.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                            bmp.recycle()
                            thumb = t
                        }
                    }
                }
            }
        } catch (e: Exception) {
            dest.delete()
            return@withContext ImportResult.Error("$base: no se puede abrir el PDF (¿protegido con contraseña o dañado?)")
        }
        val title = base.substringBeforeLast('.').replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifBlank { "Revista" }
        val m = Magazine(title = title, path = dest.absolutePath, thumbPath = thumb?.absolutePath, pages = pages)
        val id = db.insertMagazine(m)
        _magazines.value = db.magazines()
        ImportResult.MagazineAdded(m.copy(id = id))
    }

    suspend fun updateMagazine(m: Magazine) = withContext(Dispatchers.IO) {
        db.updateMagazine(m)
        _magazines.value = db.magazines()
    }

    /** Guarda la página por la que vas (sin refrescar la lista en cada página). */
    suspend fun setMagazinePage(id: Long, page: Int) = withContext(Dispatchers.IO) {
        db.magazine(id)?.let { db.updateMagazine(it.copy(lastPage = page)) }
    }

    suspend fun deleteMagazine(m: Magazine) = withContext(Dispatchers.IO) {
        File(m.path).delete()
        m.thumbPath?.let { File(it).delete() }
        db.deleteMagazine(m.id)
        _magazines.value = db.magazines()
    }

    // ------------------------------------------------- copia de seguridad

    /**
     * Crea un .zip con toda la colección: un índice (zxsofa-backup.json) y, por
     * cada juego, sus cintas, carátula, pantalla de carga y partidas guardadas.
     */
    suspend fun exportBackup(out: java.io.OutputStream, includeMagazines: Boolean = false) = withContext(Dispatchers.IO) {
        val list = db.all()
        val mags = if (includeMagazines) db.magazines() else emptyList()
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
                val docs = org.json.JSONArray()
                g.docs.forEach { path ->
                    val entry = "$dir/docs/${File(path).name}"
                    put(entry, File(path))
                    docs.put(entry)
                }
                g.pokPath?.let { put("$dir/game.pok", File(it)) }
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
                    put("docs", docs)
                    put("pok", if (g.pokPath != null && File(g.pokPath).exists()) "$dir/game.pok" else null)
                })
            }
            val magIndex = org.json.JSONArray()
            for (m in mags) {
                val dir = "m${m.id}"
                put("$dir/${File(m.path).name}", File(m.path))
                m.thumbPath?.let { put("$dir/portada.jpg", File(it)) }
                magIndex.put(org.json.JSONObject().apply {
                    put("title", m.title); put("pages", m.pages); put("lastPage", m.lastPage); put("addedAt", m.addedAt)
                    put("file", "$dir/${File(m.path).name}")
                    put("thumb", if (m.thumbPath != null && File(m.thumbPath).exists()) "$dir/portada.jpg" else null)
                })
            }
            zip.putNextEntry(java.util.zip.ZipEntry(BACKUP_INDEX))
            zip.write(org.json.JSONObject().put("app", "ZX Sofa").put("version", 1).put("games", index).put("magazines", magIndex).toString(1).toByteArray())
            zip.closeEntry()
        }
        list.size
    }

    /** Copia de seguridad en un fichero temporal (para enviarla por Wi-Fi o descargarla). */
    suspend fun exportBackupToFile(includeMagazines: Boolean = false): File = withContext(Dispatchers.IO) {
        val f = File(context.cacheDir, "coleccion-zxsofa.zip")
        f.outputStream().use { exportBackup(it, includeMagazines) }
        f
    }

    /** Lo que ocupan todas las revistas (para avisar antes de incluirlas en la copia). */
    fun magazinesSize(): Long = db.magazines().sumOf { File(it.path).length() }

    fun isBackup(file: File): Boolean = try {
        java.util.zip.ZipFile(file).use { it.getEntry(BACKUP_INDEX) != null }
    } catch (e: Exception) {
        false
    }

    /** Añade los juegos de una copia de seguridad que no estén ya (se compara la huella MD5). */
    suspend fun importBackup(file: File): Triple<Int, Int, Int> = withContext(Dispatchers.IO) {
        var added = 0
        var updated = 0
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
                val existing = if (md5.isNotBlank()) db.findByMd5(md5) else null
                if (existing != null) {
                    // El juego ya está: completamos lo que le falte, sin pisar lo que ya tiene
                    var g: Game = existing
                    val unidentified = g.zxdbId == null && g.year == null && g.publisher == null
                    if (unidentified) JSONObjectStr(o, "title")?.let { g = g.copy(title = it) }
                    g = g.copy(
                        year = g.year ?: JSONObjectStr(o, "year"),
                        publisher = g.publisher ?: JSONObjectStr(o, "publisher"),
                        genre = g.genre ?: JSONObjectStr(o, "genre"),
                        zxdbId = g.zxdbId ?: JSONObjectStr(o, "zxdbId"),
                        notes = g.notes ?: JSONObjectStr(o, "notes"),
                        favorite = g.favorite || o.optBoolean("favorite"),
                        playCount = maxOf(g.playCount, o.optInt("playCount")),
                        lastPlayed = maxOf(g.lastPlayed, o.optLong("lastPlayed")),
                    )
                    if (unidentified) {
                        g = g.copy(
                            machine = Machine.fromCore(JSONObjectStr(o, "machine") ?: g.machine.coreValue),
                            controls = ControlConfig.parse(JSONObjectStr(o, "controls") ?: g.controls.serialize()),
                        )
                    }
                    val stamp = System.currentTimeMillis()
                    if (g.coverPath == null || !File(g.coverPath!!).exists()) {
                        File(coversDir, "${g.id}_$stamp.img").takeIf { extract(JSONObjectStr(o, "cover"), it) }?.let { g = g.copy(coverPath = it.absolutePath) }
                    }
                    if (g.screenPath == null || !File(g.screenPath!!).exists()) {
                        File(screensDir, "${g.id}_$stamp.img").takeIf { extract(JSONObjectStr(o, "screen"), it) }?.let { g = g.copy(screenPath = it.absolutePath) }
                    }
                    if (g.pokPath == null || !File(g.pokPath!!).exists()) {
                        File(pokesDir, "${g.id}.pok").takeIf { extract(JSONObjectStr(o, "pok"), it) }?.let { g = g.copy(pokPath = it.absolutePath) }
                    }
                    o.optJSONArray("docs")?.let { d ->
                        for (j in 0 until d.length()) {
                            val entry = d.getString(j)
                            val name = safeName(entry.substringAfterLast('/'))
                            if (g.docs.any { File(it).name == name }) continue
                            val dest = uniqueFile(docsDir(g.id), name)
                            if (extract(entry, dest)) g = g.copy(docs = g.docs + dest.absolutePath)
                        }
                    }
                    // Cintas/caras que falten
                    o.optJSONArray("files")?.let { fs ->
                        for (j in 1 until fs.length()) {
                            val entry = fs.getString(j)
                            val name = safeName(entry.substringAfterLast('/').substringAfter('-'))
                            if (g.allFiles.any { File(it).name.equals(name, ignoreCase = true) }) continue
                            val dest = uniqueFile(gamesDir, name)
                            if (extract(entry, dest)) g = withSortedFiles(g, g.allFiles + dest.absolutePath)
                        }
                    }
                    // Partidas guardadas que no existan aquí (nunca se sobrescriben las tuyas)
                    o.optJSONArray("states")?.let { st ->
                        for (j in 0 until st.length()) {
                            val entry = st.getString(j)
                            val dest = File(statesDir, "${g.id}_${entry.substringAfterLast('/')}")
                            if (!dest.exists()) extract(entry, dest)
                        }
                    }
                    if (g != existing) { db.update(g); updated++ } else skipped++
                    continue
                }
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
                val docPaths = mutableListOf<String>()
                o.optJSONArray("docs")?.let { d ->
                    for (j in 0 until d.length()) {
                        val entry = d.getString(j)
                        val dest = uniqueFile(docsDir(id), safeName(entry.substringAfterLast('/')))
                        if (extract(entry, dest)) docPaths += dest.absolutePath
                    }
                }
                val pok = File(pokesDir, "$id.pok").takeIf { extract(JSONObjectStr(o, "pok"), it) }
                db.update(base.copy(id = id, coverPath = cover?.absolutePath, screenPath = screen?.absolutePath,
                    docs = docPaths, pokPath = pok?.absolutePath))
                added++
            }
            // Revistas (si la copia las incluye): se saltan las que ya están (mismo título y páginas)
            root.optJSONArray("magazines")?.let { ms ->
                val existing = db.magazines()
                for (i in 0 until ms.length()) {
                    val o = ms.getJSONObject(i)
                    val title = o.optString("title")
                    val pages = o.optInt("pages")
                    if (existing.any { it.title == title && it.pages == pages }) continue
                    val entry = JSONObjectStr(o, "file") ?: continue
                    val dest = uniqueFile(magazinesDir, safeName(entry.substringAfterLast('/')))
                    if (!extract(entry, dest)) continue
                    val thumb = File(magazinesDir, "${dest.nameWithoutExtension}_portada.jpg").takeIf { extract(JSONObjectStr(o, "thumb"), it) }
                    db.insertMagazine(Magazine(title = title, path = dest.absolutePath, thumbPath = thumb?.absolutePath,
                        pages = pages, lastPage = o.optInt("lastPage"), addedAt = o.optLong("addedAt", System.currentTimeMillis())))
                }
                _magazines.value = db.magazines()
            }
        }
        _games.value = db.all()
        Triple(added, updated, skipped)
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

    /**
     * Clave para saber si dos ficheros son caras del mismo juego: el nombre completo sin la
     * marca de cara. Se conserva lo que va entre paréntesis, porque ahí suele estar lo que
     * distingue una cinta de otra ("(Issue 36)" frente a "(Issue 37)", el año, la compañía...).
     */
    private fun groupKey(fileName: String): String =
        Sides.stripMarkers(fileName.substringAfterLast('/').substringBeforeLast('.')).lowercase().replace(Regex("[^a-z0-9]"), "")

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
