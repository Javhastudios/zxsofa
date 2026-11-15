package com.zxsofa.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zxsofa.emu.Pad
import com.zxsofa.emu.ZxKey
import com.zxsofa.emu.ZxKeyboard
import kotlin.math.atan2
import kotlin.math.hypot

/** Cruceta de 8 direcciones. Llama a [onMask] con los bits Pad.UP/DOWN/LEFT/RIGHT. */
@Composable
fun DPad(size: Dp, haptics: Boolean, onMask: (Int) -> Unit, modifier: Modifier = Modifier) {
    var mask by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current

    fun update(newMask: Int) {
        if (newMask != mask) {
            if (haptics && newMask != 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            mask = newMask
            onMask(newMask)
        }
    }

    Box(
        modifier
            .size(size)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val id = down.id
                    fun maskFor(p: Offset): Int {
                        val cx = this.size.width / 2f
                        val cy = this.size.height / 2f
                        val dx = p.x - cx
                        val dy = p.y - cy
                        if (hypot(dx, dy) < this.size.width * 0.12f) return 0 // zona muerta
                        // 8 sectores de 45º
                        val angle = Math.toDegrees(atan2(-dy, dx).toDouble()).let { if (it < 0) it + 360 else it }
                        val sector = (((angle + 22.5) % 360) / 45).toInt()
                        return when (sector) {
                            0 -> Pad.RIGHT
                            1 -> Pad.RIGHT or Pad.UP
                            2 -> Pad.UP
                            3 -> Pad.UP or Pad.LEFT
                            4 -> Pad.LEFT
                            5 -> Pad.LEFT or Pad.DOWN
                            6 -> Pad.DOWN
                            else -> Pad.DOWN or Pad.RIGHT
                        }
                    }
                    update(maskFor(down.position))
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change: PointerInputChange = event.changes.firstOrNull { it.id == id } ?: break
                        if (!change.pressed) break
                        update(maskFor(change.position))
                        change.consume()
                    }
                    update(0)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            val c = center
            drawCircle(Color(0x66000000), r)
            drawCircle(Color.White.copy(alpha = 0.7f), r, style = Stroke(width = 3.dp.toPx()))
            // Flechas
            val a = r * 0.22f
            val d = r * 0.68f
            listOf(Pad.UP to 0f, Pad.RIGHT to 90f, Pad.DOWN to 180f, Pad.LEFT to 270f).forEach { (bit, deg) ->
                val active = mask and bit != 0
                val rad = Math.toRadians(deg.toDouble())
                val tip = Offset(c.x + (d * kotlin.math.sin(rad)).toFloat(), c.y - (d * kotlin.math.cos(rad)).toFloat())
                val baseC = Offset(c.x + ((d - a * 1.4f) * kotlin.math.sin(rad)).toFloat(), c.y - ((d - a * 1.4f) * kotlin.math.cos(rad)).toFloat())
                val perp = Offset((a * kotlin.math.cos(rad)).toFloat(), (a * kotlin.math.sin(rad)).toFloat())
                val path = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(baseC.x + perp.x, baseC.y + perp.y)
                    lineTo(baseC.x - perp.x, baseC.y - perp.y)
                    close()
                }
                drawPath(path, if (active) ZxColors.Yellow else Color.White.copy(alpha = 0.8f))
            }
            if (mask != 0) drawCircle(ZxColors.Yellow.copy(alpha = 0.25f), r)
        }
    }
}

