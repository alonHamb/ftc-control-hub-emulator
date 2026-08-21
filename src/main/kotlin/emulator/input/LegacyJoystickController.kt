package emulator.input

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.win32.W32APIOptions
import kotlin.math.abs

/**
 * Reads any controller Windows exposes through its legacy `winmm` joystick API (`joyGetPosEx`) --
 * this is a much older, simpler, plain-C API than XInput or DirectInput, but Windows still routes
 * essentially every connected HID game controller through it for compatibility (Xbox controllers,
 * PlayStation DualShock/DualSense, and generic/no-name USB gamepads all showed up in testing).
 * This is what makes non-Xbox controllers (which don't speak XInput) work -- see
 * [CombinedGamepadInput], which tries [XInputController] first and falls back to this.
 *
 * Button/axis *meaning* isn't standardized the way XInput's is, so the mapping here is a
 * best-effort guess verified against a PlayStation DualSense: left stick = X/Y axes, right stick =
 * Z/R axes, triggers = U/V axes, face buttons assumed in Square/Cross/Circle/Triangle bit order
 * (bits 0-3) when the device reports 14+ buttons (matches DualShock 4 / DualSense), or a generic
 * bit 0-3 guess otherwise. If your controller doesn't match, the emulator's gamepad status panel
 * shows live values so you can see what's actually being read.
 */
class LegacyJoystickController(private val deviceId: Int) {
    companion object {
        private const val JOY_RETURNALL = 0xFF
        private const val POV_CENTERED = 0xFFFF
        private const val MAX_DEVICES = 16

        val isAvailable: Boolean by lazy {
            System.getProperty("os.name", "").contains("Windows", ignoreCase = true) &&
                runCatching { winmm }.isSuccess
        }

        private val winmm: Winmm by lazy { Native.load("winmm", Winmm::class.java, W32APIOptions.DEFAULT_OPTIONS) }

        /**
         * Connected device slots (0-15), one per unique (vendor, product) ID. A single physical
         * controller that supports more than one transport (e.g. a DualSense connected over both
         * USB and Bluetooth at once) can otherwise occupy two slots for the same device; this
         * keeps only the lowest-numbered slot for each, so it isn't double-counted as two pads.
         */
        fun connectedDeviceIds(): List<Int> {
            if (!isAvailable) return emptyList()
            val seenIds = mutableSetOf<Long>()
            val result = mutableListOf<Int>()
            for (id in 0 until MAX_DEVICES) {
                val info = JoyInfoEx()
                if (runCatching { winmm.joyGetPosEx(id, info) }.getOrNull() != 0) continue
                val caps = JoyCaps()
                if (runCatching { winmm.joyGetDevCapsA(id, caps, caps.size()) }.getOrNull() != 0) continue
                val hardwareId = (caps.wMid.toLong() shl 16) or (caps.wPid.toLong() and 0xFFFF)
                if (seenIds.add(hardwareId)) result += id
            }
            return result
        }
    }

    class JoyCaps : Structure() {
        @JvmField var wMid: Short = 0
        @JvmField var wPid: Short = 0
        @JvmField var szPname: ByteArray = ByteArray(32)
        @JvmField var wXmin = 0
        @JvmField var wXmax = 0
        @JvmField var wYmin = 0
        @JvmField var wYmax = 0
        @JvmField var wZmin = 0
        @JvmField var wZmax = 0
        @JvmField var wNumButtons = 0
        @JvmField var wPeriodMin = 0
        @JvmField var wPeriodMax = 0
        @JvmField var wRmin = 0
        @JvmField var wRmax = 0
        @JvmField var wUmin = 0
        @JvmField var wUmax = 0
        @JvmField var wVmin = 0
        @JvmField var wVmax = 0
        @JvmField var wCaps = 0
        @JvmField var wMaxAxes = 0
        @JvmField var wNumAxes = 0
        @JvmField var wMaxButtons = 0
        @JvmField var szRegKey: ByteArray = ByteArray(32)
        @JvmField var szOEMVxD: ByteArray = ByteArray(260)

        override fun getFieldOrder(): List<String> = listOf(
            "wMid", "wPid", "szPname", "wXmin", "wXmax", "wYmin", "wYmax", "wZmin", "wZmax",
            "wNumButtons", "wPeriodMin", "wPeriodMax", "wRmin", "wRmax", "wUmin", "wUmax", "wVmin", "wVmax",
            "wCaps", "wMaxAxes", "wNumAxes", "wMaxButtons", "szRegKey", "szOEMVxD"
        )
    }

    class JoyInfoEx : Structure() {
        @JvmField var dwSize = 52
        @JvmField var dwFlags = JOY_RETURNALL
        @JvmField var dwXpos = 0
        @JvmField var dwYpos = 0
        @JvmField var dwZpos = 0
        @JvmField var dwRpos = 0
        @JvmField var dwUpos = 0
        @JvmField var dwVpos = 0
        @JvmField var dwButtons = 0
        @JvmField var dwButtonNumber = 0
        @JvmField var dwPOV = POV_CENTERED
        @JvmField var dwReserved1 = 0
        @JvmField var dwReserved2 = 0

