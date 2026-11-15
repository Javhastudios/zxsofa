package com.zxsofa.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.zxsofa.data.Machine
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zxsofa.data.Game

@Composable
fun CatalogScreen(vm: LibraryViewModel, onAddFile: () -> Unit, onOpen: (Game) -> Unit, onPlay: (Game) -> Unit) {
    val all by vm.games.collectAsState()
    val games = vm.visibleGames(all)

    var showFree by remember { mutableStateOf(false) }
    if (showFree) FreeSpectrumDialog(vm, onDismiss = { showFree = false })

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth >= 600.dp
    Column(Modifier.fillMaxSize()) {
        val actions: @Composable () -> Unit = {
            ViewModeButton(vm.viewMode) { vm.changeViewMode(it) }
            IconButton(onClick = { showFree = true }) { Icon(Icons.Default.Computer, "Encender el Spectrum sin juego") }
            val ctx = LocalContext.current
            IconButton(onClick = { ctx.startActivity(MagazineActivity.intent(ctx)) }) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, "Revistas")
            }
            IconButton(onClick = onAddFile) { Icon(Icons.Default.Add, "Añadir juegos desde archivo") }
            IconButton(onClick = { vm.navigate(Screen.WifiImport) }) { Icon(Icons.Default.Wifi, "Añadir desde otro dispositivo") }
            IconButton(onClick = { vm.navigate(Screen.Settings) }) { Icon(Icons.Default.Settings, "Ajustes") }
        }
        val search: @Composable (Modifier) -> Unit = { m ->
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Buscar en mi colección") },
                modifier = m,
            )
        }
        if (wide && all.isNotEmpty()) {
            // Pantallas anchas (horizontal, tablet, tele): título, buscador e iconos en una sola línea
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("ZX Sofá", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    RainbowStripes(Modifier.padding(top = 4.dp), stripe = 4.dp)
                }
                Spacer(Modifier.width(20.dp))
                search(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                actions()
            }
        } else {
            AppHeader("ZX Sofá") { actions() }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (all.isEmpty()) {
            EmptyLibrary(onAddFile, onWifi = { vm.navigate(Screen.WifiImport) })
            return@Column
        }

        if (!wide) search(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Filter.entries.forEach { f ->
                FilterChip(selected = vm.filter == f, onClick = { vm.filter = f }, label = { Text(f.label) })
            }
            Spacer(Modifier.weight(1f))
            Text("${games.size} juegos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (games.isEmpty()) {
            Text("Ningún juego coincide", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        when (vm.viewMode) {
            ViewMode.GRID -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(games, key = { it.id }) { g ->
                    GameCard(g, identifying = g.id in vm.identifying, onClick = { onOpen(g) })
                }
            }
            ViewMode.CAROUSEL -> CarouselView(games, onOpen, onPlay)
            ViewMode.LIST -> ListView(games, vm.identifying, onOpen, onPlay)
            ViewMode.SHELF -> ShelfView(games, onOpen)
        }
    }
    }
}

@Composable
private fun GameCard(game: Game, identifying: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = onClick,
        interactionSource = interaction,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.focusHighlight(interaction, shape),
    ) {
        Box {
            GameCover(game.title, game.coverPath, Modifier.fillMaxWidth().aspectRatio(0.78f).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)))
            if (identifying) {
                CircularProgressIndicator(Modifier.align(Alignment.TopStart).padding(8.dp).size(22.dp), strokeWidth = 3.dp)
            }
            if (game.favorite) {
                Icon(Icons.Default.Favorite, "Favorito", tint = ZxColors.Red, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(game.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                game.subtitle.ifBlank { game.machine.label },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyLibrary(onAddFile: () -> Unit, onWifi: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RainbowStripes(stripe = 10.dp)
        Spacer(Modifier.height(24.dp))
        Text("Tu colección está vacía", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Añade tus juegos (.tap, .tzx, .z80, .sna, .dsk o .zip). La app buscará la portada y los datos de cada uno en internet.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(520.dp),
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAddFile) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Desde archivo")
            }
            OutlinedButton(onClick = onWifi) {
                Icon(Icons.Default.Wifi, null); Spacer(Modifier.width(8.dp)); Text("Desde otro dispositivo")
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "En la tele, «Desde otro dispositivo» te deja enviar los juegos desde el móvil o el PC por Wi-Fi.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            textAlign = TextAlign.Center,
        )
    }
}

/** Encender el Spectrum sin cargar ningún juego (para programar en BASIC, teclear listados...). */
@Composable
private fun FreeSpectrumDialog(vm: LibraryViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val machines = listOf(Machine.SPEC48, Machine.SPEC128, Machine.PLUS2, Machine.PLUS3)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Encender el Spectrum") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Sin cargar ningún juego: para programar en BASIC o teclear listados de las revistas. " +
                        "Lo que grabes con SAVE lo puedes guardar en tu colección desde el menú de pausa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                machines.forEach { m ->
                    val hasSession = remember(m) { vm.repo.freeStateFile(m, "auto").exists() }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDismiss(); ctx.startActivity(EmulatorActivity.freeIntent(ctx, m, false)) }, modifier = Modifier.weight(1f)) {
                            Text(m.label)
                        }
                        if (hasSession) {
                            OutlinedButton(onClick = { onDismiss(); ctx.startActivity(EmulatorActivity.freeIntent(ctx, m, true)) }) { Text("Continuar") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
