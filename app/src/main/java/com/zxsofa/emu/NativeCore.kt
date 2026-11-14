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
}
