package emulator.ui

import emulator.input.GamepadSnapshot
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.Collections

/**
 * Tracks which keys are currently held down and turns them into a [GamepadSnapshot] --
 * the keyboard-driven fallback for [emulator.input.CombinedGamepadInput] when no physical
 * controller is connected.
 *
 * Left stick (drive):   W / A / S / D
 * Right stick (turn):   I / J / K / L
 * a/b/x/y:               Z / X / C / V
 * Bumpers:                Q (left)   E (right)
 * Triggers:                1 (left)   2 (right)
 * D-pad:                  Arrow keys
 * Start / Back / Options: Enter / Backspace / O
 */
class KeyTracker : KeyAdapter() {
    private val pressed: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

    override fun keyPressed(e: KeyEvent) { pressed.add(e.keyCode) }
    override fun keyReleased(e: KeyEvent) { pressed.remove(e.keyCode) }

    fun isPressed(vkCode: Int): Boolean = pressed.contains(vkCode)

    fun snapshot(): GamepadSnapshot = GamepadSnapshot(
        leftStickX = axis(KeyEvent.VK_D, KeyEvent.VK_A),
        leftStickY = axis(KeyEvent.VK_S, KeyEvent.VK_W),
        rightStickX = axis(KeyEvent.VK_L, KeyEvent.VK_J),
        rightStickY = axis(KeyEvent.VK_K, KeyEvent.VK_I),
        leftTrigger = if (isPressed(KeyEvent.VK_1)) 1f else 0f,
        rightTrigger = if (isPressed(KeyEvent.VK_2)) 1f else 0f,
        a = isPressed(KeyEvent.VK_Z),
        b = isPressed(KeyEvent.VK_X),
        x = isPressed(KeyEvent.VK_C),
        y = isPressed(KeyEvent.VK_V),
        leftBumper = isPressed(KeyEvent.VK_Q),
        rightBumper = isPressed(KeyEvent.VK_E),
        dpadUp = isPressed(KeyEvent.VK_UP),
        dpadDown = isPressed(KeyEvent.VK_DOWN),
        dpadLeft = isPressed(KeyEvent.VK_LEFT),
        dpadRight = isPressed(KeyEvent.VK_RIGHT),
        start = isPressed(KeyEvent.VK_ENTER),
        back = isPressed(KeyEvent.VK_BACK_SPACE),
        options = isPressed(KeyEvent.VK_O)
    )

    private fun axis(positive: Int, negative: Int): Float =
        (if (isPressed(positive)) 1f else 0f) - (if (isPressed(negative)) 1f else 0f)
}
