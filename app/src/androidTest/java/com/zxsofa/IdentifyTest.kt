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
        val name = "Sabre Wulf (1984)(Ultimate Play The Game).tap"
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

    @Test
    fun copiaDeSeguridadCompletaJuegosExistentes() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = GameRepository(ctx)
        val tap = byteArrayOf(19, 0, 0, 0) + "KNIGHTLORE".toByteArray() + byteArrayOf(1, 0, 10, 0, 1, 0, 0) +
            byteArrayOf(3, 0, -1, 0, -1)
        val res = repo.importBytes("Knight Lore (1984)(Ultimate Play The Game).tap", tap)
        val game = (res as? GameRepository.ImportResult.Added)?.game ?: (res as GameRepository.ImportResult.Duplicate).game
        repo.autoIdentify(game)
        val full = repo.get(game.id)!!
        Log.i(TAG, "IDENTIFICADO year=${full.year} pub=${full.publisher} cover=${full.coverPath != null} docs=${full.docs.size} pok=${full.pokPath != null}")

        // Copia de seguridad
        val backup = File(ctx.cacheDir, "prueba-copia.zip")
        backup.outputStream().use { repo.exportBackup(it) }

        // "Otro dispositivo": el mismo juego, pero sin identificar
        full.coverPath?.let { File(it).delete() }
        full.screenPath?.let { File(it).delete() }
        full.docs.forEach { File(it).delete() }
        full.pokPath?.let { File(it).delete() }
        repo.update(full.copy(title = "Knight Lore", year = null, publisher = null, genre = null, zxdbId = null,
            coverPath = null, screenPath = null, docs = emptyList(), pokPath = null))

        val r = repo.importFile("prueba-copia.zip", backup)
        Log.i(TAG, "IMPORTAR copia -> $r")
        val after = repo.get(game.id)!!
        Log.i(TAG, "COMPLETADO year=${after.year} pub=${after.publisher} genre=${after.genre} cover=${after.coverPath?.let { File(it).exists() }} " +
            "screen=${after.screenPath?.let { File(it).exists() }} docs=${after.docs.size} pok=${after.pokPath?.let { File(it).exists() }}")
        assertTrue(r is GameRepository.ImportResult.Backup && r.updated == 1)
        assertTrue(!after.year.isNullOrBlank() && !after.publisher.isNullOrBlank() && !after.genre.isNullOrBlank())
        assertTrue(after.coverPath != null && File(after.coverPath!!).exists())
        assertTrue(after.docs.isNotEmpty())
        repo.delete(after)
    }
}
