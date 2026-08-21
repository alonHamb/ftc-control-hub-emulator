package emulator.hardware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimWebcamTest {

    @Test
    fun `starts connected and can be disconnected and reconnected`() {
        val webcam = SimWebcam("Webcam 1", "A1B2C3D4")

        assertTrue(webcam.connected)
        webcam.disconnect()
        assertFalse(webcam.connected)
        webcam.connect()
        assertTrue(webcam.connected)
    }

    @Test
    fun `activitySummary reports connection state and serial number`() {
        val webcam = SimWebcam("Webcam 1", "A1B2C3D4")

        assertTrue(webcam.activitySummary().contains("connected"))
        assertTrue(webcam.activitySummary().contains("A1B2C3D4"))

        webcam.disconnect()
        assertTrue(webcam.activitySummary().contains("disconnected"))
    }
}

class SimUsbSerialDeviceTest {

    @Test
    fun `feedIncoming then read returns the queued bytes in order`() {
        val device = SimUsbSerialDevice("usb_bridge", "FT1234")

        device.feedIncoming(byteArrayOf(1, 2, 3))

        assertEquals(3, device.bytesAvailable())
        assertArrayEquals(byteArrayOf(1, 2), device.read(2))
        assertEquals(1, device.bytesAvailable())
        assertArrayEquals(byteArrayOf(3), device.read(10))
        assertEquals(0, device.bytesAvailable())
    }

    @Test
    fun `write records the most recent write for a test adapter to assert against`() {
        val device = SimUsbSerialDevice("usb_bridge", null)

        device.write(byteArrayOf(9, 9))

        assertArrayEquals(byteArrayOf(9, 9), device.lastWrite())
    }
}
