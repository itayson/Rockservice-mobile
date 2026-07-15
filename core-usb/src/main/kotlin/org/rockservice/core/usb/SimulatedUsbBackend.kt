package org.rockservice.core.usb

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

class SimulatedUsbBackend(
    private val devices: List<UsbDeviceDescriptor> = listOf(
        UsbDeviceDescriptor(
            vendorId = 0x2207,
            productId = 0x330C,
            manufacturer = "Simulated Rockchip",
            product = "Loader-mode fixture",
        )
    ),
) : UsbBackend {
    private val closed = AtomicBoolean(false)

    override val kind = UsbBackendKind.SIMULATED

    override suspend fun listDevices(timeoutMillis: Long): List<UsbDeviceDescriptor> =
        runOperation(timeoutMillis) {
            devices.toList()
        }

    override suspend fun read(
        device: UsbDeviceDescriptor,
        offset: Long,
        length: Int,
        timeoutMillis: Long,
    ): ByteArray = runOperation(timeoutMillis) {
        require(device in devices) { "Device is not registered in the simulator." }
        require(offset >= 0) { "Offset must be non-negative." }
        require(length in 0..1_048_576) { "Read length exceeds the simulator safety limit." }
        require(length == 0 || offset <= Long.MAX_VALUE - (length - 1).toLong()) {
            "Read range overflows."
        }
        ByteArray(length) { index -> ((offset + index) and 0xff).toByte() }
    }

    override suspend fun close() {
        closed.set(true)
    }

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
}
