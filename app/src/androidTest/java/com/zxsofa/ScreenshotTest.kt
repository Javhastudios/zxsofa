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
    fun capturasDeEfectos() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        inst.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
        Thread.sleep(1500)
        for (fx in com.zxsofa.emu.ScreenEffect.entries) {
            Settings(ctx).screenEffect = fx
            Settings(ctx).touchMode = com.zxsofa.data.TouchMode.NEVER
            ActivityScenario.launch<com.zxsofa.ui.EmulatorActivity>(
                com.zxsofa.ui.EmulatorActivity.freeIntent(ctx, com.zxsofa.data.Machine.SPEC128, false)
            ).use {
                Thread.sleep(7000)
                val bmp = inst.uiAutomation.takeScreenshot()
                val small = Bitmap.createScaledBitmap(bmp, bmp.width * 3 / 5, bmp.height * 3 / 5, true)
                val out = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, 85, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP).chunked(3000).forEachIndexed { i, c -> Log.i("ZXSofaShot", "${fx.name}|$i|$c") }
            }
        }
    }
}
