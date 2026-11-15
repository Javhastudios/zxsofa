package com.zxsofa.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zxsofa.data.Game

/** Botón de la cabecera para elegir cómo se ve el catálogo. */
@Composable
fun ViewModeButton(current: ViewMode, onSelect: (ViewMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.GridView, "Vista del catálogo") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ViewMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label) },
                    leadingIcon = { if (m == current) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { open = false; onSelect(m) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ carrusel

/** Filas tipo Netflix: seguir jugando, favoritos, novedades y por género. */
private fun carouselRows(games: List<Game>): List<Pair<String, List<Game>>> {
    val rows = mutableListOf<Pair<String, List<Game>>>()
    val recent = games.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }.take(20)
    if (recent.isNotEmpty()) rows += "Seguir jugando" to recent
    val favs = games.filter { it.favorite }
    if (favs.isNotEmpty()) rows += "Favoritos" to favs
    if (games.size > 8) rows += "Añadidos recientemente" to games.sortedByDescending { it.addedAt }.take(20)
    games.groupBy { g -> g.genre?.substringBefore(':')?.trim()?.ifBlank { null } ?: "Otros" }
        .toSortedMap(compareBy<String> { it == "Otros" }.thenBy { it })
        .forEach { (genre, list) -> rows += genre to list.sortedBy { it.title.lowercase() } }
    return rows
}

@Composable
fun CarouselView(games: List<Game>, onOpen: (Game) -> Unit, onPlay: (Game) -> Unit) {
    var focused by remember { mutableStateOf<Game?>(null) }
    val rows = remember(games) { carouselRows(games) }
    val hero = focused?.let { f -> games.firstOrNull { it.id == f.id } } ?: games.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        if (hero != null) CarouselHero(hero)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(rows, key = { it.first }) { (title, list) ->
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(list, key = { it.id }) { g ->
                        CarouselCard(g, onFocus = { focused = g }, onClick = { onOpen(g) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CarouselHero(game: Game) {
    Box(Modifier.fillMaxWidth().height(170.dp)) {
        // Fondo: la pantalla de carga (o la portada) difuminada en oscuro
        GameCover(
            game.title,
            game.screenPath ?: game.coverPath,
            Modifier.fillMaxSize().alpha(0.35f),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.background, Color.Transparent))
            )
        )
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            GameCover(game.title, game.coverPath, Modifier.width(110.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(game.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (game.subtitle.isNotBlank()) Text(game.subtitle, style = MaterialTheme.typography.titleSmall)
                game.genre?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                Text(game.machine.label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CarouselCard(game: Game, onFocus: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = onClick,
        interactionSource = interaction,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .width(128.dp)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .graphicsLayer { val s = if (isFocused) 1.07f else 1f; scaleX = s; scaleY = s }
            .then(if (isFocused) Modifier.border(BorderStroke(3.dp, ZxColors.Yellow), shape) else Modifier),
    ) {
        GameCover(game.title, game.coverPath, Modifier.fillMaxWidth().aspectRatio(0.78f))
        Text(
            game.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

// --------------------------------------------------------------------- lista

@Composable
fun ListView(games: List<Game>, identifying: List<Long>, onOpen: (Game) -> Unit, onPlay: (Game) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(games, key = { it.id }) { g ->
            val interaction = remember { MutableInteractionSource() }
            val shape = RoundedCornerShape(10.dp)
            Card(
                onClick = { onOpen(g) },
                interactionSource = interaction,
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().focusHighlight(interaction, shape),
            ) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        GameCover(g.title, g.coverPath, Modifier.width(58.dp).height(74.dp).clip(RoundedCornerShape(6.dp)))
                        if (g.id in identifying) CircularProgressIndicator(Modifier.size(18.dp).align(Alignment.Center), strokeWidth = 2.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(g.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (g.favorite) Icon(Icons.Default.Favorite, "Favorito", tint = ZxColors.Red, modifier = Modifier.padding(start = 6.dp).size(16.dp))
                        }
                        if (g.subtitle.isNotBlank()) Text(g.subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                g.genre,
                                g.machine.label.removePrefix("ZX Spectrum "),
                                if (g.extraFiles.isNotEmpty()) "${g.allFiles.size} cintas" else null,
                                if (g.playCount > 0) "jugado ${g.playCount} ${if (g.playCount == 1) "vez" else "veces"}" else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { onPlay(g) }) { Icon(Icons.Default.PlayArrow, "Jugar", tint = ZxColors.Green) }
                }
            }
        }
    }
}

// --------------------------------------------------------------- estantería

private val spineColors = listOf(ZxColors.Red, ZxColors.Yellow, ZxColors.Green, ZxColors.Cyan, Color(0xFFD800D8), Color(0xFF2F5BFF))
private val Wood = Color(0xFF5A3A22)
private val WoodDark = Color(0xFF3B2414)
private val WoodLight = Color(0xFF8A5A34)

/** Cajas de cassette vistas de canto, colocadas en baldas de madera. */
@Composable
fun ShelfView(games: List<Game>, onOpen: (Game) -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(WoodDark, Color(0xFF2A190E))))) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(290.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(games, key = { it.id }) { g ->
                Column {
                    CassetteSpine(g, onClick = { onOpen(g) }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                    // La balda: al no haber hueco entre columnas, forma una tabla continua
                    Box(Modifier.fillMaxWidth().height(4.dp).background(WoodLight))
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Wood))
                }
            }
        }
    }
}

@Composable
private fun CassetteSpine(game: Game, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val band = spineColors[(game.title.hashCode() and 0x7fffffff) % spineColors.size]
    val shape = RoundedCornerShape(4.dp)
    Card(
        onClick = onClick,
        interactionSource = interaction,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C20)),
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .graphicsLayer { translationY = if (isFocused) -6f else 0f }
            .then(if (isFocused) Modifier.border(BorderStroke(3.dp, ZxColors.Yellow), shape) else Modifier),
    ) {
        Row(Modifier.fillMaxSize().padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(8.dp).fillMaxHeight().background(band, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(4.dp))
            GameCover(game.title, game.coverPath, Modifier.size(46.dp).clip(RoundedCornerShape(2.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(6.dp))
            // Etiqueta de papel del canto
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xFFF1E9D2), RoundedCornerShape(2.dp))
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    game.title.uppercase(),
                    color = Color(0xFF151515),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = listOfNotNull(game.publisher, game.year).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(sub, color = Color(0xFF55504A), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(ZxColors.Red, ZxColors.Yellow, ZxColors.Green, ZxColors.Cyan).forEach {
                    Box(Modifier.width(14.dp).height(5.dp).background(it))
                }
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}
