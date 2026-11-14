package com.zxsofa

import android.app.Application
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
    }
}
