package emulator.input

import com.sun.jna.Native
import com.sun.jna.Structure
import kotlin.math.abs

/**
 * Reads one Xbox/XInput-compatible USB game controller (most FTC gamepads -- Logitech F310s in
 * "X" mode, Xbox controllers, etc. -- present themselves this way) via the Windows XInput API,
 * through a thin JNA binding. Windows-only; [isAvailable] is false everywhere else, or if none of
 * the XInput DLLs can be loaded for any reason, so callers can fall back to the keyboard without
 * crashing.
 */
class XInputController(private val userIndex: Int) {
    companion object {
        private const val ERROR_SUCCESS = 0
        private const val BUTTON_DPAD_UP = 0x0001
        private const val BUTTON_DPAD_DOWN = 0x0002
        private const val BUTTON_DPAD_LEFT = 0x0004
        private const val BUTTON_DPAD_RIGHT = 0x0008
        private const val BUTTON_START = 0x0010
        private const val BUTTON_BACK = 0x0020
        private const val BUTTON_LEFT_SHOULDER = 0x0100
        private const val BUTTON_RIGHT_SHOULDER = 0x0200
        private const val BUTTON_A = 0x1000
        private const val BUTTON_B = 0x2000
        private const val BUTTON_X = 0x4000
        private const val BUTTON_Y = 0x8000

        private const val THUMB_DEADZONE = 7849.0f / 32767.0f
        private const val TRIGGER_DEADZONE = 30.0f / 255.0f

        val isAvailable: Boolean by lazy {
            System.getProperty("os.name", "").contains("Windows", ignoreCase = true) &&
                runCatching { library }.isSuccess
        }

        private val library: XInputLibrary by lazy {
            var lastError: Throwable? = null
            for (dllName in listOf("xinput1_4", "xinput1_3", "xinput9_1_0")) {
                try {
                    return@lazy Native.load(dllName, XInputLibrary::class.java)
                } catch (e: Throwable) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("No XInput DLL found")
        }
    }

    /** Structural mirror of the Win32 `XINPUT_GAMEPAD` struct. */
    class XInputGamepadStruct : Structure() {
        @JvmField var wButtons: Short = 0
        @JvmField var bLeftTrigger: Byte = 0
        @JvmField var bRightTrigger: Byte = 0
        @JvmField var sThumbLX: Short = 0
        @JvmField var sThumbLY: Short = 0
        @JvmField var sThumbRX: Short = 0
        @JvmField var sThumbRY: Short = 0

        override fun getFieldOrder(): List<String> =
            listOf("wButtons", "bLeftTrigger", "bRightTrigger", "sThumbLX", "sThumbLY", "sThumbRX", "sThumbRY")
    }

    /** Structural mirror of the Win32 `XINPUT_STATE` struct. */
    class XInputStateStruct : Structure() {
        @JvmField var dwPacketNumber: Int = 0
        @JvmField var gamepad: XInputGamepadStruct = XInputGamepadStruct()

        override fun getFieldOrder(): List<String> = listOf("dwPacketNumber", "gamepad")
    }

    interface XInputLibrary : com.sun.jna.Library {
        fun XInputGetState(dwUserIndex: Int, state: XInputStateStruct): Int
    }

    private fun deadzone(value: Float, threshold: Float): Float =
        if (abs(value) < threshold) 0f else value

    /** Returns the current state, or null if this controller slot isn't connected. */
    fun poll(): GamepadSnapshot? {
        if (!isAvailable) return null
        val state = XInputStateStruct()
        val result = runCatching { library.XInputGetState(userIndex, state) }.getOrNull() ?: return null
        if (result != ERROR_SUCCESS) return null

        val pad = state.gamepad
        val buttons = pad.wButtons.toInt() and 0xFFFF

        return GamepadSnapshot(
            leftStickX = deadzone(pad.sThumbLX / 32767f, THUMB_DEADZONE),
            leftStickY = -deadzone(pad.sThumbLY / 32767f, THUMB_DEADZONE), // FTC convention: stick up is negative
            rightStickX = deadzone(pad.sThumbRX / 32767f, THUMB_DEADZONE),
            rightStickY = -deadzone(pad.sThumbRY / 32767f, THUMB_DEADZONE),
            leftTrigger = deadzone((pad.bLeftTrigger.toInt() and 0xFF) / 255f, TRIGGER_DEADZONE),
            rightTrigger = deadzone((pad.bRightTrigger.toInt() and 0xFF) / 255f, TRIGGER_DEADZONE),
            a = buttons and BUTTON_A != 0,
            b = buttons and BUTTON_B != 0,
            x = buttons and BUTTON_X != 0,
            y = buttons and BUTTON_Y != 0,
            leftBumper = buttons and BUTTON_LEFT_SHOULDER != 0,
            rightBumper = buttons and BUTTON_RIGHT_SHOULDER != 0,
            dpadUp = buttons and BUTTON_DPAD_UP != 0,
            dpadDown = buttons and BUTTON_DPAD_DOWN != 0,
            dpadLeft = buttons and BUTTON_DPAD_LEFT != 0,
            dpadRight = buttons and BUTTON_DPAD_RIGHT != 0,
            start = buttons and BUTTON_START != 0,
            back = buttons and BUTTON_BACK != 0
        )
    }
}
