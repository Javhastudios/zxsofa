package com.zxsofa

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zxsofa.data.Machine
import com.zxsofa.ui.EmulatorActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Escribe con el teclado táctil (toques reales) y captura la pantalla del Spectrum. */
@RunWith(AndroidJUnit4::class)
class KeyboardTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun tap(tag: String) {
        val b = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val x = (b.left + b.right) / 2
        val y = (b.top + b.bottom) / 2
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        inst.uiAutomation.injectInputEvent(down, true)
        Thread.sleep(110)
        val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        inst.uiAutomation.injectInputEvent(up, true)
        Thread.sleep(160)
    }

    private fun shot(tag: String) {
        val bmp = inst.uiAutomation.takeScreenshot()
        val small = Bitmap.createScaledBitmap(bmp, bmp.width * 2 / 5, bmp.height * 2 / 5, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP).chunked(3000).forEachIndexed { i, c -> Log.i("ZXSofaShot", "$tag|$i|$c") }
    }

    @Test
    fun escribirComillasYCursores() {
        inst.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
        val ctx = inst.targetContext
        ActivityScenario.launch<EmulatorActivity>(EmulatorActivity.freeIntent(ctx, Machine.SPEC48, false)).use {
            Thread.sleep(6000)
            // 10 PRINT "HOLA" ; ENTER  y luego RUN
            listOf("key_1", "key_0", "key_P", "key_\"", "key_H", "key_O", "key_L", "key_A", "key_\"", "key_ENTER").forEach { tap(it) }
            Thread.sleep(500)
            listOf("key_R", "key_ENTER").forEach { tap(it) }
            Thread.sleep(1500)
            shot("KEYBV")
        }
    }
}
