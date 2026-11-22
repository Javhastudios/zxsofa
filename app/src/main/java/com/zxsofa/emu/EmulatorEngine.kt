package com.zxsofa.emu

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.SurfaceHolder
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Entrada del catálogo de la cinta: P = programa, G = grupo (TZX), T = texto. */
data class TapeEntry(val block: Int, val kind: Char, val text: String)

/** Un truco de un fichero .pok. */
data class PokEntry(val index: Int, val active: Boolean, val askValue: Boolean, val broken: Boolean, val name: String)

/** Qué cargar y con qué opciones del núcleo. */
data class LaunchConfig(
    val gamePath: String,
    val options: Map<String, String>,
    val joystickType: Int,
    /** Partida guardada a restaurar al arrancar (o null). */
    val stateToLoad: File? = null,
)

/**
 * Ejecuta el emulador en su propio hilo: un fotograma de Fuse, lo pinta en la Surface
 * y envía el audio a un AudioTrack. La escritura bloqueante del audio marca el ritmo
 * (44100 Hz / 882 muestras por fotograma = 50 fotogramas por segundo, como el Spectrum).
 */
class EmulatorEngine(
    private val systemDir: File,
    private val listener: Listener,
) : SurfaceHolder.Callback {

    interface Listener {
        fun onStarted()
        fun onMessage(message: String)
        fun onError(message: String)
    }

    @Volatile var paused = false
        private set
    @Volatile var smoothing = false
    @Volatile var effect = ScreenEffect.SHARP
    @Volatile var effectStrength = 0.7f
    @Volatile private var running = false

    private var thread: Thread? = null
    private val commands = ConcurrentLinkedQueue<() -> Unit>()

    private val surfaceLock = Any()
    private var holder: SurfaceHolder? = null
    private var bitmap: Bitmap? = null
    private val paint = Paint()
    private val dst = Rect()
    private var gl: GlRenderer? = null
    private var fpsCount = 0
    private var fpsStart = 0L
    private var surfaceW = 0
    private var surfaceH = 0

    // Entrada: se combinan táctil, mando y pulsaciones automáticas
    @Volatile private var touchMask = 0
    @Volatile private var hwMask = 0
    @Volatile private var pulseMask = 0
    private var pulseFrames = 0

    private var saveOnExit: File? = null

    // ---------------------------------------------------------------- ciclo de vida

    fun start(config: LaunchConfig) {
        if (running) return
        running = true
        thread = Thread({ loop(config) }, "ZXSofa-emu").apply { start() }
    }

    /** Detiene el emulador. Si [saveTo] no es null, guarda la partida antes de salir. */
    fun stop(saveTo: File? = null) {
        saveOnExit = saveTo
        running = false
        thread?.join(3000)
        thread = null
    }

    fun setPaused(p: Boolean) {
        paused = p
        if (p) NativeCore.nativeReleaseAll()
        pushPad()
    }

    /** Ejecuta [cmd] en el hilo del emulador (entre fotogramas). */
    fun post(cmd: () -> Unit) {
        commands.add(cmd)
    }

    fun reset() = post { NativeCore.nativeReset() }

    fun saveState(file: File, done: (Boolean) -> Unit) = post { done(writeState(file)) }

    fun loadState(file: File, done: (Boolean) -> Unit) = post { done(readState(file)) }

    // Las partidas de Fuse no guardan por dónde va la cinta: lo guardamos al lado
    // (cara puesta y bloque) para que los juegos multicarga encuentren la fase siguiente.
    private fun tapeFile(state: File) = File(state.path + ".tape")

    private fun writeState(file: File): Boolean {
        file.parentFile?.mkdirs()
        if (!NativeCore.nativeSaveState(file.absolutePath)) return false
        try {
            tapeFile(file).writeText("${NativeCore.nativeTapeIndex()} ${NativeCore.nativeTapeBlock()}")
        } catch (_: Exception) {
        }
        return true
    }

    private fun readState(file: File): Boolean {
        if (!file.exists() || !NativeCore.nativeLoadState(file.absolutePath)) return false
        try {
            val t = tapeFile(file)
            if (t.exists()) {
                val (side, block) = t.readText().trim().split(" ").map { it.toInt() }
                if (NativeCore.nativeTapeCount() > side && NativeCore.nativeTapeIndex() != side) NativeCore.nativeTapeSelect(side)
                if (block >= 0) NativeCore.nativeTapeSetBlock(block)
            }
        } catch (_: Exception) {
        }
        return true
    }

    /** Cambia a otra cinta/cara sin reiniciar el Spectrum. */
    fun selectTape(index: Int, done: (Boolean) -> Unit) = post { done(NativeCore.nativeTapeSelect(index)) }

    /** Lee los programas grabados en la cinta actual. */
    fun tapeCatalog(done: (List<TapeEntry>, Int) -> Unit) = post {
        val list = NativeCore.nativeTapeCatalog().lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 3) null else p[0].toIntOrNull()?.let { TapeEntry(it, p[1].firstOrNull() ?: 'T', p[2]) }
        }.toList()
        done(list, NativeCore.nativeTapeIndex())
    }

    /** Reinicia y carga la cinta desde el bloque indicado. */
    fun loadFromBlock(block: Int, done: (Boolean) -> Unit) = post { done(NativeCore.nativeTapeLoadBlock(block)) }

    fun pokLoad(path: String, done: (Int) -> Unit = {}) = post { done(NativeCore.nativePokLoad(path)) }

    fun pokList(done: (List<PokEntry>) -> Unit) = post {
        done(NativeCore.nativePokList().lineSequence().mapNotNull { line ->
            val p = line.split('\t', limit = 5)
            if (p.size < 5) null
            else PokEntry(p[0].toIntOrNull() ?: return@mapNotNull null, p[1] == "1", p[2] == "1", p[3] == "1", p[4])
        }.toList())
    }

    fun pokSet(index: Int, active: Boolean, value: Int, done: (Boolean) -> Unit) = post {
        done(NativeCore.nativePokSet(index, active, value))
    }

    fun insert(path: String, autoload: Boolean, done: (Boolean) -> Unit) = post { done(NativeCore.nativeInsert(path, autoload)) }

    /** Guarda lo grabado con SAVE en un fichero .tap. Devuelve false si no hay nada grabado. */
    fun writeTape(file: File, done: (Boolean) -> Unit) = post {
        done(NativeCore.nativeTapePresent() && NativeCore.nativeTapeWrite(file.absolutePath))
    }

    fun setJoystickType(type: Int) = post { NativeCore.nativeSetJoystickType(type) }

    fun setOption(key: String, value: String) = post { NativeCore.nativeSetOption(key, value) }

    // ---------------------------------------------------------------- entrada

    fun setTouchMask(mask: Int) {
        touchMask = mask
        pushPad()
    }

    fun setHardwareMask(mask: Int) {
        hwMask = mask
        pushPad()
    }

    /** Pulsa un botón del joypad durante unos fotogramas (p. ej. SELECT = teclado del núcleo). */
    fun pulse(bit: Int, frames: Int = 4) = post {
        pulseMask = pulseMask or bit
        pulseFrames = frames
        pushPad()
    }

    fun key(code: Int, down: Boolean) = NativeCore.nativeSetKey(code, down)

    private fun pushPad() {
        NativeCore.nativeSetJoypad(if (paused) 0 else touchMask or hwMask or pulseMask)
    }

    // ---------------------------------------------------------------- superficie

    override fun surfaceCreated(h: SurfaceHolder) {
        synchronized(surfaceLock) { holder = h }
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(surfaceLock) { holder = h; surfaceW = width; surfaceH = height }
        post { draw() } // repinta aunque esté en pausa
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        // Solo si es la superficie actual (al rotar puede crearse la nueva antes de destruir la vieja)
        synchronized(surfaceLock) { if (holder === h) holder = null }
        // OpenGL tiene que soltar la ventana antes de que Android la destruya
        gl?.releaseSurfaceBlocking()
    }

    /** Repinta (p. ej. tras cambiar el efecto estando en pausa). */
    fun redraw() = post { draw() }

    // ---------------------------------------------------------------- bucle

    private fun loop(cfg: LaunchConfig) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var track: AudioTrack? = null
        try {
            NativeCore.nativeInit(systemDir.absolutePath, systemDir.absolutePath)
            NativeCore.nativeClearOptions()
            cfg.options.forEach { (k, v) -> NativeCore.nativeSetOption(k, v) }
            NativeCore.nativeSetJoystickType(cfg.joystickType)
            NativeCore.nativeReleaseAll()

            val loadedOk = if (cfg.gamePath.isEmpty()) NativeCore.nativeLoadEmpty() else NativeCore.nativeLoad(cfg.gamePath)
            if (!loadedOk) {
                running = false
                listener.onError("No se pudo cargar el juego")
                return
            }
            if (cfg.stateToLoad != null && cfg.stateToLoad.exists()) {
                // Un fotograma antes para que la máquina esté inicializada
                NativeCore.nativeRunFrame(ShortArray(4096))
                if (!readState(cfg.stateToLoad)) {
                    listener.onMessage("No se pudo restaurar la partida guardada")
                }
            }

            track = createAudioTrack()
            track.play()
            listener.onStarted()

            val audio = ShortArray(8192)
            var trackPaused = false

            while (running) {
                while (true) {
                    val cmd = commands.poll() ?: break
                    cmd()
                }
                if (paused) {
                    if (!trackPaused) {
                        track.pause(); track.flush(); trackPaused = true
                    }
                    Thread.sleep(20)
                    continue
                }
                if (trackPaused) {
                    track.play(); trackPaused = false
                }

                val frames = NativeCore.nativeRunFrame(audio)
                if (GlScreen.measure && ++fpsCount == 250) {
                    val now = System.nanoTime()
                    if (fpsStart != 0L) Log.i(TAG, "Emulación: %.1f fps con %s".format(250e9 / (now - fpsStart), effect.name))
                    fpsStart = now; fpsCount = 0
                }
                if (pulseFrames > 0 && --pulseFrames == 0) {
                    pulseMask = 0
                    pushPad()
                }
                draw()
                NativeCore.nativeTakeMessage()?.let { listener.onMessage(translate(it)) }

                if (frames > 0) {
                    track.write(audio, 0, frames * 2) // bloqueante: marca los 50 fps
                } else {
                    Thread.sleep(20)
                }
            }

            // Comandos pendientes (p. ej. guardar) y guardado automático al salir
            while (true) {
                val cmd = commands.poll() ?: break
                cmd()
            }
            saveOnExit?.let { f -> writeState(f) }
        } catch (t: Throwable) {
            Log.e(TAG, "Error en el emulador", t)
            listener.onError(t.message ?: "Error en el emulador")
        } finally {
            try {
                track?.stop()
            } catch (_: Exception) {
            }
            track?.release()
            try { gl?.quit() } catch (_: Throwable) {}
            gl = null
            NativeCore.nativeReleaseAll()
            NativeCore.nativeUnload()
            running = false
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val rate = 44100
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(min, 882 * 4 * 3) // ~3 fotogramas de margen
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        return builder.build()
    }

    private fun draw() {
        val size = NativeCore.nativeFrameSize()
        if (size == 0) return
        val w = size ushr 16
        val h = size and 0xFFFF

        val r = gl ?: GlRenderer({ synchronized(surfaceLock) { Triple(holder?.surface, surfaceW, surfaceH) } }, surfaceLock)
            .also { gl = it }
        if (r.state != 2) {
            r.effect = effect; r.strength = effectStrength; r.smooth = smoothing
            val buf = r.backBuffer(w, h)
            if (NativeCore.nativeCopyFrame(buf)) r.submit()
            return
        }

        var bmp = bitmap
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            bitmap = bmp
        }
        if (!NativeCore.nativeCopyFrame(bmp)) return

        synchronized(surfaceLock) {
            val hld = holder ?: return
            val canvas = try {
                if (Build.VERSION.SDK_INT >= 26) hld.lockHardwareCanvas() else hld.lockCanvas()
            } catch (e: Exception) {
                null
            } ?: return
            try {
                canvas.drawColor(Color.BLACK)
                val cw = canvas.width
                val ch = canvas.height
                // Escala que conserva la proporción; entera (píxeles nítidos) si apenas se pierde tamaño
                var scale = minOf(cw.toFloat() / w, ch.toFloat() / h)
                val whole = kotlin.math.floor(scale)
                if (!smoothing && whole >= 2f && whole / scale >= 0.85f) scale = whole
                val dw = (w * scale).toInt()
                val dh = (h * scale).toInt()
                val left = (cw - dw) / 2
                val top = (ch - dh) / 2
                dst.set(left, top, left + dw, top + dh)
                paint.isFilterBitmap = smoothing
                canvas.drawBitmap(bmp, null, dst, paint)
            } finally {
                hld.unlockCanvasAndPost(canvas)
            }
        }
    }

    private fun translate(msg: String): String = when {
        msg.startsWith("128K content") -> "Juego de 128K: usando Spectrum 128K"
        msg.startsWith("Looks like 128K") -> "Parece un juego de 128K: cambia el modelo a 128K en la ficha"
        else -> msg
    }

    companion object {
        private const val TAG = "ZXSofa"
    }
}
