package com.zxsofa.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zxsofa.net.CoverCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CoverSearchScreen(vm: LibraryViewModel, gameId: Long) {
    val all by vm.games.collectAsState()
    val game = all.firstOrNull { it.id == gameId }
    if (game == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf(game.title) }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<CoverCandidate>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<CoverCandidate?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.setCoverFromUri(game, uri)
            vm.back()
        }
    }

    fun search() {
        if (query.isBlank()) return
        scope.launch {
            loading = true
            val list = mutableListOf<CoverCandidate>()
            // Índice de ZXDB incluido en la app (sin conexión) y, si responde, la API de ZXInfo
            val seen = mutableSetOf<String>()
            com.zxsofa.net.LocalZxdb.search(context, query).forEach { e ->
                val entry = e.toEntry()
                if (seen.add(entry.id)) list += vm.repo.finder.candidatesFrom(entry)
            }
            try {
                vm.repo.finder.search(query).forEach { if (seen.add(it.id)) list += vm.repo.finder.candidatesFrom(it) }
            } catch (_: Exception) {
            }
            list += withContext(Dispatchers.Default) { vm.repo.finder.libretroCandidates(query, 8) }
            results = list
            searched = true
            loading = false
        }
    }

    LaunchedEffect(gameId) { search() }

    Column(Modifier.fillMaxSize()) {
        AppHeader("Buscar portada") {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Título") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.weight(1f).tvEscape({ query.isEmpty() }),
            )
            Button(onClick = { search() }) { Icon(Icons.Default.Search, "Buscar") }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Fuentes: ZXDB (ZXInfo) y libretro-thumbnails",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { runCatching { pickImage.launch(arrayOf("image/*")) } }) {
                Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("Imagen propia")
            }
        }
        if (loading || vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (searched && results.isEmpty() && !loading) {
            Text("Sin resultados. Prueba con otro título (en inglés suele funcionar mejor).", Modifier.padding(16.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(results) { c -> CandidateCard(c) { chosen = c } }
        }
    }

    chosen?.let { c ->
        if (c.entry != null) {
            AlertDialog(
                onDismissRequest = { chosen = null },
                title = { Text(c.title) },
                text = { Text("¿Copiar también los datos de ZXDB (título, año, compañía, género y pantalla de carga)?") },
                confirmButton = {
                    TextButton(onClick = { chosen = null; vm.applyCandidate(game, c, true) { vm.back() } }) { Text("Portada y datos") }
                },
                dismissButton = {
                    TextButton(onClick = { chosen = null; vm.applyCandidate(game, c, false) { vm.back() } }) { Text("Solo portada") }
                },
            )
        } else {
            LaunchedEffect(c) {
                chosen = null
                vm.applyCandidate(game, c, false) { vm.back() }
            }
        }
    }
}

@Composable
private fun CandidateCard(c: CoverCandidate, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    Card(onClick = onClick, interactionSource = interaction, shape = shape, modifier = Modifier.focusHighlight(interaction, shape)) {
        RemoteImage(c.previewUrls, Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)))
        Column(Modifier.padding(8.dp)) {
            Text(c.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(c.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
