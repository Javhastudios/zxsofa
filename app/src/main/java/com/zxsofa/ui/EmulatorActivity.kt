package com.zxsofa.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.zxsofa.ZXSofaApp
import com.zxsofa.data.Game
import com.zxsofa.data.JoyMode
import com.zxsofa.data.TouchMode
import com.zxsofa.emu.AndroidKeyMap
import com.zxsofa.emu.CoreOptions
import com.zxsofa.emu.EmulatorEngine
import com.zxsofa.emu.LaunchConfig
import com.zxsofa.emu.Pad
import com.zxsofa.emu.ZxKeyboard
import kotlinx.coroutines.launch
import java.io.File

class EmulatorActivity : ComponentActivity(), EmulatorEngine.Listener {

    companion object {
        private const val EXTRA_ID = "gameId"
        private const val EXTRA_RESUME = "resume"
        fun intent(ctx: Context, gameId: Long, resume: Boolean) =
            Intent(ctx, EmulatorActivity::class.java).putExtra(EXTRA_ID, gameId).putExtra(EXTRA_RESUME, resume)
    }

    private val app get() = application as ZXSofaApp
    private lateinit var engine: EmulatorEngine
    private var game by mutableStateOf<Game?>(null)

    // Estado de la interfaz
    private var menuOpen by mutableStateOf(false)
    private var keyboardOpen by mutableStateOf(false)
    private var usingGamepad by mutableStateOf(false)
    private var touchHidden by mutableStateOf(false)
    private var joyMode by mutableStateOf(JoyMode.KEMPSTON)
    private var started = false

