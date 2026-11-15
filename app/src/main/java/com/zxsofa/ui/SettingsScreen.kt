package com.zxsofa.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.zxsofa.net.Discovery
import com.zxsofa.net.PeerDevice
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zxsofa.data.BorderSize
import com.zxsofa.data.TouchMode

@Composable
fun SettingsScreen(vm: LibraryViewModel) {
    val s = vm.settings
    var border by remember { mutableStateOf(s.border) }
    var smoothing by remember { mutableStateOf(s.smoothing) }
    var touch by remember { mutableStateOf(s.touchMode) }
    var opacity by remember { mutableFloatStateOf(s.controlsOpacity) }
    var fastLoad by remember { mutableStateOf(s.fastLoad) }
    var vibration by remember { mutableStateOf(s.vibration) }
    var autoSave by remember { mutableStateOf(s.autoSave) }

    Column(Modifier.fillMaxSize()) {
        AppHeader("Ajustes") {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).widthIn(max = 720.dp)) {
            SectionTitle("Imagen")
            SelectorRow("Borde de pantalla", border, BorderSize.entries, { it.label }, { border = it; s.border = it })
            SwitchRow("Suavizado (píxeles menos nítidos)", smoothing) { smoothing = it; s.smoothing = it }

            SectionTitle("Controles táctiles")
            SelectorRow("Mostrar controles táctiles", touch, TouchMode.entries, { it.label }, { touch = it; s.touchMode = it })
            Text("Automático: se muestran en móviles y tablets, y se ocultan al usar un mando.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Opacidad: ${(opacity * 100).toInt()} %", Modifier.padding(top = 8.dp))
            Slider(value = opacity, onValueChange = { opacity = it }, onValueChangeFinished = { s.controlsOpacity = opacity }, valueRange = 0.15f..1f)
            SwitchRow("Vibración al pulsar", vibration) { vibration = it; s.vibration = it }

            SectionTitle("Carátulas")
            Text(
                "Algunas carátulas de ZXDB son el inlay de cassette entero desplegado. Este botón deja solo la portada en las que son claramente un inlay abierto " +
                    "(mucho más anchas que altas); las cajas cuadradas y los packs apaisados no se tocan. El inlay completo se guarda en los documentos de cada juego.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { vm.fixWideCovers() }, modifier = Modifier.padding(top = 6.dp)) { Text("Arreglar carátulas desplegadas") }

            SectionTitle("Carga y partidas")
            SwitchRow("Carga rápida de cintas", fastLoad) { fastLoad = it; s.fastLoad = it }
            Text("Desactívala para ver (y oír) las franjas de carga como en el original.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow("Guardar al salir para poder «Continuar»", autoSave) { autoSave = it; s.autoSave = it }

            SectionTitle("Copia de seguridad y otros dispositivos")
            BackupSection(vm)

            SectionTitle("Acerca de")
            Text(
                "ZX Sofá usa el emulador Fuse (Free Unix Spectrum Emulator, © Philip Kendall y otros) a través de su versión " +
                    "para libretro, con licencia GPL v3. Los datos y portadas vienen de ZXDB (Spectrum Computing) mediante la API " +
                    "de ZXInfo, y de libretro-thumbnails. Las ROM del Spectrum se distribuyen con permiso de Amstrad.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun BackupSection(vm: LibraryViewModel) {
    val context = LocalContext.current
    var sending by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUris(listOf(uri))
    }

    Text(
        "Guarda toda la colección (juegos, carátulas, fichas y partidas) en un único .zip, o pásala a otro dispositivo con ZX Sofá.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val magSize = remember { vm.repo.magazinesSize() }
    if (magSize > 0) {
        SwitchRow("Incluir las revistas (${magSize / (1024 * 1024)} MB)", vm.backupMagazines) { vm.backupMagazines = it }
    }
    Spacer(Modifier.height(8.dp))
    FlowRowButtons {
        OutlinedButton(onClick = {
            runCatching { exportLauncher.launch("coleccion-zxsofa.zip") }
                .onFailure { vm.message("Este dispositivo no permite guardar archivos. Usa «Desde otro dispositivo» y descarga la copia desde el navegador.") }
        }) { Text("Exportar colección") }
        OutlinedButton(onClick = {
            runCatching { importLauncher.launch(arrayOf("application/zip", "*/*")) }
                .onFailure { vm.message("Este dispositivo no tiene selector de archivos. Recibe la colección con «Desde otro dispositivo».") }
        }) { Text("Importar colección") }
        Button(onClick = { sending = !sending }) { Text(if (sending) "Cerrar envío" else "Enviar colección a otro dispositivo") }
    }

    if (sending) {
        var devices by remember { mutableStateOf<List<PeerDevice>>(emptyList()) }
        var manual by remember { mutableStateOf("") }
        DisposableEffect(Unit) {
            val d = Discovery(context)
            d.discover { devices = it }
            onDispose { d.stop() }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "En el otro dispositivo (la tele, por ejemplo) abre ZX Sofá → «Desde otro dispositivo» (icono Wi-Fi). Aparecerá aquí:",
            style = MaterialTheme.typography.bodyMedium,
        )
        val progress = vm.sendProgress
        if (progress != null) {
            Text("Enviando… ${(progress * 100).toInt()} %", Modifier.padding(top = 8.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        if (devices.isEmpty()) {
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Buscando dispositivos en la red…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        devices.forEach { dev ->
            FilledTonalButton(
                onClick = { vm.sendCollection(dev.address) },
                enabled = progress == null,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) { Text("Enviar a ${dev.name}") }
        }
        Text("¿No aparece? Escribe la dirección que muestra la otra pantalla:", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                singleLine = true,
                placeholder = { Text("192.168.1.40:8080") },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { vm.sendCollection(manual) }, enabled = manual.isNotBlank() && progress == null) { Text("Enviar") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
