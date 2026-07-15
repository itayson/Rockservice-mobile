package org.rockservice.core.usb.rockchip

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.rockservice.core.usb.RockchipUsbClassifier
import org.rockservice.core.usb.UsbDeviceDescriptor

internal interface RockchipUsbIo {
    fun write(bytes: ByteArray, timeoutMillis: Int): Int
    fun read(maximumLength: Int, timeoutMillis: Int): ByteArray
    fun close()
}

/** Android USB Host implementation restricted to the metadata-only Rockchip protocol allowlist. */
internal class AndroidRockchipReadOnlyTransport(
    private val io: RockchipUsbIo,
) : RockchipReadOnlyTransport {
    private val closed = AtomicBoolean(false)
    private val operationMutex = Mutex()

    override suspend fun exchange(
        command: ByteArray,
        responseLengthRange: IntRange,
        timeoutMillis: Long,
    ): RockchipRawExchange = withTimeout(timeoutMillis) {
        operationMutex.withLock {
            check(!closed.get()) { "Rockchip read-only transport is closed." }
            require(command.size == RockchipReadOnlyProtocolCodec.COMMAND_BLOCK_WRAPPER_SIZE) {
                "Only validated Rockchip command block wrappers are accepted."
            }
            require(responseLengthRange.first >= 0 && responseLengthRange.last <= MAXIMUM_RESPONSE_BYTES) {
                "Rockchip response range exceeds the read-only transport limit."
            }
            val timeout = timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            currentCoroutineContext().ensureActive()

            val written = io.write(command, timeout)
            check(written == command.size) {
                "Rockchip command short write: expected ${command.size} bytes, wrote $written."
            }
            currentCoroutineContext().ensureActive()

            val data = if (responseLengthRange.last == 0) {
                ByteArray(0)
            } else {
                io.read(responseLengthRange.last, timeout).also { bytes ->
                    check(bytes.size in responseLengthRange) {
                        "Rockchip data short/invalid read: expected $responseLengthRange bytes, read ${bytes.size}."
                    }
                }
            }
            currentCoroutineContext().ensureActive()

            val status = io.read(RockchipReadOnlyProtocolCodec.COMMAND_STATUS_WRAPPER_SIZE, timeout)
            check(status.size == RockchipReadOnlyProtocolCodec.COMMAND_STATUS_WRAPPER_SIZE) {
                "Rockchip CSW short read: expected ${RockchipReadOnlyProtocolCodec.COMMAND_STATUS_WRAPPER_SIZE} bytes, read ${status.size}."
            }
            RockchipRawExchange(data = data, statusBytes = status)
        }
    }

    override suspend fun close() {
        operationMutex.withLock {
            if (closed.compareAndSet(false, true)) io.close()
        }
    }

    private companion object {
        const val MAXIMUM_RESPONSE_BYTES = 512
    }
}

/** Opens only a physically revalidated Rockchip bulk interface matching the validated read-only profile. */
internal class AndroidRockchipReadOnlyTransportFactory(
    context: Context,
    private val usbManager: UsbManager = context.applicationContext
        .getSystemService(Context.USB_SERVICE) as UsbManager,
) {
    fun open(expected: UsbDeviceDescriptor): AndroidRockchipReadOnlyTransport {
        val transportId = requireNotNull(expected.transportId) {
            "Rockchip transport requires an explicitly selected target."
        }
        val device = requireNotNull(usbManager.deviceList[transportId]) {
            "Selected Rockchip USB target is no longer attached."
        }
        validateIdentity(expected, device)
        require(device.vendorId == RockchipUsbClassifier.ROCKCHIP_VENDOR_ID) {
            "Selected USB target is not a Rockchip vendor device."
        }
        check(usbManager.hasPermission(device)) {
            "Android USB permission is required before opening Rockchip transport."
        }

        val compatibleInterfaces = (0 until device.interfaceCount)
            .map(device::getInterface)
            .filter(::isCompatibleInterface)
        require(compatibleInterfaces.size == 1) {
            "Expected exactly one compatible Rockchip FF/06/05 interface, found ${compatibleInterfaces.size}."
        }
        val usbInterface = compatibleInterfaces.single()
        val bulkIn = uniqueBulkEndpoint(usbInterface, UsbConstants.USB_DIR_IN)
        val bulkOut = uniqueBulkEndpoint(usbInterface, UsbConstants.USB_DIR_OUT)

        val connection = checkNotNull(usbManager.openDevice(device)) {
            "Android failed to open the selected Rockchip USB device."
        }
        try {
            check(connection.claimInterface(usbInterface, false)) {
                "Android failed to claim the Rockchip interface without force."
            }
            return AndroidRockchipReadOnlyTransport(
                AndroidRockchipUsbIo(connection, usbInterface, bulkIn, bulkOut),
            )
        } catch (error: Throwable) {
            connection.close()
            throw error
        }
    }

    private fun validateIdentity(expected: UsbDeviceDescriptor, actual: UsbDevice) {
        require(actual.deviceName == expected.transportId) { "Rockchip target transport identity changed." }
        require(actual.vendorId == expected.vendorId && actual.productId == expected.productId) {
            "Rockchip target VID/PID changed before transport opening."
        }
        expected.deviceClass?.let { require(actual.deviceClass == it) { "Rockchip target class changed." } }
        expected.deviceSubclass?.let { require(actual.deviceSubclass == it) { "Rockchip target subclass changed." } }
        expected.deviceProtocol?.let { require(actual.deviceProtocol == it) { "Rockchip target protocol changed." } }
    }

    private fun isCompatibleInterface(usbInterface: UsbInterface): Boolean =
        usbInterface.interfaceClass == VALIDATED_INTERFACE_CLASS &&
            usbInterface.interfaceSubclass == VALIDATED_INTERFACE_SUBCLASS &&
            usbInterface.interfaceProtocol == VALIDATED_INTERFACE_PROTOCOL

    private fun uniqueBulkEndpoint(usbInterface: UsbInterface, direction: Int): UsbEndpoint {
        val endpoints = (0 until usbInterface.endpointCount)
            .map(usbInterface::getEndpoint)
            .filter { endpoint ->
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == direction
            }
        require(endpoints.size == 1) {
            "Expected exactly one ${if (direction == UsbConstants.USB_DIR_IN) "Bulk IN" else "Bulk OUT"} endpoint, found ${endpoints.size}."
        }
        return endpoints.single()
    }

    private companion object {
        const val VALIDATED_INTERFACE_CLASS = 0xFF
        const val VALIDATED_INTERFACE_SUBCLASS = 0x06
        const val VALIDATED_INTERFACE_PROTOCOL = 0x05
    }
}

private class AndroidRockchipUsbIo(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : RockchipUsbIo {
    private val closed = AtomicBoolean(false)

    override fun write(bytes: ByteArray, timeoutMillis: Int): Int {
        check(!closed.get()) { "Rockchip USB connection is closed." }
        val count = connection.bulkTransfer(bulkOut, bytes, bytes.size, timeoutMillis)
        check(count >= 0) { "Rockchip USB bulk write failed or timed out." }
        return count
    }

    override fun read(maximumLength: Int, timeoutMillis: Int): ByteArray {
        check(!closed.get()) { "Rockchip USB connection is closed." }
        val buffer = ByteArray(maximumLength)
        val count = connection.bulkTransfer(bulkIn, buffer, buffer.size, timeoutMillis)
        check(count >= 0) { "Rockchip USB bulk read failed or timed out." }
        return buffer.copyOf(count)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            connection.releaseInterface(usbInterface)
        } finally {
            connection.close()
        }
    }
}