    // Entrada física
    private var keyMask = 0
    private var axisMask = 0
    private var touchDpad = 0
    private var touchButtons = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        engine = EmulatorEngine(File(filesDir, "system").apply { mkdirs() }, this)
        engine.smoothing = app.settings.smoothing

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    menuOpen -> closeMenu()
                    keyboardOpen -> keyboardOpen = false
                    else -> openMenu()
                }
            }
        })

        val id = intent.getLongExtra(EXTRA_ID, -1)
        val resume = intent.getBooleanExtra(EXTRA_RESUME, false)
        lifecycleScope.launch {
            val g = app.repository.get(id)
            if (g == null) {
                finish()
                return@launch
            }
            game = g
            joyMode = g.controls.mode
            val state = if (resume) app.repository.autoStateFile(g.id).takeIf { it.exists() } else null
            engine.start(
                LaunchConfig(
                    gamePath = g.filePath,
                    options = CoreOptions.forGame(g, app.settings),
                    joystickType = g.controls.mode.coreJoystick,
                    stateToLoad = state,
                )
            )
            started = true
            app.repository.markPlayed(g.id)
        }

        setContent {
            ZXSofaTheme { EmulatorScreen() }
        }
    }

    override fun onPause() {
        super.onPause()
        engine.setPaused(true)
    }

    override fun onResume() {
        super.onResume()
        if (!menuOpen) engine.setPaused(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        val g = game
        val save = if (started && g != null && app.settings.autoSave) app.repository.autoStateFile(g.id) else null
        engine.stop(save)
    }

    // ------------------------------------------------------------ Listener

    override fun onStarted() {}

    override fun onMessage(message: String) = runOnUiThread {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onError(message: String) = runOnUiThread {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    // --------------------------------------------------------------- menú

    private fun openMenu() {
        menuOpen = true
        // Soltamos todo: las teclas que se suelten con el menú abierto no llegarían al juego
        keyMask = 0; axisMask = 0; touchDpad = 0; touchButtons = 0
        engine.setHardwareMask(0)
        engine.setTouchMask(0)
        engine.setPaused(true)
    }

    private fun closeMenu() {
        menuOpen = false
        engine.setPaused(false)
    }

    private fun toast(text: String) = runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    private fun saveSlot() {
        val g = game ?: return
        engine.saveState(app.repository.slotStateFile(g.id)) { ok -> toast(if (ok) "Partida guardada" else "No se pudo guardar") }
        closeMenu()
    }

    private fun loadSlot() {
        val g = game ?: return
        engine.loadState(app.repository.slotStateFile(g.id)) { ok -> toast(if (ok) "Partida cargada" else "No hay partida guardada") }
        closeMenu()
    }

    private fun cycleJoystick() {
        val g = game ?: return
        val next = JoyMode.entries[(joyMode.ordinal + 1) % JoyMode.entries.size]
        joyMode = next
        val controls = g.controls.copy(mode = next)
        engine.setJoystickType(next.coreJoystick)
        CoreOptions.joypadMapping(controls).forEach { (k, v) -> engine.setOption(k, v) }
        val updated = g.copy(controls = controls)
        game = updated
        lifecycleScope.launch { app.repository.update(updated) } // se recuerda para la próxima vez
    }

    // ------------------------------------------------------ entrada física

    private fun padBitFor(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> Pad.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Pad.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Pad.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Pad.RIGHT
        KeyEvent.KEYCODE_DPAD_UP_LEFT -> Pad.UP or Pad.LEFT
        KeyEvent.KEYCODE_DPAD_UP_RIGHT -> Pad.UP or Pad.RIGHT
        KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> Pad.DOWN or Pad.LEFT
        KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> Pad.DOWN or Pad.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER -> Pad.A
        KeyEvent.KEYCODE_BUTTON_B -> Pad.B
        KeyEvent.KEYCODE_BUTTON_X -> Pad.X
        KeyEvent.KEYCODE_BUTTON_Y -> Pad.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> Pad.L
        KeyEvent.KEYCODE_BUTTON_R1 -> Pad.R
        else -> 0
    }

    private fun isAlphaKeyboard(event: KeyEvent): Boolean =
        event.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
            !event.isFromSource(InputDevice.SOURCE_GAMEPAD) && !event.isFromSource(InputDevice.SOURCE_DPAD)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Con el menú abierto el D-pad navega por los botones
        if (menuOpen || code == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)

        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.action == KeyEvent.ACTION_MULTIPLE) return super.dispatchKeyEvent(event)

        if (code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_MENU) {
            if (down && event.repeatCount == 0) openMenu()
            return true
        }
        if (code == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (down && event.repeatCount == 0) engine.pulse(Pad.SELECT) // teclado del núcleo (navegable con el mando)
            return true
        }

        // Teclado físico: letras, números, ENTER, espacio, mayúsculas...
        if (isAlphaKeyboard(event)) {
            val rk = AndroidKeyMap.toRetroK(code)
            if (rk != null) {
                if (event.repeatCount == 0) engine.key(rk, down)
                return true
            }
            // las flechas del teclado actúan como joystick
        }

        val bit = padBitFor(code)
        if (bit != 0) {
            if (!isAlphaKeyboard(event)) usingGamepad = true
            keyMask = if (down) keyMask or bit else keyMask and bit.inv()
            engine.setHardwareMask(keyMask or axisMask)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!menuOpen && event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            usingGamepad = true
            val x = event.getAxisValue(MotionEvent.AXIS_X) + event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y) + event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var m = 0
            if (x < -0.5f) m = m or Pad.LEFT
            if (x > 0.5f) m = m or Pad.RIGHT
            if (y < -0.5f) m = m or Pad.UP
            if (y > 0.5f) m = m or Pad.DOWN
            axisMask = m
            engine.setHardwareMask(keyMask or axisMask)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun pushTouch() = engine.setTouchMask(touchDpad or touchButtons)

    // --------------------------------------------------------- interfaz

    @Composable
    private fun EmulatorScreen() {
        val settings = app.settings
        val hasTouch = remember { packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) }
        val showTouch = !touchHidden && when (settings.touchMode) {
            TouchMode.ALWAYS -> true
            TouchMode.NEVER -> false
            TouchMode.AUTO -> hasTouch && !usingGamepad
        }
        val opacity = settings.controlsOpacity
        val haptics = settings.vibration
        val g = game
        val extra1 = g?.controls?.extra1?.let { if (it == "<none>") "ENTER" else ZxKeyboard.coreKeyLabel(it) } ?: "ENTER"
        val extra2 = g?.controls?.extra2?.let { if (it == "<none>") "ESPACIO" else ZxKeyboard.coreKeyLabel(it) } ?: "ESPACIO"

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    // Cualquier toque vuelve a mostrar los controles táctiles (modo automático)
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            if (usingGamepad) usingGamepad = false
                        }
                    }
                },
        ) {
            val portrait = maxHeight > maxWidth

            val screen = @Composable { m: Modifier ->
                AndroidView(
                    factory = { ctx -> SurfaceView(ctx).apply { holder.addCallback(engine) } },
                    modifier = m,
                )
            }
            val onKey: (Int, Boolean) -> Unit = { c, d -> engine.key(c, d) }
            val onButton: (Int, Boolean) -> Unit = { bit, d ->
                touchButtons = if (d) touchButtons or bit else touchButtons and bit.inv()
                pushTouch()
            }
            val onDpad: (Int) -> Unit = { m -> touchDpad = m; pushTouch() }

            if (portrait && showTouch) {
                // Vertical: pantalla arriba, controles abajo
                Column(Modifier.fillMaxSize()) {
                    screen(Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                    TopButtons(Modifier.fillMaxWidth())
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        if (keyboardOpen) {
                            SpectrumKeyboard(onKey, Modifier.align(Alignment.BottomCenter), keyHeight = 52.dp)
                        } else {
                            Translucent(opacity.coerceAtLeast(0.6f)) {
                                Box(Modifier.fillMaxSize().padding(16.dp)) {
                                    DPad(170.dp, haptics, onDpad, Modifier.align(Alignment.CenterStart))
                                    TouchActionButtons(extra1, extra2, haptics, onButton, Modifier.align(Alignment.CenterEnd))
                                }
                            }
                        }
                    }
                }
            } else {
                // Horizontal / TV: pantalla completa con controles superpuestos
                Box(Modifier.fillMaxSize()) {
                    screen(Modifier.fillMaxSize())
                    if (showTouch) {
                        TopButtons(Modifier.align(Alignment.TopCenter).fillMaxWidth())
                        if (!keyboardOpen) {
                            Translucent(opacity) {
                                Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
                                    DPad(160.dp, haptics, onDpad, Modifier.align(Alignment.BottomStart))
                                    TouchActionButtons(extra1, extra2, haptics, onButton, Modifier.align(Alignment.BottomEnd), big = 78.dp)
                                }
                            }
                        }
                    }
                    if (keyboardOpen) {
                        SpectrumKeyboard(onKey, Modifier.align(Alignment.BottomCenter).widthIn(max = 900.dp), keyHeight = 44.dp)
                    }
                }
            }

            if (menuOpen) PauseMenu(hasTouch)
        }
    }

    @Composable
    private fun TopButtons(modifier: Modifier) {
        Row(modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x66000000), contentColor = Color.White)
            IconButton(onClick = { openMenu() }, colors = colors) { Icon(Icons.Default.Menu, "Menú") }
            IconButton(onClick = { keyboardOpen = !keyboardOpen }, colors = colors) {
                Icon(Icons.Default.Keyboard, "Teclado", tint = if (keyboardOpen) ZxColors.Yellow else Color.White)
            }
        }
    }

    @Composable
    private fun PauseMenu(hasTouch: Boolean) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        val g = game
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(g?.title ?: "Pausa", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                RainbowStripes(stripe = 4.dp)
                Spacer(Modifier.height(4.dp))
                Button(onClick = { closeMenu() }, Modifier.fillMaxWidth().focusRequester(first)) { Text("Continuar") }
                FilledTonalButton(onClick = { saveSlot() }, Modifier.fillMaxWidth()) { Text("Guardar partida") }
                FilledTonalButton(onClick = { loadSlot() }, Modifier.fillMaxWidth()) { Text("Cargar partida") }
                FilledTonalButton(onClick = {
                    closeMenu()
                    if (hasTouch && !usingGamepad) keyboardOpen = true else engine.pulse(Pad.SELECT)
                }, Modifier.fillMaxWidth()) { Text("Teclado del Spectrum") }
                FilledTonalButton(onClick = { cycleJoystick() }, Modifier.fillMaxWidth()) { Text("Joystick: ${joyMode.label}") }
                if (hasTouch) {
                    FilledTonalButton(onClick = { touchHidden = !touchHidden }, Modifier.fillMaxWidth()) {
                        Text(if (touchHidden) "Mostrar controles táctiles" else "Ocultar controles táctiles")
                    }
                }
                FilledTonalButton(onClick = { engine.reset(); closeMenu() }, Modifier.fillMaxWidth()) { Text("Reiniciar") }
                FilledTonalButton(onClick = { finish() }, Modifier.fillMaxWidth()) { Text("Salir") }
                Text(
                    "Mando: START abre este menú · SELECT abre el teclado navegable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
