package com.zxsofa.emu

import com.zxsofa.data.ControlConfig
import com.zxsofa.data.Game
import com.zxsofa.data.JoyMode
import com.zxsofa.data.Settings

/** Traduce la ficha del juego y los ajustes a opciones del núcleo Fuse. */
object CoreOptions {

    fun forGame(game: Game, settings: Settings): Map<String, String> {
        val o = linkedMapOf(
            "fuse_machine" to game.machine.coreValue,
            "fuse_auto_machine" to "enabled",
            "fuse_size_border" to settings.border.coreValue,
            "fuse_auto_load" to "enabled",
            "fuse_fast_load" to if (settings.fastLoad) "enabled" else "disabled",
            "fuse_load_sound" to "enabled",
            "fuse_speaker_type" to "tv speaker",
            "fuse_ay_stereo_separation" to "acb",
            "fuse_display_joystick_type" to "disabled",
            "fuse_display_emulation_speed" to "disabled",
            "fuse_key_ovrlay_transp" to "enabled",
            "fuse_key_hold_time" to "300",
        )
        o.putAll(joypadMapping(game.controls))
        return o
    }

    /** Spectrum encendido sin juego (para programar). */
    fun forFree(machine: com.zxsofa.data.Machine, settings: Settings): Map<String, String> {
        val o = linkedMapOf(
            "fuse_machine" to machine.coreValue,
            "fuse_auto_machine" to "disabled",
            "fuse_size_border" to settings.border.coreValue,
            "fuse_auto_load" to "disabled",
            "fuse_fast_load" to if (settings.fastLoad) "enabled" else "disabled",
            "fuse_load_sound" to "enabled",
            "fuse_speaker_type" to "tv speaker",
            "fuse_ay_stereo_separation" to "acb",
            "fuse_display_joystick_type" to "disabled",
            "fuse_key_ovrlay_transp" to "enabled",
            "fuse_key_hold_time" to "300",
        )
        o.putAll(joypadMapping(ControlConfig()))
        return o
    }

    /** Mapeo del joypad a teclas (solo en modo "Teclas personalizadas") y botones extra. */
    fun joypadMapping(c: ControlConfig): Map<String, String> {
        val none = "<none>"
        val keys = c.mode == JoyMode.KEYS
        return mapOf(
            "fuse_joypad_up" to if (keys) c.up else none,
            "fuse_joypad_down" to if (keys) c.down else none,
            "fuse_joypad_left" to if (keys) c.left else none,
            "fuse_joypad_right" to if (keys) c.right else none,
            "fuse_joypad_a" to if (keys) c.fire else none,
            "fuse_joypad_x" to if (keys) c.fire else none,
            "fuse_joypad_y" to if (keys) c.fire else none,
            "fuse_joypad_b" to if (keys) c.up else none,
            "fuse_joypad_l" to c.extra1,
            "fuse_joypad_r" to c.extra2,
            "fuse_joypad_start" to none,
            "fuse_joypad_l2" to none,
            "fuse_joypad_r2" to none,
            "fuse_joypad_l3" to none,
            "fuse_joypad_r3" to none,
        )
    }
}
