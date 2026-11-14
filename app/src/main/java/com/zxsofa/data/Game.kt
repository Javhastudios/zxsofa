package com.zxsofa.data

/** Modelos de Spectrum que ofrece la app (valor = opción fuse_machine del núcleo). */
enum class Machine(val label: String, val coreValue: String) {
    SPEC48("ZX Spectrum 48K", "Spectrum 48K"),
    SPEC128("ZX Spectrum 128K", "Spectrum 128K"),
    PLUS2("ZX Spectrum +2", "Spectrum +2"),
    PLUS2A("ZX Spectrum +2A", "Spectrum +2A"),
    PLUS3("ZX Spectrum +3", "Spectrum +3"),
    SPEC16("ZX Spectrum 16K", "Spectrum 16K");

    companion object {
        val DEFAULT = SPEC128
        fun fromCore(value: String?): Machine = entries.firstOrNull { it.coreValue == value } ?: DEFAULT

        /** Interpreta el machineType de ZXDB ("ZX-Spectrum 48K", "ZX-Spectrum 128K", ...). */
        fun fromZxdb(machineType: String?): Machine? {
            val m = machineType?.lowercase() ?: return null
            return when {
                "+3" in m -> PLUS3
                "+2a" in m -> PLUS2A
                "+2" in m -> PLUS2
                "128" in m -> SPEC128
                "16k" in m -> SPEC16
                "48" in m -> SPEC48
                else -> null
            }
        }
    }
}

/** Cómo se traduce el joystick (táctil o mando) al Spectrum. */
enum class JoyMode(val label: String, val coreJoystick: Int) {
    KEMPSTON("Kempston", 1),
    SINCLAIR1("Sinclair 1 (6-0)", 2),
    SINCLAIR2("Sinclair 2 (1-5)", 3),
    CURSOR("Cursor (5-8, 0)", 0),
    KEYS("Teclas personalizadas", 1),
}

/**
 * Configuración de controles de un juego. Las teclas usan los nombres del núcleo
 * ("q", "Space", "Enter", "<none>"...), ver ZxKeyboard.coreKeyNames.
 */
data class ControlConfig(
    val mode: JoyMode = JoyMode.KEMPSTON,
    val up: String = "q",
    val down: String = "a",
    val left: String = "o",
    val right: String = "p",
    val fire: String = "Space",
    /** Botones extra (táctiles y L/R del mando). "<none>" = ENTER / ESPACIO. */
    val extra1: String = "<none>",
    val extra2: String = "<none>",
) {
    fun serialize(): String = listOf(mode.name, up, down, left, right, fire, extra1, extra2).joinToString("|")

    companion object {
        fun parse(s: String?): ControlConfig {
            if (s.isNullOrBlank()) return ControlConfig()
            val p = s.split("|")
            return try {
                ControlConfig(
                    mode = JoyMode.valueOf(p[0]),
                    up = p.getOrElse(1) { "q" }, down = p.getOrElse(2) { "a" },
                    left = p.getOrElse(3) { "o" }, right = p.getOrElse(4) { "p" },
                    fire = p.getOrElse(5) { "Space" },
                    extra1 = p.getOrElse(6) { "<none>" }, extra2 = p.getOrElse(7) { "<none>" },
                )
            } catch (e: Exception) {
                ControlConfig()
            }
        }
    }
}

data class Game(
    val id: Long = 0,
    val title: String,
    val fileName: String,
    val filePath: String,
    val md5: String,
    val year: String? = null,
    val publisher: String? = null,
    val genre: String? = null,
    val machine: Machine = Machine.DEFAULT,
    val controls: ControlConfig = ControlConfig(),
    val coverPath: String? = null,
    val screenPath: String? = null,
    val zxdbId: String? = null,
    val favorite: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val lastPlayed: Long = 0,
    val playCount: Int = 0,
    val notes: String? = null,
) {
    val subtitle: String
        get() = listOfNotNull(year, publisher).joinToString(" · ")
}
