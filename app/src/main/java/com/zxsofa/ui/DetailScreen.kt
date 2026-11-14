package com.zxsofa.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zxsofa.data.Game
import com.zxsofa.data.JoyMode
import com.zxsofa.emu.ZxKeyboard
import java.text.DateFormat
import java.util.Date

@Composable
fun DetailScreen(vm: LibraryViewModel, gameId: Long, onPlay: (Game, Boolean) -> Unit) {
    val all by vm.games.collectAsState()
    val game = all.firstOrNull { it.id == gameId }
    if (game == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val hasSave = vm.repo.autoStateFile(game.id).exists()
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(game.id) { runCatching { playFocus.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        AppHeader(game.title) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > 640.dp
            val cover = @Composable { m: Modifier ->
                GameCover(game.title, game.coverPath, m.clip(RoundedCornerShape(12.dp)))
            }
            val info = @Composable {
                Column {
                    if (game.subtitle.isNotBlank()) Text(game.subtitle, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    PlayButtons(game, hasSave, playFocus, onPlay)
                    Spacer(Modifier.height(12.dp))
                    ActionButtons(vm, game, onDelete = { confirmDelete = true })

                    SectionTitle("Ficha")
                    InfoLine("Género", game.genre)
                    InfoLine("Modelo", game.machine.label)
                    InfoLine("Controles", controlsSummary(game))
                    InfoLine("Fichero", game.fileName)
                    InfoLine("Cintas", if (game.extraFiles.isNotEmpty()) "${game.allFiles.size} (cámbialas en el menú de pausa)" else null)
                    InfoLine("Partidas", if (game.playCount > 0) "${game.playCount}" else null)
                    InfoLine("Última vez", if (game.lastPlayed > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(game.lastPlayed)) else null)
                    InfoLine("ZXDB", game.zxdbId)
                    if (!game.notes.isNullOrBlank()) {
                        SectionTitle("Notas")
                        Text(game.notes)
                    }
                    if (game.screenPath != null) {
                        SectionTitle("Pantalla de carga")
                        GameCover(game.title, game.screenPath, Modifier.width(320.dp).aspectRatio(4f / 3f).clip(RoundedCornerShape(8.dp)))
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    cover(Modifier.width(300.dp).aspectRatio(0.78f))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { info() }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    cover(Modifier.fillMaxWidth().aspectRatio(1f))
                    Spacer(Modifier.height(16.dp))
                    info()
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar «${game.title}»?") },
            text = { Text("Se borrarán el juego, su portada y las partidas guardadas.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.back(); vm.delete(game) }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun PlayButtons(game: Game, hasSave: Boolean, playFocus: FocusRequester, onPlay: (Game, Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (hasSave) {
            Button(onClick = { onPlay(game, true) }, modifier = Modifier.focusRequester(playFocus)) {
                Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text("Continuar")
            }
            FilledTonalButton(onClick = { onPlay(game, false) }) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Empezar de nuevo")
            }
        } else {
            Button(onClick = { onPlay(game, false) }, modifier = Modifier.focusRequester(playFocus)) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Jugar", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionButtons(vm: LibraryViewModel, game: Game, onDelete: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.navigate(Screen.CoverSearch(game.id)) }) {
            Icon(Icons.Default.ImageSearch, null); Spacer(Modifier.width(6.dp)); Text("Buscar portada")
        }
        OutlinedButton(onClick = { vm.identify(game) }, enabled = game.id !in vm.identifying) {
            Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Identificar")
        }
        OutlinedButton(onClick = { vm.navigate(Screen.Edit(game.id)) }) {
            Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Editar")
        }
        OutlinedButton(onClick = { vm.toggleFavorite(game) }) {
            Icon(if (game.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null)
            Spacer(Modifier.width(6.dp)); Text(if (game.favorite) "Favorito" else "Marcar favorito")
        }
        OutlinedButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text("Eliminar")
        }
    }
}

private fun controlsSummary(g: Game): String {
    val c = g.controls
    return if (c.mode == JoyMode.KEYS) {
        val k = listOf(c.up, c.down, c.left, c.right).joinToString("") { ZxKeyboard.coreKeyLabel(it).take(1) }
        "Teclas $k + ${ZxKeyboard.coreKeyLabel(c.fire)}"
    } else c.mode.label
}
