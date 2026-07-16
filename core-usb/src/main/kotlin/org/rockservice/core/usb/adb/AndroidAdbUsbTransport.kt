package org.rockservice.core.usb.adb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.rockservice.core.usb.UsbDeviceDescriptor

/** One bounded message-level ADB transport over a selected Android USB Host connection. */
interface AdbMessageTransport {
    /** Sends exactly one validated ADB message frame. */
    suspend fun send(
        message: AdbMessage,
        timeoutMillis: Long = DEFAULT_OPERATION_TIMEOUT_MILLIS,
    )

    /** Receives exactly one validated ADB message frame. */
    suspend fun receive(
        timeoutMillis: Long = DEFAULT_OPERATION_TIMEOUT_MILLIS,
        requireChecksum: Boolean = true,
    ): AdbMessage

    /** Releases transport resources. Must be idempotent. */
    suspend fun close()

    companion object {
        const val DEFAULT_OPERATION_TIMEOUT_MILLIS = 5_000L
    }
}

/** Android USB Host transport restricted to a single validated ADB interface profile. */
internal class AndroidAdbUsbTransport(
    private val io: AdbUsbIo,
    private val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AdbMessageTransport {
    private val closed = AtomicBoolean(false)
    private val sendMutex = Mutex()
    private val receiveMutex = Mutex()
    private val closeMutex = Mutex()

    override suspend fun send(message: AdbMessage, timeoutMillis: Long) {
        require(timeoutMillis > 0L) { "ADB USB timeout must be positive." }
        val ioDeadlineNanos = ioDeadlineAfter(timeoutMillis)
        withTimeout(timeoutMillis) {
            sendMutex.withLock {
                check(!closed.get()) { "ADB USB transport is closed." }
                currentCoroutineContext().ensureActive()
                val frame = AdbProtocolCodec.encode(message)
                withContext(blockingDispatcher) {
                    io.writeExactly(
                        frame,
                        remainingIoTimeout(ioDeadlineNanos, "ADB USB send"),
                    )
                }
                currentCoroutineContext().ensureActive()
            }
        }
    }

    override suspend fun receive(
        timeoutMillis: Long,
        requireChecksum: Boolean,
    ): AdbMessage {
        require(timeoutMillis > 0L) { "ADB USB timeout must be positive." }
        val ioDeadlineNanos = ioDeadlineAfter(timeoutMillis)
        return withTimeout(timeoutMillis) {
            receiveMutex.withLock {
                check(!closed.get()) { "ADB USB transport is closed." }
                currentCoroutineContext().ensureActive()

                val headerBytes = withContext(blockingDispatcher) {
                    io.readExactly(
                        AdbProtocolCodec.HEADER_SIZE_BYTES,
                        remainingIoTimeout(ioDeadlineNanos, "ADB USB receive header"),
                    )
                }
                val header = AdbProtocolCodec.decodeHeader(headerBytes)
                currentCoroutineContext().ensureActive()
                val payload = if (header.dataLength == 0) {
                    ByteArray(0)
                } else {
                    withContext(blockingDispatcher) {
                        io.readExactly(
                            header.dataLength,
                            remainingIoTimeout(ioDeadlineNanos, "ADB USB receive payload"),
                        )
                    }
                }
                currentCoroutineContext().ensureActive()
                AdbProtocolCodec.decodePayload(header, payload, requireChecksum)
            }
        }
    }

    override suspend fun close() {
        closeMutex.withLock {
            if (!closed.compareAndSet(false, true)) return@withLock
            withContext(NonCancellable) {
                sendMutex.withLock {
                    receiveMutex.withLock {
                        withContext(blockingDispatcher) {
                            io.close()
                        }
                    }
                }
            }
        }
    }

    private fun ioDeadlineAfter(timeoutMillis: Long): Long {
        val marginMillis = minOf(IO_TIMEOUT_MARGIN_MILLIS, timeoutMillis / 4L)
        val boundedTimeoutMillis = (timeoutMillis - marginMillis)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundedTimeoutMillis)
    }

    private fun remainingIoTimeout(deadlineNanos: Long, operation: String): Int {
        val remainingNanos = deadlineNanos - System.nanoTime()
        check(remainingNanos > 0L) { "$operation exceeded its total timeout before blocking I/O started." }
        val remainingMillis = (remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND
        return remainingMillis
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private companion object {
        const val IO_TIMEOUT_MARGIN_MILLIS = 250L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/** Minimal blocking USB I/O boundary used by the coroutine transport. */
internal interface AdbUsbIo : Closeable {
    /** Writes the complete byte array before the total timeout expires. */
    fun writeExactly(bytes: ByteArray, timeoutMillis: Int)

    /** Reads exactly [byteCount] bytes before the total timeout expires. */
    fun readExactly(byteCount: Int, timeoutMillis: Int): ByteArray
}

/** Opens only an explicitly selected USB target exposing exactly one canonical ADB interface. */
class AndroidAdbUsbTransportFactory(
    context: Context,
    private val usbManager: UsbManager = context.applicationContext
        .getSystemService(Context.USB_SERVICE) as UsbManager,
) {
    fun open(expected: UsbDeviceDescriptor): AdbMessageTransport {
        val transportId = requireNotNull(expected.transportId) {
            "ADB transport requires an explicitly selected target."
        }
        val device = requireNotNull(usbManager.deviceList[transportId]) {
            "Selected ADB USB target is no longer attached."
        }
        validateIdentity(expected, device)
        check(usbManager.hasPermission(device)) {
            "Android USB permission is required before opening ADB transport."
        }

        val interfaces = (0 until device.interfaceCount)
            .map(device::getInterface)
            .filter(::isAdbInterface)
        require(interfaces.size == 1) {
            "Expected exactly one ADB FF/42/01 interface, found ${interfaces.size}."
        }
        val adbInterface = interfaces.single()
        val bulkIn = uniqueBulkEndpoint(adbInterface, UsbConstants.USB_DIR_IN)
        val bulkOut = uniqueBulkEndpoint(adbInterface, UsbConstants.USB_DIR_OUT)

        val connection = checkNotNull(usbManager.openDevice(device)) {
            "Android failed to open the selected ADB USB device."
        }
        try {
            check(connection.claimInterface(adbInterface, false)) {
                "Android failed to claim the ADB interface without force."
            }
            return AndroidAdbUsbTransport(
                AndroidAdbBulkIo(
                    connection = connection,
                    usbInterface = adbInterface,
                    bulkIn = bulkIn,
                    bulkOut = bulkOut,
                ),
            )
        } catch (error: Throwable) {
            connection.close()
            throw error
        }
    }

    private fun validateIdentity(expected: UsbDeviceDescriptor, actual: UsbDevice) {
        require(actual.deviceName == expected.transportId) { "ADB target transport identity changed." }
        require(actual.vendorId == expected.vendorId && actual.productId == expected.productId) {
            "ADB target VID/PID changed before transport opening."
        }
        expected.deviceClass?.let { require(actual.deviceClass == it) { "ADB target class changed." } }
        expected.deviceSubclass?.let { require(actual.deviceSubclass == it) { "ADB target subclass changed." } }
        expected.deviceProtocol?.let { require(actual.deviceProtocol == it) { "ADB target protocol changed." } }
    }

    private fun isAdbInterface(usbInterface: UsbInterface): Boolean =
        AdbUsbInterfaceProfile.matches(
            interfaceClass = usbInterface.interfaceClass,
            interfaceSubclass = usbInterface.interfaceSubclass,
            interfaceProtocol = usbInterface.interfaceProtocol,
        )

    private fun uniqueBulkEndpoint(usbInterface: UsbInterface, direction: Int): UsbEndpoint {
        val endpoints = (0 until usbInterface.endpointCount)
            .map(usbInterface::getEndpoint)
            .filter { endpoint ->
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == direction
            }
        require(endpoints.size == 1) {
            "Expected exactly one ${if (direction == UsbConstants.USB_DIR_IN) "Bulk IN" else "Bulk OUT"} ADB endpoint, found ${endpoints.size}."
        }
        return endpoints.single()
    }
}

/** Pure classifier for the canonical USB interface used by ADB devices. */
object AdbUsbInterfaceProfile {
    const val INTERFACE_CLASS = 0xFF
    const val INTERFACE_SUBCLASS = 0x42
    const val INTERFACE_PROTOCOL = 0x01

    fun matches(
        interfaceClass: Int,
        interfaceSubclass: Int,
        interfaceProtocol: Int,
    ): Boolean = interfaceClass == INTERFACE_CLASS &&
        interfaceSubclass == INTERFACE_SUBCLASS &&
        interfaceProtocol == INTERFACE_PROTOCOL
}

private class AndroidAdbBulkIo(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : AdbUsbIo {
    private val closed = AtomicBoolean(false)

    override fun writeExactly(bytes: ByteArray, timeoutMillis: Int) {
        check(!closed.get()) { "ADB USB connection is closed." }
        require(timeoutMillis > 0) { "ADB USB write timeout must be positive." }
        val deadlineNanos = deadlineAfter(timeoutMillis)
        var offset = 0
        while (offset < bytes.size) {
            val transferTimeout = remainingTransferTimeout(deadlineNanos, "ADB bulk OUT")
            val count = connection.bulkTransfer(
                bulkOut,
                bytes,
                offset,
                bytes.size - offset,
                transferTimeout,
            )
            check(count > 0) {
                "ADB bulk OUT failed, timed out or made no progress after $transferTimeout ms at offset $offset."
            }
            offset += count
        }
    }

    override fun readExactly(byteCount: Int, timeoutMillis: Int): ByteArray {
        check(!closed.get()) { "ADB USB connection is closed." }
        require(timeoutMillis > 0) { "ADB USB read timeout must be positive." }
        require(byteCount in 0..AdbProtocolCodec.MAXIMUM_PAYLOAD_BYTES) {
            "ADB USB read length $byteCount exceeds the local payload limit."
        }
        if (byteCount == 0) return ByteArray(0)

        val deadlineNanos = deadlineAfter(timeoutMillis)
        val output = ByteArray(byteCount)
        var offset = 0
        while (offset < output.size) {
            val transferTimeout = remainingTransferTimeout(deadlineNanos, "ADB bulk IN")
            val count = connection.bulkTransfer(
                bulkIn,
                output,
                offset,
                output.size - offset,
                transferTimeout,
            )
            check(count > 0) {
                "ADB bulk IN failed, timed out or made no progress after $transferTimeout ms at offset $offset."
            }
            offset += count
        }
        return output
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            connection.releaseInterface(usbInterface)
        } finally {
            connection.close()
        }
    }

    private fun deadlineAfter(timeoutMillis: Int): Long =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())

    private fun remainingTransferTimeout(deadlineNanos: Long, operation: String): Int {
        val remainingNanos = deadlineNanos - System.nanoTime()
        check(remainingNanos > 0L) { "$operation exceeded its total timeout before completing." }
        val remainingMillis = (remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND
        return remainingMillis
            .coerceAtLeast(1L)
            .coerceAtMost(MAXIMUM_SINGLE_BULK_TRANSFER_TIMEOUT_MILLIS.toLong())
            .toInt()
    }

    private companion object {
        const val MAXIMUM_SINGLE_BULK_TRANSFER_TIMEOUT_MILLIS = 2_000
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
