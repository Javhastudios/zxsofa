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
    @Volatile private var running = false

    private var thread: Thread? = null
    private val commands = ConcurrentLinkedQueue<() -> Unit>()

    private val surfaceLock = Any()
    private var holder: SurfaceHolder? = null
    private var bitmap: Bitmap? = null
    private val paint = Paint()
    private val dst = Rect()

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

    fun saveState(file: File, done: (Boolean) -> Unit) = post {
        file.parentFile?.mkdirs()
        done(NativeCore.nativeSaveState(file.absolutePath))
    }

    fun loadState(file: File, done: (Boolean) -> Unit) = post {
        done(file.exists() && NativeCore.nativeLoadState(file.absolutePath))
    }

    /** Cambia a otra cinta/cara sin reiniciar el Spectrum. */
    fun selectTape(index: Int, done: (Boolean) -> Unit) = post { done(NativeCore.nativeTapeSelect(index)) }

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
        synchronized(surfaceLock) { holder = h }
        post { draw() } // repinta aunque esté en pausa
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        // Solo si es la superficie actual (al rotar puede crearse la nueva antes de destruir la vieja)
        synchronized(surfaceLock) { if (holder === h) holder = null }
    }

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

            if (!NativeCore.nativeLoad(cfg.gamePath)) {
                running = false
                listener.onError("No se pudo cargar el juego")
                return
            }
            if (cfg.stateToLoad != null && cfg.stateToLoad.exists()) {
                // Un fotograma antes para que la máquina esté inicializada
                NativeCore.nativeRunFrame(ShortArray(4096))
                if (!NativeCore.nativeLoadState(cfg.stateToLoad.absolutePath)) {
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
            saveOnExit?.let { f ->
                f.parentFile?.mkdirs()
                NativeCore.nativeSaveState(f.absolutePath)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error en el emulador", t)
            listener.onError(t.message ?: "Error en el emulador")
        } finally {
            try {
                track?.stop()
            } catch (_: Exception) {
            }
            track?.release()
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
