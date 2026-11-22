package com.zxsofa

import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zxsofa.data.GameRepository
import com.zxsofa.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Con el D-pad (como en la tele) se puede salir del buscador. */
@RunWith(AndroidJUnit4::class)
class TvSearchTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun key(code: Int) { inst.sendKeyDownUpSync(code); Thread.sleep(600); compose.waitForIdle() }

    @Test
    fun salirDelBuscador() {
        val app = inst.targetContext.applicationContext as ZXSofaApp
        runBlocking {
            listOf("Manic Miner", "Jetpac", "Atic Atac").forEachIndexed { i, n ->
                val tap = byteArrayOf(19, 0, 0, 0) + "GAME$i     ".take(10).toByteArray() + byteArrayOf(1, 0, 10, 0, 1, 0, 0) +
                    byteArrayOf(3, 0, -1, i.toByte(), (-1 xor i).toByte())
                app.repository.importBytes("$n.tap", tap)
            }
            app.repository.refresh()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(2500)
            val field = compose.onNode(hasSetTextAction())
            // 1) Abajo
            field.performClick(); Thread.sleep(800); field.assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            field.assertIsNotFocused(); Log.i("ZXSofaTest", "DPAD_DOWN sale del buscador: OK")
            // 2) Atrás (con texto escrito, cerrando antes el teclado)
            field.performClick(); field.performTextInput("man"); Thread.sleep(800); field.assertIsFocused()
            key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_BACK)
            field.assertIsNotFocused(); Log.i("ZXSofaTest", "BACK sale del buscador: OK")
            // 3) Arriba
            field.performClick(); Thread.sleep(800)
            key(KeyEvent.KEYCODE_DPAD_UP)
            field.assertIsNotFocused(); Log.i("ZXSofaTest", "DPAD_UP sale del buscador: OK")
            // 4) Búsqueda sin resultados y abajo
            field.performClick(); field.performTextInput("zzzz"); Thread.sleep(800)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            field.assertIsNotFocused(); Log.i("ZXSofaTest", "Sin resultados, DPAD_DOWN sale: OK")
        }
    }
}
