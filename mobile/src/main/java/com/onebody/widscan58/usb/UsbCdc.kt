// Finding the board's CDC-ACM endpoints in the Android USB host stack.
// GPL-3.0-or-later.
package com.onebody.widscan58.usb

import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Parcelable

const val ESPRESSIF_VID = 0x303A

/** The Class-typed overload only exists from API 33; the deprecated one still works everywhere. */
fun <T : Parcelable> Intent.usbExtra(key: String, type: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(key, type)
    else @Suppress("DEPRECATION") getParcelableExtra(key)

private const val BULK_IN = UsbConstants.USB_DIR_IN or UsbConstants.USB_ENDPOINT_XFER_BULK
private const val BULK_OUT = UsbConstants.USB_DIR_OUT or UsbConstants.USB_ENDPOINT_XFER_BULK

/** The two bulk pipes plus the CDC control interface that carries SET_CONTROL_LINE_STATE. */
class CdcLayout(val control: UsbInterface, val data: UsbInterface) {
    val inEndpoint: UsbEndpoint? get() = data.getEndpoint(BULK_IN)
    val outEndpoint: UsbEndpoint? get() = data.getEndpoint(BULK_OUT)

    fun describe(): String = buildString {
        append("control iface ").append(control.id).append('(').append(control.interfaceClass).append(')')
        append("  data iface ").append(data.id).append('(').append(data.interfaceClass).append(')')
        append("  in=").append(inEndpoint?.address)
        append(" out=").append(outEndpoint?.address)
    }
}

private fun UsbDevice.interfaces() = (0 until interfaceCount).map { getInterface(it) }

/**
 * The board's native USB is CDC-ACM: a class 2 control interface and a class 10 data
 * interface with the bulk pair. Some stacks present the data interface without the class,
 * so the bulk pair is what actually has to be there.
 */
fun cdcLayoutOf(device: UsbDevice): CdcLayout? {
    val withBothPipes = device.interfaces().filter { it.getEndpoint(BULK_IN) != null && it.getEndpoint(BULK_OUT) != null }
    val data = withBothPipes.firstOrNull { it.interfaceClass == 10 } ?: withBothPipes.firstOrNull() ?: return null
    val control = device.interfaces().firstOrNull { it != data && it.interfaceClass == 2 } ?: return null
    return CdcLayout(control, data)
}

fun espressifDevices(manager: UsbManager): List<UsbDevice> =
    manager.deviceList.values.filter { it.vendorId == ESPRESSIF_VID }.sortedBy { it.deviceName }

/** The first Espressif device that speaks CDC, i.e. our board. */
fun findBoard(manager: UsbManager): UsbDevice? = espressifDevices(manager).firstOrNull { cdcLayoutOf(it) != null }
