package com.zxsofa.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
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

    // El destacado va rotando solo entre los juegos (con imagen, en orden aleatorio).
    // Si eliges uno con el mando, se queda ese un rato y luego sigue rotando.
    val rotation = remember(games.map { it.id }.toSet()) {
        games.filter { it.coverPath != null || it.screenPath != null }.ifEmpty { games }.shuffled()
    }
    var rotIndex by remember { mutableIntStateOf(0) }
    var lastFocusAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(rotation) {
        while (true) {
            delay(6000)
            if (rotation.size > 1 && System.currentTimeMillis() - lastFocusAt > 10_000) {
                focused = null
                rotIndex = (rotIndex + 1) % rotation.size
            }
        }
    }
    val hero = focused?.let { f -> games.firstOrNull { it.id == f.id } }
        ?: rotation.getOrNull(rotIndex % maxOf(1, rotation.size))?.let { r -> games.firstOrNull { it.id == r.id } }
        ?: games.firstOrNull()

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val heroHeight = if (maxHeight < 420.dp) 0.dp else if (maxHeight < 600.dp) 120.dp else 170.dp
    Column(Modifier.fillMaxSize()) {
        if (hero != null && heroHeight > 0.dp) {
            Crossfade(targetState = hero, animationSpec = tween(700), label = "destacado") { h ->
                CarouselHero(h, heroHeight, onClick = { onOpen(h) })
            }
        }
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
                        CarouselCard(g, onFocus = { focused = g; lastFocusAt = System.currentTimeMillis() }, onClick = { onOpen(g) })
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun CarouselHero(game: Game, height: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(height).clickable(onClick = onClick)) {
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

/**
 * Estantería de madera: cada fila es una balda (res/drawable-nodpi/shelf_tile.jpg, que se
 * repite sin cortes). Las cajas están de pie sobre la balda mostrando la portada, y el título
 * va en una etiqueta en el canto.
 * Proporciones de la imagen: pared 0-70 %, superficie de la balda 71-85 %, canto 86-100 %.
 */
@Composable
fun ShelfView(games: List<Game>, onOpen: (Game) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF3A2412))) {
        val rowHeight = if (maxHeight > 700.dp) 250.dp else 220.dp
        val boxHeight = rowHeight * 0.66f
        val boxWidth = boxHeight * 0.66f              // proporción de una caja de cassette
        val minGap = 18.dp
        val perRow = ((maxWidth - minGap) / (boxWidth + minGap)).toInt().coerceAtLeast(1)
        // Reparto del sobrante para que las filas llenas ocupen todo el ancho
        val gap = ((maxWidth - boxWidth * perRow) / (perRow + 1)).coerceAtLeast(8.dp)
        val rows = remember(games, perRow) { games.chunked(perRow) }
        // Siempre al menos tres baldas, aunque haya pocos juegos, para que parezca una estantería
        val shelves = rows + List((3 - rows.size).coerceAtLeast(0)) { emptyList<Game>() }

        LazyColumn(Modifier.fillMaxSize()) {
            items(shelves.size) { r ->
                Box(Modifier.fillMaxWidth().height(rowHeight)) {
                    Image(
                        painter = androidx.compose.ui.res.painterResource(com.zxsofa.R.drawable.shelf_tile),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = gap),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        shelves[r].forEach { g ->
                            ShelfItem(g, boxWidth, boxHeight, rowHeight, onClick = { onOpen(g) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfItem(game: Game, boxWidth: androidx.compose.ui.unit.Dp, boxHeight: androidx.compose.ui.unit.Dp,
                      rowHeight: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(3.dp)
    Column(Modifier.width(boxWidth).fillMaxHeight()) {
        // Hueco hasta que la caja apoya en la balda (al 80 % de la fila)
        Spacer(Modifier.height(rowHeight * 0.80f - boxHeight))
        Card(
            onClick = onClick,
            interactionSource = interaction,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier
                .width(boxWidth)
                .height(boxHeight)
                .graphicsLayer {
                    // La seleccionada se saca un poco de la balda
                    translationY = if (isFocused) -14f else 0f
                    val sc = if (isFocused) 1.06f else 1f
                    scaleX = sc; scaleY = sc
                }
                .then(if (isFocused) Modifier.border(BorderStroke(3.dp, ZxColors.Yellow), shape) else Modifier),
        ) {
            Box(Modifier.fillMaxSize()) {
                GameCover(game.title, game.coverPath, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                // Brillo de la caja de plástico
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(Color(0x33FFFFFF), Color.Transparent, Color(0x22000000)))
                    )
                )
                // Bisagra de la caja a la izquierda
                Box(Modifier.width(4.dp).fillMaxHeight().background(Color(0x55000000)))
            }
        }
        // Etiqueta en el canto de la balda
        Spacer(Modifier.height(rowHeight * 0.075f))
        Text(
            game.title,
            color = Color(0xFF2B1A0C),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
