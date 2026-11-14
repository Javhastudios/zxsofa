package com.zxsofa.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zxsofa.data.ControlConfig
import com.zxsofa.data.JoyMode
import com.zxsofa.data.Machine
import com.zxsofa.emu.ZxKeyboard

@Composable
fun EditScreen(vm: LibraryViewModel, gameId: Long) {
    val all by vm.games.collectAsState()
    val game = all.firstOrNull { it.id == gameId }
    if (game == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }

    var title by remember { mutableStateOf(game.title) }
    var year by remember { mutableStateOf(game.year ?: "") }
    var publisher by remember { mutableStateOf(game.publisher ?: "") }
    var genre by remember { mutableStateOf(game.genre ?: "") }
    var notes by remember { mutableStateOf(game.notes ?: "") }
    var machine by remember { mutableStateOf(game.machine) }
    var controls by remember { mutableStateOf(game.controls) }

    Column(Modifier.fillMaxSize()) {
        AppHeader("Editar") {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).widthIn(max = 720.dp),
        ) {
            SectionTitle("Datos")
            OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(year, { year = it }, label = { Text("Año") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(publisher, { publisher = it }, label = { Text("Compañía") }, singleLine = true, modifier = Modifier.weight(2f))
            }
            OutlinedTextField(genre, { genre = it }, label = { Text("Género") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Notas (teclas, trucos…)") }, minLines = 2, modifier = Modifier.fillMaxWidth())

            SectionTitle("Emulación")
            SelectorRow("Modelo de Spectrum", machine, Machine.entries, { it.label }, { machine = it })
            Text(
                "Casi todos los juegos de 48K funcionan en el 128K. Si alguno se cuelga, prueba con el 48K.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Controles")
            ControlsEditor(controls) { controls = it }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    vm.update(
                        game.copy(
                            title = title.trim().ifBlank { game.title },
                            year = year.trim().ifBlank { null },
                            publisher = publisher.trim().ifBlank { null },
                            genre = genre.trim().ifBlank { null },
                            notes = notes.trim().ifBlank { null },
                            machine = machine,
                            controls = controls,
                        )
                    )
                    vm.back()
                }) { Text("Guardar") }
                OutlinedButton(onClick = { vm.back() }) { Text("Cancelar") }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
fun ControlsEditor(c: ControlConfig, onChange: (ControlConfig) -> Unit) {
    val keys = ZxKeyboard.coreKeyNames
    val label = { k: String -> ZxKeyboard.coreKeyLabel(k) }

    SelectorRow("Joystick", c.mode, JoyMode.entries, { it.label }, { onChange(c.copy(mode = it)) })
    Text(
        when (c.mode) {
            JoyMode.KEMPSTON -> "El más común. Elige otro si el juego no responde al joystick."
            JoyMode.SINCLAIR1 -> "Teclas 6 7 8 9 0 (Interface 2, puerto 1)."
            JoyMode.SINCLAIR2 -> "Teclas 1 2 3 4 5 (Interface 2, puerto 2)."
            JoyMode.CURSOR -> "Teclas de cursor 5 6 7 8 y disparo con 0 (Protek/AGF)."
            JoyMode.KEYS -> "El joystick pulsa las teclas que elijas (p. ej. Q A O P y Espacio)."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (c.mode == JoyMode.KEYS) {
        SelectorRow("Arriba", c.up, keys, label, { onChange(c.copy(up = it)) })
        SelectorRow("Abajo", c.down, keys, label, { onChange(c.copy(down = it)) })
        SelectorRow("Izquierda", c.left, keys, label, { onChange(c.copy(left = it)) })
        SelectorRow("Derecha", c.right, keys, label, { onChange(c.copy(right = it)) })
        SelectorRow("Disparo", c.fire, keys, label, { onChange(c.copy(fire = it)) })
    }
    SelectorRow("Botón extra 1 (L)", c.extra1, keys, { if (it == "<none>") "ENTER" else label(it) }, { onChange(c.copy(extra1 = it)) })
    SelectorRow("Botón extra 2 (R)", c.extra2, keys, { if (it == "<none>") "ESPACIO" else label(it) }, { onChange(c.copy(extra2 = it)) })
}
