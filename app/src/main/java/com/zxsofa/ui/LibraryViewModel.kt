package com.zxsofa.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zxsofa.ZXSofaApp
import com.zxsofa.data.Game
import com.zxsofa.data.GameRepository
import com.zxsofa.net.CoverCandidate
import com.zxsofa.net.sendFileTo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

sealed class Screen {
    data object Catalog : Screen()
    data class Detail(val gameId: Long) : Screen()
    data class Edit(val gameId: Long) : Screen()
    data class CoverSearch(val gameId: Long) : Screen()
    data object Settings : Screen()
    data object WifiImport : Screen()
}

enum class ViewMode(val label: String) {
    GRID("Portadas"), CAROUSEL("Carrusel"), LIST("Lista"), SHELF("Estantería");

    companion object {
        fun from(name: String) = entries.firstOrNull { it.name == name } ?: GRID
    }
}

enum class Filter(val label: String) { ALL("Todos"), FAVORITES("Favoritos"), RECENT("Recientes") }

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val zx = app as ZXSofaApp
    val repo: GameRepository = zx.repository
    val settings = zx.settings
    val games = repo.games

    // Navegación sencilla con pila
    val backStack = mutableStateListOf<Screen>(Screen.Catalog)
    val current: Screen get() = backStack.last()
    fun navigate(s: Screen) { backStack.add(s) }
    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    var query by mutableStateOf("")
    var filter by mutableStateOf(Filter.ALL)
    var busy by mutableStateOf(false)
        private set

    var viewMode by mutableStateOf(ViewMode.from(settings.catalogView))
        private set
    fun changeViewMode(m: ViewMode) {
        viewMode = m
        settings.catalogView = m.name
    }

    /** Identificaciones en curso (para mostrar un indicador en la tarjeta). */
    val identifying = mutableStateListOf<Long>()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    init {
        viewModelScope.launch { repo.refresh() }
    }

    fun visibleGames(all: List<Game>): List<Game> {
        val q = query.trim().lowercase()
        var list = all.filter { g ->
            q.isEmpty() || g.title.lowercase().contains(q) || (g.publisher?.lowercase()?.contains(q) == true) ||
                (g.year?.contains(q) == true)
        }
        list = when (filter) {
            Filter.ALL -> list
            Filter.FAVORITES -> list.filter { it.favorite }
            Filter.RECENT -> list.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }
        }
        return list
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy = true
            var added = 0
            val toIdentify = mutableListOf<Game>()
            for (uri in uris) {
                when (val r = repo.importUri(uri)) {
                    is GameRepository.ImportResult.Added -> { added++; toIdentify += r.game }
                    is GameRepository.ImportResult.Duplicate -> _messages.tryEmit("Ya estaba en la colección: ${r.game.title}")
                    is GameRepository.ImportResult.AddedSide -> _messages.tryEmit("Nueva cinta añadida a ${r.game.title} (${r.game.allFiles.size} en total)")
                    is GameRepository.ImportResult.Backup -> _messages.tryEmit(backupText(r))
                    is GameRepository.ImportResult.MagazineAdded -> _messages.tryEmit("Revista añadida: ${r.magazine.title}")
                    is GameRepository.ImportResult.PokAttached -> _messages.tryEmit("POKEs añadidos a ${r.game.title}")
                    is GameRepository.ImportResult.Error -> _messages.tryEmit(r.message)
                }
            }
            busy = false
            if (added > 0) _messages.tryEmit(if (added == 1) "Juego añadido. Buscando portada…" else "$added juegos añadidos. Buscando portadas…")
            identifyAll(toIdentify)
        }
    }

    /** Llamado por el servidor Wi-Fi al recibir un fichero. */
    fun onReceived(result: GameRepository.ImportResult) {
        when (result) {
            is GameRepository.ImportResult.Added -> {
                _messages.tryEmit("Recibido: ${result.game.title}")
                identifyAll(listOf(result.game))
            }
            is GameRepository.ImportResult.Duplicate -> _messages.tryEmit("Ya estaba en la colección: ${result.game.title}")
            is GameRepository.ImportResult.AddedSide -> _messages.tryEmit("Nueva cinta añadida a ${result.game.title}")
            is GameRepository.ImportResult.Backup -> _messages.tryEmit(backupText(result))
            is GameRepository.ImportResult.MagazineAdded -> _messages.tryEmit("Revista recibida: ${result.magazine.title}")
            is GameRepository.ImportResult.PokAttached -> _messages.tryEmit("POKEs añadidos a ${result.game.title}")
            is GameRepository.ImportResult.Error -> _messages.tryEmit(result.message)
        }
    }

    private fun backupText(r: GameRepository.ImportResult.Backup) =
        "Colección importada: ${r.added} juegos nuevos, ${r.updated} completados" + if (r.skipped > 0) ", ${r.skipped} sin cambios" else ""

    // ------------------------------------------------ copia de seguridad

    /** Progreso del envío de la colección (0..1), o null si no se está enviando. */
    var sendProgress by mutableStateOf<Float?>(null)
        private set

    var backupMagazines by mutableStateOf(false)

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            busy = true
            try {
                val n = getApplication<Application>().contentResolver.openOutputStream(uri)?.use { repo.exportBackup(it, backupMagazines) } ?: 0
                _messages.tryEmit("Copia guardada: $n juegos")
            } catch (e: Exception) {
                _messages.tryEmit("No se pudo guardar la copia: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun sendCollection(address: String) {
        if (sendProgress != null) return
        viewModelScope.launch {
            sendProgress = 0f
            try {
                val file = repo.exportBackupToFile(backupMagazines)
                val reply = sendFileTo(address, file, "coleccion-zxsofa.zip") { p -> sendProgress = p }
                _messages.tryEmit("Colección enviada (${reply.trim()})")
                file.delete()
            } catch (e: Exception) {
                _messages.tryEmit("No se pudo enviar a $address: ${e.message ?: "sin conexión"}")
            } finally {
                sendProgress = null
            }
        }
    }

    // ------------------------------------------------ instrucciones y POKEs

    fun addDocs(game: Game, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch { _messages.tryEmit("Añadidos ${repo.addDocs(game, uris)} documentos") }
    }

    fun removeDoc(game: Game, path: String) = viewModelScope.launch { repo.removeDoc(game, path) }

    fun fetchInstructions(game: Game) = viewModelScope.launch {
        busy = true
        try {
            val n = repo.fetchInstructions(game)
            _messages.tryEmit(
                when {
                    n < 0 -> "No se pudo identificar el juego en ZXDB"
                    n == 0 -> "ZXDB no tiene instrucciones nuevas para este juego"
                    else -> "Descargadas $n instrucciones"
                }
            )
        } catch (e: Exception) {
            _messages.tryEmit("Sin conexión con ZXDB")
        } finally {
            busy = false
        }
    }

    fun setPokFromTipshop(game: Game, path: String) = viewModelScope.launch {
        busy = true
        val g = try { repo.setPokFromTipshop(game, path) } catch (e: Exception) { null }
        busy = false
        _messages.tryEmit(if (g != null) "POKEs listos: ${repo.pokCount(g.pokPath)} trucos" else "No se pudo descargar el .pok")
    }

    fun setPokFromUri(game: Game, uri: Uri) = viewModelScope.launch {
        val g = repo.setPokFromUri(game, uri)
        _messages.tryEmit(if (g != null) "POKEs añadidos: ${repo.pokCount(g.pokPath)} trucos" else "No se pudo leer el fichero")
    }

    fun removePok(game: Game) = viewModelScope.launch { repo.removePok(game) }

    fun recropCover(game: Game, pos: Float) = viewModelScope.launch {
        repo.recropCover(game, pos)
        _messages.tryEmit("Recorte guardado")
    }

    fun fixWideCovers() = viewModelScope.launch {
        busy = true
        val n = try { repo.fixWideCovers() } catch (e: Exception) { 0 }
        busy = false
        _messages.tryEmit(if (n == 0) "No hay carátulas desplegadas que arreglar" else "Arregladas $n carátulas. Puedes ajustar el recorte en cada ficha.")
    }

    fun addTapes(game: Game, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val n = repo.addTapes(game, uris)
            _messages.tryEmit(if (n > 0) "Añadidas $n cintas/caras" else "Ningún fichero válido")
        }
    }

    fun removeTape(game: Game, path: String) = viewModelScope.launch { repo.removeTape(game, path) }

    fun splitTape(game: Game, path: String) = viewModelScope.launch {
        val n = repo.splitTape(game, path)
        if (n != null) {
            _messages.tryEmit("«${n.title}» es ahora un juego aparte")
            identifyAll(listOf(n))
        }
    }

    private fun identifyAll(list: List<Game>) {
        if (list.isEmpty()) return
        viewModelScope.launch {
            // De tres en tres para no saturar la API
            list.chunked(3).forEach { chunk ->
                chunk.map { g ->
                    async {
                        identifying.add(g.id)
                        try {
                            val msg = repo.autoIdentify(g)
                            if (list.size == 1) _messages.tryEmit(msg)
                        } catch (e: Exception) {
                            if (list.size == 1) _messages.tryEmit("Sin conexión para buscar la portada")
                        } finally {
                            identifying.remove(g.id)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    fun identify(game: Game) = identifyAll(listOf(game))

    fun update(game: Game) = viewModelScope.launch { repo.update(game) }
    fun toggleFavorite(game: Game) = viewModelScope.launch { repo.toggleFavorite(game) }
    fun delete(game: Game) = viewModelScope.launch {
        repo.delete(game)
        _messages.tryEmit("Eliminado: ${game.title}")
    }

    fun applyCandidate(game: Game, c: CoverCandidate, withMetadata: Boolean, done: () -> Unit) = viewModelScope.launch {
        busy = true
        try {
            repo.applyCandidate(game, c, withMetadata)
            _messages.tryEmit("Portada actualizada")
        } catch (e: Exception) {
            _messages.tryEmit("No se pudo descargar la portada")
        } finally {
            busy = false
        }
        done()
    }

    fun setCoverFromUri(game: Game, uri: Uri) = viewModelScope.launch {
        repo.setCoverFromUri(game, uri)
        _messages.tryEmit("Portada actualizada")
    }

    fun message(text: String) { _messages.tryEmit(text) }
}
