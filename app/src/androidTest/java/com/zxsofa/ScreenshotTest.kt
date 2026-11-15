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
    @Test
    fun capturasDelCatalogo() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val app = ctx.applicationContext as ZXSofaApp
        val repo: GameRepository = app.repository
        runBlocking {
            val names = listOf(
                "Sabre Wulf (1984)(Ultimate Play The Game)", "Knight Lore (1984)(Ultimate Play The Game)",
                "Manic Miner (1983)(Bug-Byte)", "Jet Set Willy (1984)(Software Projects)",
                "Head over Heels (1987)(Ocean)", "Atic Atac (1983)(Ultimate Play The Game)",
                "Jetpac (1983)(Ultimate Play The Game)", "La Abadia del Crimen (1988)(Opera Soft)",
                "Army Moves (1987)(Dinamic)", "Batman - The Caped Crusader (1988)(Ocean)",
            )
            names.forEachIndexed { i, n ->
                val tap = byteArrayOf(19, 0, 0, 0) + "GAME$i     ".take(10).toByteArray() + byteArrayOf(1, 0, 10, 0, 1, 0, 0) +
                    byteArrayOf(3, 0, -1, i.toByte(), (-1 xor i).toByte())
                val r = repo.importBytes("$n.tap", tap)
                if (r is GameRepository.ImportResult.Added) repo.autoIdentify(r.game)
            }
            repo.refresh()
        }
        val shots = listOf("SHELF" to true, "SHELF" to false, "CAROUSEL" to true, "GRID" to true)
        for ((mode, landscape) in shots) {
            Settings(ctx).catalogView = mode
            inst.uiAutomation.setRotation(if (landscape) android.app.UiAutomation.ROTATION_FREEZE_90 else android.app.UiAutomation.ROTATION_FREEZE_0)
            Thread.sleep(1500)
            ActivityScenario.launch(MainActivity::class.java).use {
                Thread.sleep(5000)
                val bmp = inst.uiAutomation.takeScreenshot()
                val small = Bitmap.createScaledBitmap(bmp, bmp.width * 2 / 5, bmp.height * 2 / 5, true)
                val out = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, 75, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                val tag = mode + if (landscape) "H" else "V"
                b64.chunked(3000).forEachIndexed { i, c -> Log.i("ZXSofaShot", "$tag|$i|$c") }
                Log.i("ZXSofaShot", "$tag|END")
            }
        }
    }
}
