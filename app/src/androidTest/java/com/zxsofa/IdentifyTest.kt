package com.zxsofa

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zxsofa.data.GameRepository
import com.zxsofa.net.LocalZxdb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Prueba en un Android real: importar un juego, identificarlo y comprobar que se rellenan los datos. */
@RunWith(AndroidJUnit4::class)
class IdentifyTest {
    private val TAG = "ZXSofaTest"

    @Test
    fun identificaYRellenaDatos() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = GameRepository(ctx)

        val local = LocalZxdb.identify(ctx, "Sabre Wulf (1984)(Ultimate Play The Game).tzx", "Sabre Wulf")
        Log.i(TAG, "local=$local")
        assertNotNull("El índice local no encuentra Sabre Wulf", local)

        // Una cinta TAP mínima válida con ese nombre
        val tap = byteArrayOf(19, 0, 0, 0) + "SABREWULF ".toByteArray() + byteArrayOf(1, 0, 10, 0, 1, 0, 0) +
            byteArrayOf(3, 0, -1, 0, -1)
        val name = "Sabre Wulf (1984)(Ultimate Play The Game) ${System.currentTimeMillis()}.tap"
        val res = repo.importBytes(name, tap)
        Log.i(TAG, "import=$res")
        val game = when (res) {
            is GameRepository.ImportResult.Added -> res.game
            is GameRepository.ImportResult.Duplicate -> res.game
            else -> throw AssertionError("Importación fallida: $res")
        }
        val msg = repo.autoIdentify(game)
        Log.i(TAG, "identify=$msg")
        val g = repo.get(game.id)!!
        Log.i(TAG, "RESULTADO title=${g.title} year=${g.year} publisher=${g.publisher} genre=${g.genre} cover=${g.coverPath} " +
            "coverExiste=${g.coverPath?.let { File(it).exists() }} screen=${g.screenPath} docs=${g.docs} pok=${g.pokPath}")
        assertTrue("Año vacío: $g", !g.year.isNullOrBlank())
        assertTrue("Compañía vacía: $g", !g.publisher.isNullOrBlank())
        assertTrue("Género vacío: $g", !g.genre.isNullOrBlank())
        repo.delete(g)
    }
}
