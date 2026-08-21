package emulator.hardware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimI2cDeviceTest {

    private fun device(i2cAddress: Int = 0x3c) =
        SimI2cDevice(PortId(HubId.CONTROL, PortType.I2C, 0), "sensor", i2cAddress)

    @Test
    fun `writeRegister then readRegister returns the written bytes`() {
        val sensor = device()

        sensor.writeRegister(0x04, byteArrayOf(0x10, 0x20, 0x30))

        assertArrayEquals(byteArrayOf(0x10, 0x20, 0x30), sensor.readRegister(0x04, 3))
    }

    @Test
    fun `unwritten registers read as zero`() {
        val sensor = device()

        assertArrayEquals(ByteArray(4), sensor.readRegister(0x50, 4))
    }

    @Test
    fun `writes past the register file are dropped rather than throwing`() {
        val sensor = device()

        sensor.writeRegister(254, byteArrayOf(1, 2, 3, 4)) // spills past byte 255

        assertArrayEquals(byteArrayOf(1, 2), sensor.readRegister(254, 2))
    }

    @Test
    fun `engaged defaults true and can be toggled like the real I2cDevice API`() {
        val sensor = device()

        assertTrue(sensor.engaged)
        sensor.engaged = false
        assertFalse(sensor.engaged)
    }

    @Test
    fun `named readings still work alongside the register file`() {
        val sensor = device()

        sensor.setReading("distanceMm", 125.0)

        assertEquals(125.0, sensor.getReading("distanceMm"), 0.0)
        assertEquals(0.0, sensor.getReading("unset"), 0.0)
    }

    @Test
    fun `i2cAddress defaults to zero when not specified`() {
        val sensor = SimI2cDevice(PortId(HubId.CONTROL, PortType.I2C, 0), "sensor")

        assertEquals(0x00, sensor.i2cAddress)
    }
}
