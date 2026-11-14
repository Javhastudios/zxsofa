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
            is GameRepository.ImportResult.Error -> _messages.tryEmit(result.message)
        }
    }

    fun addTapes(game: Game, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val n = repo.addTapes(game, uris)
            _messages.tryEmit(if (n > 0) "Añadidas $n cintas/caras" else "Ningún fichero válido")
        }
    }

    fun removeTape(game: Game, path: String) = viewModelScope.launch { repo.removeTape(game, path) }

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
