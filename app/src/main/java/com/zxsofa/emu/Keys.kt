package com.zxsofa.emu

import android.view.KeyEvent

/** Códigos RETROK de libretro que entiende el núcleo. */
object RetroK {
    const val RETURN = 13
    const val SPACE = 32
    const val BACKSPACE = 8
    const val UP = 273
    const val DOWN = 274
    const val RIGHT = 275
    const val LEFT = 276
    const val RSHIFT = 303
    const val LSHIFT = 304   // CAPS SHIFT
    const val RCTRL = 305
    const val LCTRL = 306    // SYMBOL SHIFT
    const val RALT = 307
    const val LALT = 308

    fun digit(d: Int) = 48 + d
    fun letter(c: Char) = c.lowercaseChar().code
}

/** Bits del joypad (RETRO_DEVICE_ID_JOYPAD_*). */
object Pad {
    const val B = 1 shl 0       // por defecto: arriba (saltar)
    const val Y = 1 shl 1       // disparo
    const val SELECT = 1 shl 2  // abre/cierra el teclado en pantalla del núcleo
    const val START = 1 shl 3
    const val UP = 1 shl 4
    const val DOWN = 1 shl 5
    const val LEFT = 1 shl 6
    const val RIGHT = 1 shl 7
    const val A = 1 shl 8       // disparo
    const val X = 1 shl 9       // disparo
    const val L = 1 shl 10      // ENTER
    const val R = 1 shl 11      // ESPACIO
}

/** Una tecla del Spectrum: etiqueta y código RETROK. */
data class ZxKey(val label: String, val code: Int, val sub: String = "")

object ZxKeyboard {
    val CAPS = ZxKey("CAPS", RetroK.LSHIFT, "SHIFT")
    val SYMBOL = ZxKey("SYMB", RetroK.LCTRL, "SHIFT")
    val ENTER = ZxKey("ENTER", RetroK.RETURN)
    val SPACE = ZxKey("SPACE", RetroK.SPACE, "BREAK")

    val rows: List<List<ZxKey>> = listOf(
        "1234567890".map { ZxKey(it.toString(), it.code) },
        "QWERTYUIOP".map { ZxKey(it.toString(), RetroK.letter(it)) },
        "ASDFGHJKL".map { ZxKey(it.toString(), RetroK.letter(it)) } + ENTER,
        listOf(CAPS) + "ZXCVBNM".map { ZxKey(it.toString(), RetroK.letter(it)) } + listOf(SYMBOL, SPACE),
    )

    /** Nombres de teclas tal y como los acepta la opción fuse_joypad_* del núcleo. */
    val coreKeyNames: List<String> =
        listOf("<none>") + ('0'..'9').map { it.toString() } + ('a'..'z').map { it.toString() } +
            listOf("Enter", "Caps", "Symbol", "Space")

    fun coreKeyLabel(name: String): String = when (name) {
        "<none>" -> "—"
        "Enter" -> "ENTER"
        "Caps" -> "CAPS SHIFT"
        "Symbol" -> "SYMBOL SHIFT"
        "Space" -> "ESPACIO"
        else -> name.uppercase()
    }

    /** Código RETROK para un nombre de tecla del núcleo (para los botones extra táctiles). */
    fun retrokForCoreName(name: String): Int? = when {
        name == "Enter" -> RetroK.RETURN
        name == "Caps" -> RetroK.LSHIFT
        name == "Symbol" -> RetroK.LCTRL
        name == "Space" -> RetroK.SPACE
        name.length == 1 && name[0].isLetterOrDigit() -> name[0].lowercaseChar().code
        else -> null
    }
}

/** Traducción de teclas de un teclado físico Android a RETROK. */
object AndroidKeyMap {
    fun toRetroK(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> RetroK.digit(keyCode - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 'a'.code + (keyCode - KeyEvent.KEYCODE_A)
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RetroK.RETURN
        KeyEvent.KEYCODE_SPACE -> RetroK.SPACE
        KeyEvent.KEYCODE_DEL -> RetroK.BACKSPACE
        KeyEvent.KEYCODE_SHIFT_LEFT -> RetroK.LSHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> RetroK.RSHIFT
        KeyEvent.KEYCODE_CTRL_LEFT -> RetroK.LCTRL
        KeyEvent.KEYCODE_CTRL_RIGHT -> RetroK.RCTRL
        KeyEvent.KEYCODE_ALT_LEFT -> RetroK.LALT
        KeyEvent.KEYCODE_ALT_RIGHT -> RetroK.RALT
        else -> null
    }
}
