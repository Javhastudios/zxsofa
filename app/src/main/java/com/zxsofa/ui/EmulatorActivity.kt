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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import com.zxsofa.data.Sides
import com.zxsofa.data.TouchMode
import com.zxsofa.emu.AndroidKeyMap
import com.zxsofa.emu.CoreOptions
import com.zxsofa.emu.EmulatorEngine
import com.zxsofa.emu.LaunchConfig
import com.zxsofa.emu.Pad
import com.zxsofa.emu.PokEntry
import com.zxsofa.emu.NativeCore
import com.zxsofa.emu.TapeEntry
import com.zxsofa.emu.ZxKeyboard
import kotlinx.coroutines.launch
import java.io.File

class EmulatorActivity : ComponentActivity(), EmulatorEngine.Listener {

    companion object {
        private const val EXTRA_ID = "gameId"
        private const val EXTRA_RESUME = "resume"
        private const val EXTRA_FREE = "freeMachine"
        fun intent(ctx: Context, gameId: Long, resume: Boolean) =
            Intent(ctx, EmulatorActivity::class.java).putExtra(EXTRA_ID, gameId).putExtra(EXTRA_RESUME, resume)

        /** Spectrum encendido sin juego (BASIC, programar...). */
        fun freeIntent(ctx: Context, machine: com.zxsofa.data.Machine, resume: Boolean) =
            Intent(ctx, EmulatorActivity::class.java).putExtra(EXTRA_FREE, machine.name).putExtra(EXTRA_RESUME, resume)
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
    private var screenEffect by mutableStateOf(com.zxsofa.emu.ScreenEffect.SHARP)
    private var tapeIndex by mutableStateOf(0)
    /** Modelo cuando se enciende el Spectrum sin juego (null = jugando a un juego). */
    private var freeMachine by mutableStateOf<com.zxsofa.data.Machine?>(null)
    private var askProgramName by mutableStateOf(false)
    private var showLoadList by mutableStateOf(false)
    private var pickedGame by mutableStateOf<Game?>(null)
    /** Modo ordenador: juego cuya cinta está metida (para poder cambiar de cara). */
    private var insertedGame by mutableStateOf<Game?>(null)
    private var insertedTape by mutableStateOf(0)
    private var catalog by mutableStateOf<List<TapeEntry>>(emptyList())
    private var showCatalog by mutableStateOf(false)
    private var showDocs by mutableStateOf(false)
    private var showPokes by mutableStateOf(false)
    private var pokes by mutableStateOf<List<PokEntry>>(emptyList())
    private var askPoke by mutableStateOf<PokEntry?>(null)
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
        engine.effect = app.settings.screenEffect
        screenEffect = engine.effect
        engine.effectStrength = app.settings.effectStrength
        engine.tvFrame = if (app.settings.tvFrame) tvFrameBitmap() else null

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    menuOpen && askPoke != null -> askPoke = null
                    menuOpen && pickedGame != null -> pickedGame = null
                    menuOpen && (showCatalog || showDocs || showPokes || showLoadList || showKeys) -> { showCatalog = false; showDocs = false; showPokes = false; showLoadList = false; showKeys = false }
                    menuOpen -> closeMenu()
                    keyboardOpen -> keyboardOpen = false
                    else -> openMenu()
                }
            }
        })

        val id = intent.getLongExtra(EXTRA_ID, -1)
        val resume = intent.getBooleanExtra(EXTRA_RESUME, false)
        val free = intent.getStringExtra(EXTRA_FREE)?.let { n -> com.zxsofa.data.Machine.entries.firstOrNull { it.name == n } }
        if (free != null) {
            freeMachine = free
            keyboardOpen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
            val state = if (resume) app.repository.freeStateFile(free, "auto").takeIf { it.exists() } else null
            engine.start(LaunchConfig(gamePath = "", options = CoreOptions.forFree(free, app.settings), joystickType = 1, stateToLoad = state))
            started = true
        } else lifecycleScope.launch {
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
                    gamePath = app.repository.launchPath(g),
                    // Al continuar una partida no hace falta autocargar la cinta (se restaura todo)
                    options = CoreOptions.forGame(g, app.settings).let { o ->
                        if (state != null) o + ("fuse_auto_load" to "disabled") else o
                    },
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
        val fm = freeMachine
        val save = when {
            !started || !app.settings.autoSave -> null
            fm != null -> app.repository.freeStateFile(fm, "auto")
            g != null -> app.repository.autoStateFile(g.id)
            else -> null
        }
        engine.stop(save)
    }

    // ------------------------------------------------------------ Listener

    override fun onStarted() {
        // Hilo del emulador, con el juego ya cargado: preparamos sus POKEs
        game?.pokPath?.let { if (java.io.File(it).exists()) NativeCore.nativePokLoad(it) }
    }

    private fun refreshPokes() = engine.pokList { list -> runOnUiThread { pokes = list } }

    private fun togglePoke(p: PokEntry, value: Int = 0) {
        engine.pokSet(p.index, !p.active, value) { ok ->
            if (!ok) toast("Ese POKE no se puede aplicar")
            refreshPokes()
        }
    }

    private fun findPokes() {
        val g = game ?: return
        toast("Buscando POKEs…")
        lifecycleScope.launch {
            val updated = try { app.repository.autoPokes(g) } catch (e: Exception) { null }
            if (updated?.pokPath != null) {
                game = updated
                engine.pokLoad(updated.pokPath) { n -> toast("$n trucos encontrados"); refreshPokes() }
                showPokes = true
            } else {
                toast("No he encontrado POKEs para este juego (prueba desde la ficha: «Buscar POKEs»)")
            }
        }
    }

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
        engine.tapeCatalog { list, side -> runOnUiThread { catalog = list; tapeIndex = side } }
    }

    private fun closeMenu() {
        menuOpen = false
        showCatalog = false
        showDocs = false
        showPokes = false
        askPoke = null
        askProgramName = false
        showLoadList = false
        pickedGame = null
        showKeys = false
        engine.setPaused(false)
    }

    private fun toast(text: String) = runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    private fun saveSlot() {
        freeMachine?.let { m ->
            engine.saveState(app.repository.freeStateFile(m, "slot1")) { ok -> toast(if (ok) "Estado guardado" else "No se pudo guardar") }
            closeMenu()
            return
        }
        val g = game ?: return
        engine.saveState(app.repository.slotStateFile(g.id)) { ok -> toast(if (ok) "Partida guardada" else "No se pudo guardar") }
        closeMenu()
    }

    private fun loadSlot() {
        freeMachine?.let { m ->
            engine.loadState(app.repository.freeStateFile(m, "slot1")) { ok -> toast(if (ok) "Estado cargado" else "No hay nada guardado") }
            closeMenu()
            return
        }
        val g = game ?: return
        engine.loadState(app.repository.slotStateFile(g.id)) { ok -> toast(if (ok) "Partida cargada" else "No hay partida guardada") }
        closeMenu()
    }

    /** Modo ordenador: mete la cinta de un juego de la colección. */
    private fun insertGame(g: Game, autoload: Boolean) {
        engine.insert(g.filePath, autoload) { ok ->
            if (ok) runOnUiThread {
                insertedGame = g
                insertedTape = 0
                applyControls(g.controls)
            }
            toast(
                when {
                    !ok -> "No se pudo meter la cinta"
                    autoload -> "Cargando ${g.title}…"
                    else -> "Cinta metida: escribe LOAD \"\" (J y dos comillas) y pulsa ENTER"
                }
            )
        }
        closeMenu()
    }

    /** Modo ordenador: cambia a otra cinta/cara del juego metido, sin reiniciar. */
    private fun insertTape(index: Int) {
        val g = insertedGame ?: return
        val path = g.allFiles.getOrNull(index) ?: return
        val label = Sides.label(java.io.File(path).name, index)
        engine.insert(path, false) { ok ->
            if (ok) runOnUiThread { insertedTape = index }
            toast(if (ok) "$label insertada" else "No se pudo cambiar de cinta")
        }
        closeMenu()
    }

    /** Guarda lo grabado con SAVE como un juego nuevo de la colección. */
    private fun saveProgram(name: String) {
        val tmp = java.io.File(cacheDir, "grabacion.tap")
        engine.writeTape(tmp) { ok ->
            if (!ok) {
                toast("No hay nada grabado. Escribe SAVE \"nombre\" en BASIC primero.")
                return@writeTape
            }
            lifecycleScope.launch {
                when (val r = app.repository.addRecordedTape(name, tmp)) {
                    is com.zxsofa.data.GameRepository.ImportResult.Added -> toast("Guardado en la colección: ${r.game.title}")
                    is com.zxsofa.data.GameRepository.ImportResult.Duplicate -> toast("Ese programa ya estaba en la colección")
                    else -> toast("No se pudo guardar")
                }
                tmp.delete()
            }
        }
        askProgramName = false
        closeMenu()
    }

    private fun selectTape(index: Int) {
        val g = game ?: return
        val label = Sides.label(java.io.File(g.allFiles[index]).name, index)
        engine.selectTape(index) { ok ->
            runOnUiThread {
                if (ok) {
                    tapeIndex = index
                    toast("$label insertada")
                } else {
                    toast("No se pudo cambiar de cinta")
                }
            }
        }
        closeMenu()
    }

    private fun loadProgram(entry: TapeEntry) {
        engine.loadFromBlock(entry.block) { ok ->
            toast(if (ok) "Cargando ${entry.text}…" else "No se pudo cargar desde ese punto")
        }
        closeMenu()
    }

    /** Controles en uso: los del juego abierto, los del juego metido en el modo ordenador o los sueltos. */
    private var freeControls by mutableStateOf(com.zxsofa.data.ControlConfig())
    private var showKeys by mutableStateOf(false)

    private fun currentControls(): com.zxsofa.data.ControlConfig =
        game?.controls ?: insertedGame?.controls ?: freeControls

    /** Aplica unos controles al núcleo y los recuerda (en el juego, si hay uno). */
    private fun applyControls(controls: com.zxsofa.data.ControlConfig) {
        joyMode = controls.mode
        engine.setJoystickType(controls.mode.coreJoystick)
        CoreOptions.joypadMapping(controls).forEach { (k, v) -> engine.setOption(k, v) }
        val g = game
        val ins = insertedGame
        when {
            g != null -> { val u = g.copy(controls = controls); game = u; lifecycleScope.launch { app.repository.update(u) } }
            ins != null -> { val u = ins.copy(controls = controls); insertedGame = u; lifecycleScope.launch { app.repository.update(u) } }
            else -> freeControls = controls
        }
    }

    private fun tvFrameBitmap(): android.graphics.Bitmap? = try {
        android.graphics.BitmapFactory.decodeResource(resources, com.zxsofa.R.drawable.tv_frame)
    } catch (e: Throwable) { null }

    private fun cycleEffect() {
        val all = com.zxsofa.emu.ScreenEffect.entries
        val next = all[(screenEffect.ordinal + 1) % all.size]
        screenEffect = next
        engine.effect = next
        app.settings.screenEffect = next
        engine.redraw()
    }

    private fun cycleJoystick() {
        val next = JoyMode.entries[(joyMode.ordinal + 1) % JoyMode.entries.size]
        applyControls(currentControls().copy(mode = next))
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
        val ctl = currentControls()
        val extra1 = if (ctl.extra1 == "<none>") "ENTER" else ZxKeyboard.coreKeyLabel(ctl.extra1)
        val extra2 = if (ctl.extra2 == "<none>") "ESPACIO" else ZxKeyboard.coreKeyLabel(ctl.extra2)

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
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                        if (keyboardOpen) {
                            // El teclado aprovecha todo el alto disponible (teclas grandes para programar)
                            val kh = ((maxHeight - 40.dp) / 4 - 5.dp).coerceIn(44.dp, 100.dp)
                            SpectrumKeyboard(onKey, Modifier.align(Alignment.BottomCenter), keyHeight = kh)
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
    private fun TapeCatalogList(first: FocusRequester) {
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        Text(
            "Elige desde dónde cargar: el Spectrum se reinicia y carga ese programa.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { showCatalog = false }, Modifier.fillMaxWidth().focusRequester(first)) { Text("Volver") }
        catalog.forEach { e ->
            when (e.kind) {
                'P' -> FilledTonalButton(onClick = { loadProgram(e) }, Modifier.fillMaxWidth()) { Text(e.text) }
                'G' -> FilledTonalButton(onClick = { loadProgram(e) }, Modifier.fillMaxWidth()) {
                    Text("▸ ${e.text}", fontWeight = FontWeight.Bold)
                }
                else -> Text(e.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun PauseMenu(hasTouch: Boolean) {
        val first = remember { FocusRequester() }
        LaunchedEffect(showCatalog, showDocs, showPokes, showLoadList, pickedGame, showKeys) { runCatching { first.requestFocus() } }
        val g = game
        // El menú no va dentro de un Surface: fijamos el color del texto para que se lea sobre el fondo oscuro
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .padding(vertical = 12.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    when {
                        showCatalog -> "Programas de la cinta"
                        showDocs -> "Instrucciones"
                        showPokes -> "POKEs"
                        showLoadList -> "Cargar juego"
                        showKeys -> "Teclas del joystick"
                        else -> freeMachine?.label ?: g?.title ?: "Pausa"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                RainbowStripes(stripe = 4.dp)
                Spacer(Modifier.height(4.dp))
                if (showCatalog) {
                    TapeCatalogList(first)
                    return@Column
                }
                if (showDocs && g != null) {
                    Button(onClick = { showDocs = false }, Modifier.fillMaxWidth().focusRequester(first)) { Text("Volver") }
                    g.docs.forEach { path ->
                        FilledTonalButton(onClick = {
                            startActivity(MagazineActivity.docIntent(this@EmulatorActivity, path, java.io.File(path).name))
                        }, Modifier.fillMaxWidth()) { Text(java.io.File(path).name, maxLines = 1) }
                    }
                    return@Column
                }
                if (showKeys) {
                    Button(onClick = { showKeys = false }, Modifier.fillMaxWidth().focusRequester(first)) { Text("Listo") }
                    val c = currentControls()
                    val names = ZxKeyboard.coreKeyNames
                    val lbl = { k: String -> ZxKeyboard.coreKeyLabel(k) }
                    SelectorRow("Arriba", c.up, names, lbl, { applyControls(currentControls().copy(up = it)) })
                    SelectorRow("Abajo", c.down, names, lbl, { applyControls(currentControls().copy(down = it)) })
                    SelectorRow("Izquierda", c.left, names, lbl, { applyControls(currentControls().copy(left = it)) })
                    SelectorRow("Derecha", c.right, names, lbl, { applyControls(currentControls().copy(right = it)) })
                    SelectorRow("Disparo", c.fire, names, lbl, { applyControls(currentControls().copy(fire = it)) })
                    SelectorRow("Botón extra 1", c.extra1, names, { if (it == "<none>") "ENTER" else lbl(it) }, { applyControls(currentControls().copy(extra1 = it)) })
                    SelectorRow("Botón extra 2", c.extra2, names, { if (it == "<none>") "ESPACIO" else lbl(it) }, { applyControls(currentControls().copy(extra2 = it)) })
                    Text(
                        "Se aplican al momento" + (if (game != null || insertedGame != null) " y se guardan para este juego." else "."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                if (showLoadList) {
                    val picked = pickedGame
                    if (picked != null) {
                        Text(picked.title, fontWeight = FontWeight.Bold)
                        Button(onClick = { insertGame(picked, true) }, Modifier.fillMaxWidth().focusRequester(first)) { Text("Cargar ya") }
                        FilledTonalButton(onClick = { insertGame(picked, false) }, Modifier.fillMaxWidth()) {
                            Text("Solo meter la cinta (escribo LOAD \"\")")
                        }
                        TextButton(onClick = { pickedGame = null }, Modifier.fillMaxWidth()) { Text("Elegir otro") }
                        return@Column
                    }
                    Button(onClick = { showLoadList = false }, Modifier.fillMaxWidth().focusRequester(first)) { Text("Volver") }
                    val list = app.repository.games.value
                    if (list.isEmpty()) Text("Tu colección está vacía.")
                    list.forEach { gm ->
                        FilledTonalButton(onClick = { pickedGame = gm }, Modifier.fillMaxWidth()) {
                            Text(gm.title + (gm.year?.let { " ($it)" } ?: ""), maxLines = 1)
                        }
                    }
                    return@Column
                }
                if (showPokes) {
                    Button(onClick = { showPokes = false }, Modifier.fillMaxWidth().focusRequester(first)) { Text("Volver") }
                    Text("Pulsa un truco para activarlo o desactivarlo.", style = MaterialTheme.typography.bodySmall)
                    pokes.forEach { p ->
                        FilledTonalButton(
                            onClick = { if (!p.active && p.askValue) askPoke = p else togglePoke(p) },
                            enabled = !p.broken,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text((if (p.active) "✅ " else "⬜ ") + p.name, maxLines = 2, modifier = Modifier.weight(1f))
                        }
                    }
                    askPoke?.let { p ->
                        var value by remember(p.index) { mutableStateOf("") }
                        Text("«${p.name}» necesita un número (0-255), por ejemplo vidas:", Modifier.padding(top = 8.dp))
                        OutlinedTextField(
                            value = value,
                            onValueChange = { v -> value = v.filter { it.isDigit() }.take(3) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().tvEscape(),
                        )
                        Button(onClick = {
                            togglePoke(p, (value.toIntOrNull() ?: 0).coerceIn(0, 255))
                            askPoke = null
                        }, Modifier.fillMaxWidth()) { Text("Aplicar") }
                    }
                    return@Column
                }
                Button(onClick = { closeMenu() }, Modifier.fillMaxWidth().focusRequester(first)) { Text("Continuar") }
                if (catalog.count { it.kind == 'P' } >= 2) {
                    FilledTonalButton(onClick = { showCatalog = true }, Modifier.fillMaxWidth()) {
                        Text("Elegir programa de la cinta (${catalog.count { it.kind == 'P' }})")
                    }
                }
                if (g != null && g.extraFiles.isNotEmpty()) {
                    Text("Cinta en el cassette:", style = MaterialTheme.typography.labelLarge)
                    g.allFiles.forEachIndexed { i, path ->
                        val label = Sides.label(java.io.File(path).name, i)
                        if (i == tapeIndex) {
                            Button(onClick = { closeMenu() }, Modifier.fillMaxWidth()) { Text("▶ $label") }
                        } else {
                            FilledTonalButton(onClick = { selectTape(i) }, Modifier.fillMaxWidth()) { Text("Poner $label") }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (g != null && g.docs.isNotEmpty()) {
                    FilledTonalButton(onClick = {
                        if (g.docs.size == 1) startActivity(MagazineActivity.docIntent(this@EmulatorActivity, g.docs[0], java.io.File(g.docs[0]).name))
                        else showDocs = true
                    }, Modifier.fillMaxWidth()) { Text("Instrucciones") }
                }
                if (freeMachine != null) {
                    val ins = insertedGame
                    if (ins != null && ins.extraFiles.isNotEmpty()) {
                        Text("Cinta en el cassette (${ins.title}):", style = MaterialTheme.typography.labelLarge)
                        ins.allFiles.forEachIndexed { i, path ->
                            val label = Sides.label(java.io.File(path).name, i)
                            if (i == insertedTape) {
                                Button(onClick = { closeMenu() }, Modifier.fillMaxWidth()) { Text("▶ $label") }
                            } else {
                                FilledTonalButton(onClick = { insertTape(i) }, Modifier.fillMaxWidth()) { Text("Poner $label") }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    FilledTonalButton(onClick = { showLoadList = true }, Modifier.fillMaxWidth()) { Text("Cargar juego de la colección") }
                    if (askProgramName) {
                        var name by remember { mutableStateOf("") }
                        Text("Nombre del programa:", Modifier.padding(top = 4.dp))
                        OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth().tvEscape())
                        Button(onClick = { saveProgram(name) }, Modifier.fillMaxWidth()) { Text("Guardar en la colección") }
                    } else {
                        FilledTonalButton(onClick = { askProgramName = true }, Modifier.fillMaxWidth()) { Text("Guardar programa en la colección") }
                    }
                    Text(
                        "Graba con SAVE \"nombre\" en BASIC y luego guárdalo aquí: aparecerá en tu colección como una cinta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FilledTonalButton(onClick = {
                        if (g?.pokPath != null) { refreshPokes(); showPokes = true } else findPokes()
                    }, Modifier.fillMaxWidth()) { Text(if (g?.pokPath != null) "POKEs (trucos)" else "Buscar POKEs") }
                }
                FilledTonalButton(onClick = { startActivity(MagazineActivity.intent(this@EmulatorActivity)) }, Modifier.fillMaxWidth()) {
                    Text("Revistas")
                }
                FilledTonalButton(onClick = { saveSlot() }, Modifier.fillMaxWidth()) { Text("Guardar partida") }
                FilledTonalButton(onClick = { loadSlot() }, Modifier.fillMaxWidth()) { Text("Cargar partida") }
                FilledTonalButton(onClick = {
                    closeMenu()
                    if (hasTouch && !usingGamepad) keyboardOpen = true else engine.pulse(Pad.SELECT)
                }, Modifier.fillMaxWidth()) { Text("Teclado del Spectrum") }
                FilledTonalButton(onClick = { cycleJoystick() }, Modifier.fillMaxWidth()) { Text("Joystick: ${joyMode.label}") }
                FilledTonalButton(onClick = { cycleEffect() }, Modifier.fillMaxWidth().testTag("effect")) { Text("Pantalla: ${screenEffect.label}") }
                if (joyMode == JoyMode.KEYS) {
                    FilledTonalButton(onClick = { showKeys = true }, Modifier.fillMaxWidth()) { Text("Elegir las teclas") }
                }
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
}
