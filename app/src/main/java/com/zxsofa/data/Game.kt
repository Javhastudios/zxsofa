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
    /** Otras cintas/caras del mismo juego (rutas absolutas), en orden. */
    val extraFiles: List<String> = emptyList(),
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
    /** Instrucciones, mapas... (rutas absolutas de PDF, TXT o imágenes). */
    val docs: List<String> = emptyList(),
    /** Fichero .pok con los POKEs (trucos) del juego. */
    val pokPath: String? = null,
) {
    val subtitle: String
        get() = listOfNotNull(year, publisher).joinToString(" · ")

    /** Todas las cintas del juego: la principal primero. */
    val allFiles: List<String>
        get() = listOf(filePath) + extraFiles
}

/** Reconoce "Side A", "Cara B", "Part 2", "Tape 1 Side B"... en nombres de fichero. */
object Sides {
    private val marker = Regex("(?i)(?<![a-z])(side|cara|lado|part|parte|tape|cinta|disk|disco)[\\s_\\-]*([a-d]|[1-9])(?![a-z0-9])")

    fun hasMarker(fileName: String): Boolean = marker.containsMatchIn(fileName.substringAfterLast('/'))

    /** Clave para ordenar: Side A < Side B, Tape 1 Side B < Tape 2 Side A... */
    fun order(fileName: String): List<Int> =
        marker.findAll(fileName.substringAfterLast('/')).map { m ->
            val v = m.groupValues[2].lowercase()[0]
            if (v.isDigit()) v - '0' else v - 'a' + 1
        }.toList()

    fun compare(a: String, b: String): Int {
        val oa = order(a)
        val ob = order(b)
        for (i in 0 until maxOf(oa.size, ob.size)) {
            val c = oa.getOrElse(i) { 0 }.compareTo(ob.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return a.compareTo(b, ignoreCase = true)
    }

    /** Quita las marcas de cara para agrupar ("Robocop - Side 2 of 2" -> "Robocop"). */
    fun stripMarkers(name: String): String =
        name.replace(Regex("(?i)(?<![a-z])(side|cara|lado|part|parte|tape|cinta|disk|disco)[\\s_\\-]*([a-d]|[1-9])(\\s*(of|de)\\s*\\d)?(?![a-z0-9])"), " ")

    /** Etiqueta legible: "Cara A", "Cinta 1 · Cara B", "Parte 2"... */
    fun label(fileName: String, index: Int): String {
        val parts = marker.findAll(fileName.substringAfterLast('/')).map { m ->
            val word = when (m.groupValues[1].lowercase()) {
                "side", "cara", "lado" -> "Cara"
                "part", "parte" -> "Parte"
                "tape", "cinta" -> "Cinta"
                else -> "Disco"
            }
            "$word ${m.groupValues[2].uppercase()}"
        }.toList()
        return if (parts.isEmpty()) "Cinta ${index + 1}" else parts.joinToString(" · ")
    }
}
