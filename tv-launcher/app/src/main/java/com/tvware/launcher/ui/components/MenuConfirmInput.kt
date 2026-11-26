package com.tvware.launcher.ui.components

internal class MenuConfirmInput {
    private var pressedKey: Int? = null

    fun consume(keyCode: Int, down: Boolean, repeatCount: Int, canceled: Boolean = false): Boolean {
        if (down) {
            if (repeatCount != 0) return true
            pressedKey = keyCode
            return false
        }
        if (pressedKey != keyCode) return true
        pressedKey = null
        return canceled
    }
}
