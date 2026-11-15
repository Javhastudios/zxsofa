package com.zxsofa.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File

/**
 * Carátulas desplegadas (inlay abierto: contraportada | lomo | portada...). Busca la zona
 * con proporción de caja de cassette que tiene más color (la portada suele ser la parte
 * más colorida; el texto de instrucciones es casi blanco y negro) y permite recortarla.
 */
object CoverCrop {
    const val RATIO = 0.64f          // ancho / alto de la portada de una caja de cassette
    const val WIDE = 1.15f           // a partir de aquí consideramos que la imagen está desplegada

    fun size(file: File): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    fun isWide(file: File): Boolean = size(file)?.let { (w, h) -> w.toFloat() / h > WIDE } ?: false

    /** Posición (0..1) del borde izquierdo de la ventana más colorida. */
    fun guess(file: File): Float {
        val (w0, h0) = size(file) ?: return 0f
        val opts = BitmapFactory.Options().apply { inSampleSize = maxOf(1, h0 / 200) }
        val full = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return 0f
        val h = 100
        val w = (full.width * h / full.height.toFloat()).toInt().coerceAtLeast(1)
        val sm = Bitmap.createScaledBitmap(full, w, h, true)
        val win = (h * RATIO).toInt()
        if (w <= win) return 0f
        val hsv = FloatArray(3)
        val col = FloatArray(w)
        for (x in 0 until w) {
            var s = 0f
            for (y in 0 until h) {
                Color.colorToHSV(sm.getPixel(x, y), hsv)
                val white = hsv[1] < 0.15f && hsv[2] > 0.75f
                s += hsv[1] * hsv[2] * 0.8f + if (white) 0f else 0.2f
            }
            col[x] = s / h
        }
        var best = 0
        var bestScore = -1f
        var acc = col.take(win).sum()
        for (x in 0..(w - win)) {
            if (x > 0) acc += col[x + win - 1] - col[x - 1]
            if (acc > bestScore) { bestScore = acc; best = x }
        }
        if (sm != full) sm.recycle()
        full.recycle()
        return best.toFloat() / (w - win)
    }

    /** Recorta [src] en la posición [pos] (0 = izquierda, 1 = derecha) y lo guarda en [dest]. */
    fun crop(src: File, pos: Float, dest: File): Boolean {
        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return false
        val cw = (bmp.height * RATIO).toInt().coerceAtMost(bmp.width)
        val x = ((bmp.width - cw) * pos.coerceIn(0f, 1f)).toInt()
        val out = Bitmap.createBitmap(bmp, x, 0, cw, bmp.height)
        dest.parentFile?.mkdirs()
        dest.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (out != bmp) out.recycle()
        bmp.recycle()
        return true
    }
}