/** Botón táctil que se mantiene pulsado mientras el dedo esté encima. */
@Composable
fun HoldButton(
    label: String,
    size: Dp,
    color: Color,
    haptics: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
    width: Dp = size,
) {
    var pressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .size(width, size)
            .background(if (pressed) color else color.copy(alpha = 0.45f), shape)
            .border(2.dp, Color.White.copy(alpha = 0.7f), shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    if (haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onChange(true)
                    down.consume()
                    val id = down.id
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                    }
                    pressed = false
                    onChange(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

/** Botones de acción: FUEGO (A), ↑ (B) y los dos extra (L/R). */
@Composable
fun TouchActionButtons(
    extra1Label: String,
    extra2Label: String,
    haptics: Boolean,
    onButton: (bit: Int, down: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    big: Dp = 84.dp,
) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HoldButton(extra1Label, 40.dp, ZxColors.Green, haptics, { onButton(Pad.L, it) }, shape = RoundedCornerShape(20.dp), width = 86.dp)
            HoldButton(extra2Label, 40.dp, ZxColors.Green, haptics, { onButton(Pad.R, it) }, shape = RoundedCornerShape(20.dp), width = 86.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            HoldButton("↑", big * 0.72f, ZxColors.Cyan, haptics, { onButton(Pad.B, it) }, modifier = Modifier.padding(bottom = big * 0.5f))
            HoldButton("FUEGO", big, ZxColors.Red, haptics, { onButton(Pad.A, it) })
        }
    }
}

/**
 * Teclado del Spectrum de 40 teclas. CAPS SHIFT y SYMBOL SHIFT se quedan "enganchados"
 * hasta pulsar la siguiente tecla, para poder hacer combinaciones con un solo dedo.
 */
@Composable
fun SpectrumKeyboard(onKey: (code: Int, down: Boolean) -> Unit, modifier: Modifier = Modifier, keyHeight: Dp = 46.dp) {
    var caps by remember { mutableStateOf(false) }
    var symbol by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xEE101012), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ZxKeyboard.rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { key ->
                    val isCaps = key == ZxKeyboard.CAPS
                    val isSym = key == ZxKeyboard.SYMBOL
                    val latched = (isCaps && caps) || (isSym && symbol)
                    KeyCap(
                        key = key,
                        latched = latched,
                        height = keyHeight,
                        modifier = Modifier.weight(if (key.label.length > 1) 1.3f else 1f),
                        onPress = { down ->
                            when {
                                isCaps -> if (down) caps = !caps
                                isSym -> if (down) symbol = !symbol
                                down -> {
                                    if (caps) onKey(ZxKeyboard.CAPS.code, true)
                                    if (symbol) onKey(ZxKeyboard.SYMBOL.code, true)
                                    onKey(key.code, true)
                                }
                                else -> {
                                    onKey(key.code, false)
                                    if (caps) { onKey(ZxKeyboard.CAPS.code, false); caps = false }
                                    if (symbol) { onKey(ZxKeyboard.SYMBOL.code, false); symbol = false }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyCap(key: ZxKey, latched: Boolean, height: Dp, modifier: Modifier, onPress: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .height(height)
            .background(
                when {
                    pressed -> ZxColors.Cyan
                    latched -> ZxColors.Yellow
                    else -> Color(0xFF2A2A30)
                },
                shape,
            )
            .border(1.dp, Color(0xFF55555F), shape)
            .pointerInput(key) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onPress(true)
                    down.consume()
                    val id = down.id
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                    }
                    pressed = false
                    onPress(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val dark = pressed || latched
        val showExtras = height >= 50.dp
        Box(Modifier.fillMaxSize()) {
            if (showExtras && key.symbol.isNotEmpty()) {
                Text(
                    key.symbol,
                    color = if (dark) Color.Black else ZxColors.Red,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 3.dp, top = 1.dp),
                )
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    key.label,
                    color = if (dark) Color.Black else Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (key.label.length > 1) 11.sp else 17.sp,
                )
                if (key.sub.isNotEmpty()) {
                    Text(key.sub, color = if (dark) Color.Black else ZxColors.Red, fontSize = 8.sp)
                }
                if (showExtras && key.keyword.isNotEmpty()) {
                    Text(key.keyword, color = if (dark) Color.Black else Color(0xFFCFCFCF), fontSize = 7.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Contenedor que aplica la opacidad elegida en ajustes. */
@Composable
fun Translucent(opacity: Float, content: @Composable () -> Unit) {
    Box(Modifier.alpha(opacity)) { content() }
}
