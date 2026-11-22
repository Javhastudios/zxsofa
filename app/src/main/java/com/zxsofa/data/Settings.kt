package com.zxsofa.data

import android.content.Context
import android.content.SharedPreferences

enum class TouchMode(val label: String) { AUTO("Automático"), ALWAYS("Siempre"), NEVER("Nunca") }

enum class BorderSize(val label: String, val coreValue: String) {
    FULL("Completo", "full"),
    MEDIUM("Mediano", "medium"),
    SMALL("Pequeño", "small"),
    MINIMUM("Mínimo", "minimum"),
    NONE("Sin borde", "none"),
}

/** Ajustes globales de la app. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var border: BorderSize
        get() = enumOr(prefs.getString("border", null), BorderSize.SMALL)
        set(v) = prefs.edit().putString("border", v.name).apply()

    var smoothing: Boolean
        get() = prefs.getBoolean("smoothing", false)
        set(v) = prefs.edit().putBoolean("smoothing", v).apply()

    var screenEffect: com.zxsofa.emu.ScreenEffect
        get() = enumOr(prefs.getString("effect", null), com.zxsofa.emu.ScreenEffect.SHARP)
        set(v) = prefs.edit().putString("effect", v.name).apply()

    /** Intensidad de líneas y rejilla (0–1). */
    var effectStrength: Float
        get() = prefs.getFloat("effectStrength", 0.7f)
        set(v) = prefs.edit().putFloat("effectStrength", v).apply()

    var touchMode: TouchMode
        get() = enumOr(prefs.getString("touch", null), TouchMode.AUTO)
        set(v) = prefs.edit().putString("touch", v.name).apply()

    var controlsOpacity: Float
        get() = prefs.getFloat("opacity", 0.55f)
        set(v) = prefs.edit().putFloat("opacity", v).apply()

    var fastLoad: Boolean
        get() = prefs.getBoolean("fastLoad", true)
        set(v) = prefs.edit().putBoolean("fastLoad", v).apply()

    var vibration: Boolean
        get() = prefs.getBoolean("vibration", true)
        set(v) = prefs.edit().putBoolean("vibration", v).apply()

    /** Guarda automáticamente al salir del juego para poder "Continuar". */
    var autoSave: Boolean
        get() = prefs.getBoolean("autoSave", true)
        set(v) = prefs.edit().putBoolean("autoSave", v).apply()

    var catalogView: String
        get() = prefs.getString("catalogView", "GRID") ?: "GRID"
        set(v) = prefs.edit().putString("catalogView", v).apply()

    var sortOrder: String
        get() = prefs.getString("sort", "title") ?: "title"
        set(v) = prefs.edit().putString("sort", v).apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
        try { if (name == null) def else enumValueOf<T>(name) } catch (e: Exception) { def }
}
