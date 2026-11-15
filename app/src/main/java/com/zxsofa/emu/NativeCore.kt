package com.zxsofa.emu

import android.graphics.Bitmap

/**
 * Acceso directo al núcleo Fuse (libzxsofa.so). Es un único emulador por proceso.
 * Todas las funciones salvo las de entrada deben llamarse desde el hilo del emulador.
 */
object NativeCore {
    init {
        System.loadLibrary("zxsofa")
    }

    external fun nativeInit(systemDir: String, saveDir: String)
    external fun nativeClearOptions()
    external fun nativeSetOption(key: String, value: String)
    external fun nativeLoad(path: String): Boolean
    /** Enciende el Spectrum sin cargar nada. */
    external fun nativeLoadEmpty(): Boolean
    /** Guarda en un .tap la cinta actual (lo grabado con SAVE). */
    external fun nativeTapeWrite(path: String): Boolean
    external fun nativeTapePresent(): Boolean
    /** Mete una cinta/snapshot en la máquina encendida; autoload = cargar ya. */
    external fun nativeInsert(path: String, autoload: Boolean): Boolean
    external fun nativeUnload()
    external fun nativeReset()

    /** Ejecuta un fotograma; copia el audio en [audio] y devuelve los frames estéreo copiados. */
    external fun nativeRunFrame(audio: ShortArray): Int

    /** (ancho shl 16) or alto del último fotograma, 0 si todavía no hay ninguno. */
    external fun nativeFrameSize(): Int
    external fun nativeCopyFrame(bitmap: Bitmap): Boolean

    // Entrada: seguras desde cualquier hilo
    external fun nativeSetJoystickType(type: Int)
    external fun nativeSetJoypad(mask: Int)
    external fun nativeSetKey(code: Int, down: Boolean)
    external fun nativeReleaseAll()

    external fun nativeSaveState(path: String): Boolean
    external fun nativeLoadState(path: String): Boolean
    external fun nativeTakeMessage(): String?

    // Varias cintas/caras (cargadas desde un .m3u)
    external fun nativeTapeCount(): Int
    external fun nativeTapeIndex(): Int
    external fun nativeTapeSelect(index: Int): Boolean

    // Catálogo de la cinta (recopilatorios) y carga desde un bloque
    external fun nativeTapeCatalog(): String
    external fun nativeTapeLoadBlock(block: Int): Boolean

    // Posición de la cinta (no va dentro de las partidas guardadas de Fuse)
    external fun nativeTapeBlock(): Int
    external fun nativeTapeSetBlock(block: Int): Boolean

    // POKEs (.pok)
    external fun nativePokLoad(path: String): Int
    external fun nativePokClear()
    external fun nativePokList(): String
    external fun nativePokSet(index: Int, active: Boolean, value: Int): Boolean
}
