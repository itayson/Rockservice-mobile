package org.rockservice.core.usb.rockchip

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.rockservice.core.usb.RockchipUsbClassifier
import org.rockservice.core.usb.UsbDeviceDescriptor

internal enum class RockchipUsbIoMethod(val displayName: String) {
    BULK_TRANSFER("bulkTransfer"),
    USB_REQUEST("UsbRequest"),
}

internal enum class RockchipTransportStage(val displayName: String) {
    COMMAND_WRITE("COMMAND_WRITE"),
    DATA_READ("DATA_READ"),
    STATUS_READ("STATUS_READ"),
}

internal class RockchipUsbTransportException(
    val stage: RockchipTransportStage,
    val method: RockchipUsbIoMethod,
    detail: String,
    cause: Throwable? = null,
) : IllegalStateException("${method.displayName}/${stage.displayName}: $detail", cause)

internal interface RockchipUsbIo {
    val method: RockchipUsbIoMethod
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
            val ioTimeout = (timeoutMillis - IO_TIMEOUT_MARGIN_MILLIS)
                .coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            currentCoroutineContext().ensureActive()

            val written = atStage(RockchipTransportStage.COMMAND_WRITE) {
                io.write(command, ioTimeout)
            }
            if (written != command.size) {
                throw RockchipUsbTransportException(
                    stage = RockchipTransportStage.COMMAND_WRITE,
                    method = io.method,
                    detail = "short write: expected ${command.size} bytes, wrote $written.",
                )
            }
            currentCoroutineContext().ensureActive()

            val data = if (responseLengthRange.last == 0) {
                ByteArray(0)
            } else {
                atStage(RockchipTransportStage.DATA_READ) {
                    io.read(responseLengthRange.last, ioTimeout)
                }.also { bytes ->
                    if (bytes.size !in responseLengthRange) {
                        throw RockchipUsbTransportException(
                            stage = RockchipTransportStage.DATA_READ,
                            method = io.method,
                            detail = "unexpected length: expected $responseLengthRange bytes, read ${bytes.size}.",
                        )
                    }
                }
            }
            currentCoroutineContext().ensureActive()

            val status = atStage(RockchipTransportStage.STATUS_READ) {
                io.read(RockchipReadOnlyProtocolCodec.COMMAND_STATUS_WRAPPER_SIZE, ioTimeout)
            }
            if (status.size != RockchipReadOnlyProtocolCodec.COMMAND_STATUS_WRAPPER_SIZE) {
                throw RockchipUsbTransportException(
                    stage = RockchipTransportStage.STATUS_READ,
                    method = io.method,
                    detail = "short CSW: expected ${RockchipReadOnlyProtocolCodec.COMMAND_STATUS_WRAPPER_SIZE} bytes, read ${status.size}.",
                )
            }
            RockchipRawExchange(data = data, statusBytes = status)
        }
    }

    override suspend fun close() {
        operationMutex.withLock {
            if (closed.compareAndSet(false, true)) io.close()
        }
    }

    private inline fun <T> atStage(stage: RockchipTransportStage, block: () -> T): T =
        try {
            block()
        } catch (error: RockchipUsbTransportException) {
            throw error
        } catch (error: RuntimeException) {
            throw RockchipUsbTransportException(
                stage = stage,
                method = io.method,
                detail = error.message?.take(MAXIMUM_ERROR_DETAIL_LENGTH)?.ifBlank { null }
                    ?: error.javaClass.simpleName,
                cause = error,
            )
        }

    private companion object {
        const val MAXIMUM_RESPONSE_BYTES = 512
        const val IO_TIMEOUT_MARGIN_MILLIS = 250L
        const val MAXIMUM_ERROR_DETAIL_LENGTH = 180
    }
}

