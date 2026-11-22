package com.zxsofa.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Permite salir de un campo de texto con el mando o el D-pad de la tele:
 * arriba/abajo pasan al siguiente elemento, Atrás quita el foco y, si el campo está
 * vacío, izquierda/derecha también salen. [multiLine]: arriba/abajo se quedan para moverse por el texto.
 */
fun Modifier.tvEscape(isEmpty: () -> Boolean = { false }, multiLine: Boolean = false): Modifier = composed {
    val fm = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        fun leave(dir: FocusDirection): Boolean {
            kb?.hide()
            if (!fm.moveFocus(dir)) {
                // Nada en esa dirección (p. ej. búsqueda sin resultados): probamos otras
                if (!fm.moveFocus(FocusDirection.Next) && !fm.moveFocus(FocusDirection.Previous)) fm.clearFocus()
            }
            return true
        }
        when (e.key) {
            Key.DirectionDown -> if (multiLine) false else leave(FocusDirection.Down)
            Key.DirectionUp -> if (multiLine) false else leave(FocusDirection.Up)
            Key.DirectionLeft -> if (isEmpty()) leave(FocusDirection.Left) else false
            Key.DirectionRight -> if (isEmpty()) leave(FocusDirection.Right) else false
            Key.Back, Key.Escape, Key.ButtonB -> { kb?.hide(); fm.clearFocus(); true }
            else -> false
        }
    }
}
