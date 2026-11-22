package com.zxsofa.emu

import android.graphics.Bitmap
import android.util.Log
import android.view.Surface

/**
 * Hilo de pintado propio: el emulador entrega fotogramas y sigue a lo suyo, de modo que
 * un efecto pesado o una GPU lenta solo pueden saltarse fotogramas, nunca ralentizar
 * el juego ni el sonido. Triple búfer: el emulador escribe en uno, otro queda listo y el
 * tercero es el que se está pintando.
 */
class GlRenderer(
    private val surfaceSource: () -> Triple<Surface?, Int, Int>,
    private val surfaceLock: Any,
) {
    /** 0 = arrancando, 1 = OpenGL listo, 2 = no disponible (usar el pintado clásico). */
    @Volatile var state = 0
        private set
    @Volatile var effect = ScreenEffect.SHARP
    @Volatile var strength = 0.7f
    @Volatile var smooth = false
    @Volatile var tvFrame: Bitmap? = null

    private val lock = Object()
    private var back: Bitmap? = null   // del emulador
    private var ready: Bitmap? = null  // último fotograma completo
    private var front: Bitmap? = null  // el que se pinta
    private var hasNew = false
    private var releaseRequest: java.util.concurrent.CountDownLatch? = null
    @Volatile private var running = true
    private val thread = Thread({ loop() }, "ZXSofa-gl").apply { start() }

    /** Búfer donde el emulador debe copiar el próximo fotograma. */
    fun backBuffer(w: Int, h: Int): Bitmap {
        val b = back
        if (b != null && b.width == w && b.height == h) return b
        return Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).also { back = it }
    }

    /** El fotograma de [backBuffer] está completo: pasa a ser el siguiente en pintarse. */
    fun submit() {
        synchronized(lock) {
            val t = ready; ready = back; back = t
            hasNew = true
            lock.notifyAll()
        }
    }

    /** Repinta el último fotograma (cambio de efecto o de tamaño estando en pausa). */
    fun repaint() {
        synchronized(lock) {
            if (ready == null && front != null) { ready = front; front = null }
            hasNew = ready != null
            lock.notifyAll()
        }
    }

    /** Suelta la ventana antes de que Android la destruya (llamar desde el hilo de la interfaz). */
    fun releaseSurfaceBlocking() {
        if (!thread.isAlive) return
        val latch = java.util.concurrent.CountDownLatch(1)
        synchronized(lock) { releaseRequest = latch; lock.notifyAll() }
        latch.await(600, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    fun quit() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        thread.join(1000)
    }

    private fun loop() {
        val gl = GlScreen()
        if (!gl.init()) { state = 2; return }
        state = 1
        try {
            while (running) {
                var frame: Bitmap? = null
                var release: java.util.concurrent.CountDownLatch? = null
                synchronized(lock) {
                    if (!hasNew && releaseRequest == null && running) lock.wait(100)
                    release = releaseRequest; releaseRequest = null
                    if (hasNew) {
                        // El que se pintó antes vuelve a quedar libre como "listo" vacío
                        val t = front; front = ready; ready = t
                        hasNew = false
                        frame = front
                    }
                }
                release?.let { gl.releaseSurface(); it.countDown() }
                val f = frame ?: continue
                synchronized(surfaceLock) {
                    val (surface, w, h) = surfaceSource()
                    if (surface != null) gl.draw(surface, w, h, f, effect, strength, smooth, tvFrame)
                }
            }
        } catch (e: Throwable) {
            Log.w("ZXSofa", "Fallo OpenGL, uso el pintado normal", e)
            state = 2
        } finally {
            releaseRequest?.countDown()
            try { gl.release() } catch (_: Throwable) {}
        }
    }
}