/** Opens only a physically revalidated Rockchip bulk interface matching the validated read-only profile. */
internal class AndroidRockchipReadOnlyTransportFactory(
    context: Context,
    private val usbManager: UsbManager = context.applicationContext
        .getSystemService(Context.USB_SERVICE) as UsbManager,
) {
    fun open(
        expected: UsbDeviceDescriptor,
        method: RockchipUsbIoMethod = RockchipUsbIoMethod.BULK_TRANSFER,
    ): AndroidRockchipReadOnlyTransport {
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
            val io = when (method) {
                RockchipUsbIoMethod.BULK_TRANSFER -> AndroidRockchipBulkTransferIo(
                    connection = connection,
                    usbInterface = usbInterface,
                    bulkIn = bulkIn,
                    bulkOut = bulkOut,
                )
                RockchipUsbIoMethod.USB_REQUEST -> AndroidRockchipUsbRequestIo(
                    connection = connection,
                    usbInterface = usbInterface,
                    bulkIn = bulkIn,
                    bulkOut = bulkOut,
                )
            }
            return AndroidRockchipReadOnlyTransport(io)
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

private abstract class AndroidRockchipUsbIoBase(
    protected val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
) : RockchipUsbIo {
    protected val closed = AtomicBoolean(false)

    final override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            connection.releaseInterface(usbInterface)
        } finally {
            connection.close()
        }
    }
}

private class AndroidRockchipBulkTransferIo(
    connection: UsbDeviceConnection,
    usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : AndroidRockchipUsbIoBase(connection, usbInterface) {
    override val method: RockchipUsbIoMethod = RockchipUsbIoMethod.BULK_TRANSFER

    override fun write(bytes: ByteArray, timeoutMillis: Int): Int {
        check(!closed.get()) { "Rockchip USB connection is closed." }
        val count = connection.bulkTransfer(bulkOut, bytes, bytes.size, timeoutMillis)
        check(count >= 0) { "bulk OUT failed or timed out after $timeoutMillis ms." }
        return count
    }

    override fun read(maximumLength: Int, timeoutMillis: Int): ByteArray {
        check(!closed.get()) { "Rockchip USB connection is closed." }
        val buffer = ByteArray(maximumLength)
        val count = connection.bulkTransfer(bulkIn, buffer, buffer.size, timeoutMillis)
        check(count >= 0) { "bulk IN failed or timed out after $timeoutMillis ms." }
        return buffer.copyOf(count)
    }
}

private class AndroidRockchipUsbRequestIo(
    connection: UsbDeviceConnection,
    usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : AndroidRockchipUsbIoBase(connection, usbInterface) {
    override val method: RockchipUsbIoMethod = RockchipUsbIoMethod.USB_REQUEST

    override fun write(bytes: ByteArray, timeoutMillis: Int): Int {
        check(!closed.get()) { "Rockchip USB connection is closed." }
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        buffer.put(bytes)
        buffer.flip()
        return executeRequest(bulkOut, buffer, timeoutMillis)
    }

    override fun read(maximumLength: Int, timeoutMillis: Int): ByteArray {
        check(!closed.get()) { "Rockchip USB connection is closed." }
        val buffer = ByteBuffer.allocateDirect(maximumLength)
        val count = executeRequest(bulkIn, buffer, timeoutMillis)
        buffer.flip()
        return ByteArray(count).also(buffer::get)
    }

    private fun executeRequest(
        endpoint: UsbEndpoint,
        buffer: ByteBuffer,
        timeoutMillis: Int,
    ): Int {
        val request = UsbRequest()
        check(request.initialize(connection, endpoint)) {
            "UsbRequest initialization failed for endpoint 0x${endpoint.address.toString(16)}."
        }
        val requestToken = Any()
        request.setClientData(requestToken)
        var queued = false
        var completed = false
        try {
            check(request.queue(buffer)) {
                "UsbRequest queue failed for endpoint 0x${endpoint.address.toString(16)}."
            }
            queued = true
            val returned = try {
                connection.requestWait(timeoutMillis.toLong())
            } catch (timeout: TimeoutException) {
                throw IllegalStateException("UsbRequest timed out after $timeoutMillis ms.", timeout)
            }
            check(returned != null) { "UsbRequest completed with an Android USB error." }
            check(returned === request || returned.clientData === requestToken) {
                "UsbRequest completion did not match the queued Rockchip request."
            }
            completed = true
            return buffer.position()
        } finally {
            if (queued && !completed) request.cancel()
            request.close()
        }
    }
}
