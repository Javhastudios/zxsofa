package com.zxsofa

import android.app.Application
import kotlinx.coroutines.launch
import com.zxsofa.data.GameRepository
import com.zxsofa.data.Settings

class ZXSofaApp : Application() {
    lateinit var repository: GameRepository
        private set
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        repository = GameRepository(this)
        settings = Settings(this)
        // Cargamos el índice de ZXDB en segundo plano para que identificar sea inmediato
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try { com.zxsofa.net.LocalZxdb.preload(this@ZXSofaApp) } catch (_: Exception) {}
        }
    }
}
