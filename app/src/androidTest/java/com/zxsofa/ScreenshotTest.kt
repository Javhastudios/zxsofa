package com.zxsofa

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zxsofa.data.GameRepository
import com.zxsofa.data.Settings
import com.zxsofa.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Capturas de las vistas del catálogo (se vuelcan en el log en base64 para revisarlas). */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    /** Instantánea .sna de 48K con barras de colores y un bucle infinito. */
    private fun testSna(): ByteArray {
        val ram = ByteArray(49152)
        // Mapa de bits: texto de prueba en forma de rayas finas y cuadros
        for (y in 0 until 192) for (xb in 0 until 32) {
            val addr = ((y and 0xC0) shl 5) or ((y and 0x07) shl 8) or ((y and 0x38) shl 2) or xb
            ram[addr] = if ((y / 8 + xb) % 2 == 0) 0xAA.toByte() else if (y % 8 < 4) 0xFF.toByte() else 0x00
        }
        // Atributos: franjas de los 8 colores con BRIGHT alterno
        for (row in 0 until 24) for (col in 0 until 32) {
            val ink = (col / 4) and 7
            val paper = 7 - ink
            val bright = if (row % 6 < 3) 0x40 else 0
            ram[0x1800 + row * 32 + col] = (bright or (paper shl 3) or ink).toByte()
        }
        ram[0x8000 - 0x4000] = 0x18; ram[0x8001 - 0x4000] = 0xFE.toByte()   // JR $
        ram[0xFF00 - 0x4000] = 0x00; ram[0xFF01 - 0x4000] = 0x80.toByte()   // PC en la pila
        val h = ByteArray(27)
        h[23] = 0x00; h[24] = 0xFF.toByte()   // SP = FF00
        h[25] = 1                              // IM 1
        h[26] = 2                              // borde rojo
        return h + ram
    }

    @Test
    fun capturasDeEfectos() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val app = ctx.applicationContext as ZXSofaApp
        val id = kotlinx.coroutines.runBlocking {
            app.repository.importBytes("Barras de prueba.sna", testSna())
            app.repository.refresh()
            app.repository.games.value.first { it.title.startsWith("Barras") }.id
        }
        inst.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
        Thread.sleep(1500)
        val st = Settings(ctx)
        st.touchMode = com.zxsofa.data.TouchMode.NEVER
        for ((name, fx, frame) in listOf(
            Triple("TVFRAME", com.zxsofa.emu.ScreenEffect.TV, true),
            Triple("TV", com.zxsofa.emu.ScreenEffect.TV, false),
            Triple("GREEN", com.zxsofa.emu.ScreenEffect.GREEN, false),
        )) {
            st.screenEffect = fx
            st.tvFrame = frame
            ActivityScenario.launch<com.zxsofa.ui.EmulatorActivity>(
                com.zxsofa.ui.EmulatorActivity.intent(ctx, id, false)
            ).use {
                Thread.sleep(6000)
                val bmp = inst.uiAutomation.takeScreenshot()
                val small = Bitmap.createScaledBitmap(bmp, bmp.width * 3 / 5, bmp.height * 3 / 5, true)
                val out = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, 85, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP).chunked(3000).forEachIndexed { i, c -> Log.i("ZXSofaShot", "$name|$i|$c") }
            }
        }
    }
}
