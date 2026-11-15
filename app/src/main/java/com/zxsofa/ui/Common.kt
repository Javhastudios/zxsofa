package com.zxsofa.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.io.File

/** Portada local del juego o, si no tiene, una "caja" generada con el título. */
@Composable
fun GameCover(title: String, path: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (path != null && File(path).exists()) {
            AsyncImage(
                model = File(path),
                contentDescription = title,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PlaceholderCover(title)
        }
    }
}

@Composable
private fun PlaceholderCover(title: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF202028), Color(0xFF0A0A0C)))),
    ) {
        Text(
            title,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(12.dp),
        )
        RainbowStripes(Modifier.align(Alignment.BottomEnd).padding(8.dp), stripe = 5.dp)
    }
}

/** Imagen remota con espejos alternativos: si una URL falla prueba la siguiente. */
@Composable
fun RemoteImage(urls: List<String>, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    var index by remember(urls) { mutableIntStateOf(0) }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (index < urls.size) {
            AsyncImage(
                model = urls[index],
                contentDescription = null,
                contentScale = contentScale,
                onError = { index++ },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("Sin imagen", color = Color.Gray, fontSize = 12.sp)
        }
    }
}

/** Modificador que resalta el elemento con foco (mando de TV / D-pad). */
@Composable
fun Modifier.focusHighlight(interaction: MutableInteractionSource, shape: RoundedCornerShape = RoundedCornerShape(12.dp)): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    return this.then(
        if (focused) Modifier.border(BorderStroke(3.dp, ZxColors.Yellow), shape) else Modifier
    )
}

/** Fila "Etiqueta: [valor ▼]" con menú desplegable; funciona con táctil y con D-pad. */
@Composable
fun <T> SelectorRow(
    label: String,
    value: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(optionLabel(value))
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(text = { Text(optionLabel(opt)) }, onClick = { open = false; onSelect(opt) })
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun InfoLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.Start) {
        Text("$label: ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(value)
    }
}

@Composable
fun AppHeader(title: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            RainbowStripes(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(2.dp)), stripe = 4.dp)
        }
        actions()
    }
}
