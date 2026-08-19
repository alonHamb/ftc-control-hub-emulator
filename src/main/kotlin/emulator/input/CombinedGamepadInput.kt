package emulator.input

import emulator.ui.KeyTracker

/** One tick's worth of gamepad1/gamepad2 state, plus a human-readable label for where it came from. */
data class CombinedGamepadState(
    val gamepad1: GamepadSnapshot,
    val gamepad1Source: String,
    val gamepad2: GamepadSnapshot,
    val gamepad2Source: String
)

/**
 * Combines every input source this emulator knows how to read into one gamepad1/gamepad2 state
 * per tick, in priority order:
 *
 * 1. XInput (Xbox controllers, and anything else that emulates XInput) -- the best-supported
 *    path: precise official button/axis semantics, no guessing.
 * 2. Windows' legacy `winmm` joystick API ([LegacyJoystickController]) -- catches everything else
 *    Windows can see as a game controller (PlayStation DualShock/DualSense, generic USB gamepads,
 *    ...), with a best-effort button/axis mapping since that API doesn't standardize meaning the
 *    way XInput does.
 * 3. Keyboard -- always available, drives gamepad1 only.
 *
 * Each connected physical controller claims one XInput slot or one legacy joystick slot; the
 * first one found drives gamepad1, the second (if any) drives gamepad2.
 */
class CombinedGamepadInput(private val keyTracker: KeyTracker) {
    private val xinput = listOf(XInputController(0), XInputController(1), XInputController(2), XInputController(3))

    fun poll(): CombinedGamepadState {
        val xinputHits = xinput.mapIndexedNotNull { index, controller ->
            controller.poll()?.let { it to "XInput controller $index" }
        }
        val legacyHits = if (xinputHits.size < 2) {
            LegacyJoystickController.connectedDeviceIds()
                .mapNotNull { id -> LegacyJoystickController(id).poll()?.let { it to "Joystick $id" } }
        } else {
            emptyList()
        }
        val hits = xinputHits + legacyHits

        val (gamepad1, source1) = hits.getOrNull(0) ?: (keyTracker.snapshot() to "Keyboard")
        val (gamepad2, source2) = hits.getOrNull(1) ?: (GamepadSnapshot() to "None")

        return CombinedGamepadState(gamepad1, source1, gamepad2, source2)
    }
}
