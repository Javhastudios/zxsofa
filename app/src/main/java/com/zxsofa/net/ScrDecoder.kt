package com.zxsofa.net

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/** Convierte una pantalla del Spectrum (.scr, 6912 bytes: píxeles + atributos) en PNG. */
object ScrDecoder {
    private fun color(index: Int, bright: Boolean): Int {
        val v = if (bright) 0xFF else 0xD7
        val r = if (index and 2 != 0) v else 0
        val g = if (index and 4 != 0) v else 0
        val b = if (index and 1 != 0) v else 0
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun toPng(scr: ByteArray): ByteArray? {
        if (scr.size < 6912) return null
        val border = 16
        val w = 256 + border * 2
        val h = 192 + border * 2
        val pixels = IntArray(w * h) { color(0, false) }
        for (y in 0 until 192) {
            val row = ((y and 0xC0) shl 5) or ((y and 0x07) shl 8) or ((y and 0x38) shl 2)
            for (cx in 0 until 32) {
                val bits = scr[row or cx].toInt() and 0xFF
                val attr = scr[6144 + (y / 8) * 32 + cx].toInt() and 0xFF
                val bright = attr and 0x40 != 0
                val ink = color(attr and 7, bright)
                val paper = color((attr shr 3) and 7, bright)
                for (b in 0 until 8) {
                    val on = bits and (0x80 shr b) != 0
                    pixels[(y + border) * w + border + cx * 8 + b] = if (on) ink else paper
                }
            }
        }
        val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
