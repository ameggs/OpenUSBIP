package io.openusbip.server.protocol

import io.openusbip.server.usb.DeviceMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android gives no bus/device number, so both are parsed out of the kernel
 * path in getDeviceName(). If that parsing is wrong the devid we advertise
 * will not match the devid clients echo back, and every URB is misrouted --
 * hence testing it directly.
 */
class DeviceMappingTest {

    @Test
    fun `parses bus and device from a kernel usb path`() {
        val bd = DeviceMapping.parseBusDev("/dev/bus/usb/001/004")!!
        assertEquals(1, bd.busnum)
        assertEquals(4, bd.devnum)
    }

    @Test
    fun `parses multi digit bus and device numbers`() {
        val bd = DeviceMapping.parseBusDev("/dev/bus/usb/012/117")!!
        assertEquals(12, bd.busnum)
        assertEquals(117, bd.devnum)
    }

    @Test
    fun `refuses a name it cannot parse instead of guessing`() {
        // Fabricating a bus/device pair would yield a devid that never matches
        // a real device, so these must fail loudly as null.
        assertNull(DeviceMapping.parseBusDev(""))
        assertNull(DeviceMapping.parseBusDev("/dev/bus/usb"))
        assertNull(DeviceMapping.parseBusDev("/dev/bus/usb/abc/def"))
        assertNull(DeviceMapping.parseBusDev("usb1"))
    }

    @Test
    fun `busid is stable and unique per bus device pair`() {
        assertEquals("1-4", DeviceMapping.busid(1, 4))
        assertEquals("2-11", DeviceMapping.busid(2, 11))
        // distinct devices never collide
        assertTrue(DeviceMapping.busid(1, 4) != DeviceMapping.busid(1, 14))
    }

    @Test
    fun `version string converts to bcd`() {
        assertEquals(0x0200, DeviceMapping.bcdVersion("2.00"))
        assertEquals(0x0100, DeviceMapping.bcdVersion("1.00"))
        assertEquals(0x0210, DeviceMapping.bcdVersion("2.10"))
        assertEquals(0x0300, DeviceMapping.bcdVersion("3.00"))
    }

    @Test
    fun `version handles missing or malformed input`() {
        assertEquals(0, DeviceMapping.bcdVersion(null))
        assertEquals(0, DeviceMapping.bcdVersion(""))
        assertEquals(0, DeviceMapping.bcdVersion("junk"))
        // a bare major with no minor still works
        assertEquals(0x0100, DeviceMapping.bcdVersion("1"))
    }

    private fun spec(
        name: String = "/dev/bus/usb/001/004",
        deviceClass: Int = 0x00,
        interfaces: List<DeviceMapping.InterfaceSpec> = listOf(
            DeviceMapping.InterfaceSpec(0x08, 0x06, 0x50),
        ),
    ) = DeviceMapping.DeviceSpec(
        deviceName = name,
        vendorId = 0x0781,
        productId = 0x5575,
        deviceClass = deviceClass,
        deviceSubClass = 0,
        deviceProtocol = 0,
        version = "2.00",
        configurationCount = 1,
        interfaces = interfaces,
    )

    @Test
    fun `maps an android device onto the wire structure`() {
        val d = DeviceMapping.toUsbipDevice(spec())!!

        assertEquals("1-4", d.busid)
        assertEquals(1, d.busnum)
        assertEquals(4, d.devnum)
        assertEquals(0x0781, d.idVendor)
        assertEquals(0x5575, d.idProduct)
        assertEquals(0x0200, d.bcdDevice)
        assertEquals(1, d.numInterfaces)
        assertEquals(0x08, d.interfaces[0].interfaceClass)
        // devid must round-trip, since the client echoes it in every URB
        assertEquals(Protocol.devid(1, 4), d.devid)
    }

    @Test
    fun `mapping fails when the path is unparseable`() {
        assertNull(DeviceMapping.toUsbipDevice(spec(name = "bogus")))
    }

    @Test
    fun `encoded mapped device is still the right size`() {
        assertEquals(Protocol.USB_DEVICE_SIZE, DeviceMapping.toUsbipDevice(spec())!!.encode().size)
    }

    @Test
    fun `hubs are not exportable`() {
        // Exporting a hub would move a whole tree of devices.
        assertTrue(!DeviceMapping.isExportable(spec(deviceClass = 0x09)))
        assertTrue(
            !DeviceMapping.isExportable(
                spec(interfaces = listOf(DeviceMapping.InterfaceSpec(0x09, 0, 0))),
            ),
        )
    }

    @Test
    fun `mass storage is exportable`() {
        assertTrue(DeviceMapping.isExportable(spec()))
    }

    @Test
    fun `a device with an unparseable path is not exportable`() {
        assertTrue(!DeviceMapping.isExportable(spec(name = "nope")))
    }
}
