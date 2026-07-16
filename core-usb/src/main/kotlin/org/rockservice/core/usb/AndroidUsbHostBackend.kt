package org.rockservice.core.usb

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

internal data class UsbHostDeviceSnapshot(
    val transportId: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String?,
    val product: String?,
    val serialNumber: String?,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val hasPermission: Boolean,
)

internal interface UsbHostPlatform {
    suspend fun listDevices(): List<UsbHostDeviceSnapshot>
    suspend fun inspectTopology(transportId: String): UsbDeviceTopology
    suspend fun requestPermission(transportId: String): Boolean
    suspend fun readRawDescriptors(transportId: String): ByteArray
    suspend fun close()
}

class AndroidUsbHostBackend internal constructor(
    private val platform: UsbHostPlatform,
) : UsbBackend {
    constructor(context: Context) : this(AndroidUsbHostPlatform(context.applicationContext))

    private val closed = AtomicBoolean(false)

    override val kind: UsbBackendKind = UsbBackendKind.ANDROID_HOST

    override suspend fun listDevices(timeoutMillis: Long): List<UsbDeviceDescriptor> =
        runOperation(timeoutMillis) {
            platform.listDevices().map { snapshot -> snapshot.toDescriptor() }
        }

    suspend fun inspectTopology(
        device: UsbDeviceDescriptor,
        timeoutMillis: Long = UsbBackend.DEFAULT_TIMEOUT_MILLIS,
    ): UsbDeviceTopology = runOperation(timeoutMillis) {
        val transportId = requireNotNull(device.transportId) {
            "Android USB Host topology inspection requires a transportId from a fresh enumeration."
        }
        val attached = findAttachedTarget(transportId)
        require(matchesIdentity(device, attached)) {
            "USB target identity changed before topology inspection."
        }
        currentCoroutineContext().ensureActive()
        val topology = platform.inspectTopology(transportId)
        require(topology.transportId == transportId) {
            "USB topology belongs to a different transport target."
        }
        topology
    }

    /** Requests permission for one freshly enumerated target and revalidates identity after grant. */
    suspend fun requestPermission(
        device: UsbDeviceDescriptor,
        timeoutMillis: Long = UsbBackend.DEFAULT_TIMEOUT_MILLIS,
    ): UsbDeviceDescriptor = runOperation(timeoutMillis) {
        requestPermissionInternal(device, "permission request")
    }

    override suspend fun read(
        device: UsbDeviceDescriptor,
        offset: Long,
        length: Int,
        timeoutMillis: Long,
    ): ByteArray = runOperation(timeoutMillis) {
        validateReadRange(offset, length)
        val permittedDevice = requestPermissionInternal(device, "descriptor read")
        val transportId = requireNotNull(permittedDevice.transportId)
        val descriptors = platform.readRawDescriptors(transportId)
        if (offset >= descriptors.size.toLong() || length == 0) {
            return@runOperation ByteArray(0)
        }
        val start = offset.toInt()
        val end = minOf(descriptors.size.toLong(), offset + length.toLong()).toInt()
        descriptors.copyOfRange(start, end)
    }

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) platform.close()
    }

    private suspend fun requestPermissionInternal(
        device: UsbDeviceDescriptor,
        operationLabel: String,
    ): UsbDeviceDescriptor {
        val transportId = requireNotNull(device.transportId) {
            "Android USB Host $operationLabel requires a transportId from a fresh enumeration."
        }
        val attached = findAttachedTarget(transportId)
        require(matchesIdentity(device, attached)) {
            "USB target identity changed before $operationLabel."
        }
        val permitted = attached.hasPermission || platform.requestPermission(transportId)
        check(permitted) { "USB permission was denied for the selected device." }
        currentCoroutineContext().ensureActive()
        val revalidated = findAttachedTarget(transportId)
        require(matchesIdentity(device, revalidated)) {
            "USB target identity changed after permission handling for $operationLabel."
        }
        check(revalidated.hasPermission) {
            "Android reported permission success but the selected USB target is not permitted."
        }
        return revalidated.toDescriptor()
    }

    private suspend fun findAttachedTarget(transportId: String): UsbHostDeviceSnapshot =
        platform.listDevices().singleOrNull { snapshot -> snapshot.transportId == transportId }
            ?: throw IllegalArgumentException("USB device is no longer attached.")

    private fun matchesIdentity(
        expected: UsbDeviceDescriptor,
        actual: UsbHostDeviceSnapshot,
    ): Boolean =
        actual.vendorId == expected.vendorId &&
            actual.productId == expected.productId &&
            expected.deviceClass?.let { it == actual.deviceClass } != false &&
            expected.deviceSubclass?.let { it == actual.deviceSubclass } != false &&
            expected.deviceProtocol?.let { it == actual.deviceProtocol } != false

    private suspend fun <T> runOperation(
        timeoutMillis: Long,
        block: suspend () -> T,
    ): T {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero." }
        check(!closed.get()) { "USB backend is closed." }
        return withTimeout(timeoutMillis) {
            currentCoroutineContext().ensureActive()
            block()
        }
    }

    private fun validateReadRange(offset: Long, length: Int) {
        require(offset >= 0) { "Offset must be non-negative." }
        require(length in 0..MAX_DESCRIPTOR_READ_BYTES) {
            "Read length exceeds the Android USB descriptor safety limit."
        }
        require(length == 0 || offset <= Long.MAX_VALUE - length.toLong()) {
            "Read range overflows."
        }
    }

    private fun UsbHostDeviceSnapshot.toDescriptor(): UsbDeviceDescriptor = UsbDeviceDescriptor(
        vendorId = vendorId,
        productId = productId,
        manufacturer = manufacturer,
        product = product,
        serialNumber = serialNumber,
        transportId = transportId,
        deviceClass = deviceClass,
        deviceSubclass = deviceSubclass,
        deviceProtocol = deviceProtocol,
        hasPermission = hasPermission,
    )

    private companion object {
        const val MAX_DESCRIPTOR_READ_BYTES = 65_536
    }
}
