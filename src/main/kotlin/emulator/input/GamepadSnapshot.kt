package emulator.input

/**
 * A generic gamepad state snapshot, field-for-field compatible with the real FTC SDK's
 * `com.qualcomm.robotcore.hardware.Gamepad` -- consumers just copy each field across. Produced by
 * whichever of [emulator.ui.KeyTracker], [XInputController], or [LegacyJoystickController] is
 * active for a given pad; see [CombinedGamepadInput].
 */
data class GamepadSnapshot(
    val leftStickX: Float = 0f,
    val leftStickY: Float = 0f,
    val rightStickX: Float = 0f,
    val rightStickY: Float = 0f,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f,
    val a: Boolean = false,
    val b: Boolean = false,
    val x: Boolean = false,
    val y: Boolean = false,
    val leftBumper: Boolean = false,
    val rightBumper: Boolean = false,
    val dpadUp: Boolean = false,
    val dpadDown: Boolean = false,
    val dpadLeft: Boolean = false,
    val dpadRight: Boolean = false,
    val start: Boolean = false,
    val back: Boolean = false,
    val options: Boolean = false
)
