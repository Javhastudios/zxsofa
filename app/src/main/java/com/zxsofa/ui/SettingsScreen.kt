package com.zxsofa.ui

import androidx.compose.foundation.layout.Column
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

            SectionTitle("Carga y partidas")
            SwitchRow("Carga rápida de cintas", fastLoad) { fastLoad = it; s.fastLoad = it }
            Text("Desactívala para ver (y oír) las franjas de carga como en el original.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow("Guardar al salir para poder «Continuar»", autoSave) { autoSave = it; s.autoSave = it }

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