        override fun getFieldOrder(): List<String> = listOf(
            "dwSize", "dwFlags", "dwXpos", "dwYpos", "dwZpos", "dwRpos", "dwUpos", "dwVpos",
            "dwButtons", "dwButtonNumber", "dwPOV", "dwReserved1", "dwReserved2"
        )
    }

    interface Winmm : com.sun.jna.Library {
        fun joyGetNumDevs(): Int
        fun joyGetDevCapsA(uJoyID: Int, pjc: JoyCaps, cbjc: Int): Int
        fun joyGetPosEx(uJoyID: Int, pji: JoyInfoEx): Int
    }

    private fun normalizeAxis(value: Int, min: Int, max: Int, invert: Boolean = false): Float {
        if (max <= min) return 0f
        val centered = (2.0 * (value - min) / (max - min) - 1.0).toFloat().coerceIn(-1f, 1f)
        val deadzoned = if (abs(centered) < 0.12f) 0f else centered
        return if (invert) -deadzoned else deadzoned
    }

    private fun normalizeTrigger(value: Int, min: Int, max: Int): Float {
        if (max <= min) return 0f
        return ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    }

    /** Returns the current state, or null if this device slot isn't connected. */
    fun poll(): GamepadSnapshot? {
        if (!isAvailable) return null
        val caps = JoyCaps()
        if (runCatching { winmm.joyGetDevCapsA(deviceId, caps, caps.size()) }.getOrNull() != 0) return null
        val info = JoyInfoEx()
        if (runCatching { winmm.joyGetPosEx(deviceId, info) }.getOrNull() != 0) return null

        val hasRightStick = caps.wNumAxes >= 4
        val hasTriggerAxes = caps.wNumAxes >= 6
        val buttons = info.dwButtons
        val playstationLike = caps.wNumButtons >= 14

        // bit0=Square/x, bit1=Cross/a, bit2=Circle/b, bit3=Triangle/y matches DualShock4/DualSense;
        // otherwise guess the more common bit0=a,bit1=b,bit2=x,bit3=y ordinal layout.
        val (aBit, bBit, xBit, yBit) = if (playstationLike) intArrayOf(1, 2, 0, 3) else intArrayOf(0, 1, 2, 3)

        fun button(bit: Int) = (buttons shr bit) and 1 != 0

        val povUp: Boolean
        val povDown: Boolean
        val povLeft: Boolean
        val povRight: Boolean
        if (info.dwPOV == POV_CENTERED) {
            povUp = false; povDown = false; povLeft = false; povRight = false
        } else {
            val degrees = info.dwPOV / 100
            povUp = degrees in 0..44 || degrees in 316..360
            povRight = degrees in 46..134
            povDown = degrees in 136..224
            povLeft = degrees in 226..314
        }

        return GamepadSnapshot(
            leftStickX = normalizeAxis(info.dwXpos, caps.wXmin, caps.wXmax),
            // Not inverted: joyGetPosEx's Y axis already reports the low end of the calibrated
            // range (dwYpos near wYmin) when the stick is pushed up/forward, same polarity as
            // DirectInput's documented lY convention -- which already matches "FTC convention:
            // stick up is negative" (see XInputController/KeyTracker) with no extra inversion
            // needed. The previous `invert = true` here double-inverted it, so forward/back read
            // backwards for controllers read through this legacy path (confirmed against a real
            // Xbox controller, which Windows routes through this API rather than XInput on some
            // systems/drivers).
            leftStickY = normalizeAxis(info.dwYpos, caps.wYmin, caps.wYmax),
            rightStickX = if (hasRightStick) normalizeAxis(info.dwZpos, caps.wZmin, caps.wZmax) else 0f,
            rightStickY = if (hasRightStick) normalizeAxis(info.dwRpos, caps.wRmin, caps.wRmax) else 0f,
            leftTrigger = if (hasTriggerAxes) normalizeTrigger(info.dwUpos, caps.wUmin, caps.wUmax) else 0f,
            rightTrigger = if (hasTriggerAxes) normalizeTrigger(info.dwVpos, caps.wVmin, caps.wVmax) else 0f,
            a = button(aBit),
            b = button(bBit),
            x = button(xBit),
            y = button(yBit),
            leftBumper = button(4),
            rightBumper = button(5),
            dpadUp = povUp,
            dpadDown = povDown,
            dpadLeft = povLeft,
            dpadRight = povRight,
            start = button(if (playstationLike) 9 else 7),
            back = button(if (playstationLike) 8 else 6),
            options = button(if (playstationLike) 12 else 8)
        )
    }
}
