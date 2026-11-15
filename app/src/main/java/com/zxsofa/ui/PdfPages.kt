package com.zxsofa.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * Dibuja páginas de un PDF con el PdfRenderer de Android.
 * PdfRenderer no admite varios hilos: todo pasa por un único hilo propio.
 */
class PdfPages(file: File) : Closeable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val executor = Executors.newSingleThreadExecutor()
    private val thread: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val aspects = HashMap<Int, Float>()
    @Volatile private var closed = false

    val count: Int = renderer.pageCount

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Alto / ancho de la página. */
    suspend fun aspect(index: Int): Float = withContext(thread) {
        aspects[index] ?: if (closed) 1.4f else renderer.openPage(index).use { p ->
            (p.height.toFloat() / p.width).also { aspects[index] = it }
        }
    }

    /** Página [index] dibujada a [width] píxeles de ancho (se guarda en caché). */
    suspend fun render(index: Int, width: Int): Bitmap? = withContext(thread) {
        if (closed || index !in 0 until count) return@withContext null
        val w = width.coerceIn(100, 3000)
        val key = "$index@$w"
        cache.get(key)?.let { return@withContext it }
        renderer.openPage(index).use { page ->
            aspects[index] = page.height.toFloat() / page.width
            val h = (w * page.height.toFloat() / page.width).toInt().coerceIn(1, 4200)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            cache.put(key, bmp)
            bmp
        }
    }

    override fun close() {
        closed = true
        executor.execute {
            try { renderer.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
            cache.evictAll()
        }
        executor.shutdown()
    }
}
