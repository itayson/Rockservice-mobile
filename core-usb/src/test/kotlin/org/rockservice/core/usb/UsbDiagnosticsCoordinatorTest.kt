package org.rockservice.core.usb

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbDiagnosticsCoordinatorTest {
    @Test
    fun `preserves selected target after fresh enumeration still contains it`() = runTest {
        val coordinator = UsbDiagnosticsCoordinator()
        val scanner = QueueScanner(
            ready("usb-1", "usb-2"),
            ready("usb-1"),
        )

        coordinator.refresh(scanner)
        coordinator.selectTarget("usb-1")
        coordinator.refresh(scanner)

        assertEquals("usb-1", coordinator.state.value.selectedTransportId)
        assertEquals(
            1,
            (coordinator.state.value.diagnostics as UsbDiagnosticsState.Ready).devices.size,
        )
    }

    @Test
    fun `clears selected target when fresh enumeration no longer contains it`() = runTest {
        val coordinator = UsbDiagnosticsCoordinator()
        val scanner = QueueScanner(
            ready("usb-1"),
            ready("usb-2"),
        )

        coordinator.refresh(scanner)
        coordinator.selectTarget("usb-1")
        coordinator.refresh(scanner)

        assertNull(coordinator.state.value.selectedTransportId)
    }

    @Test
    fun `serializes concurrent refreshes across scanner boundary`() = runTest {
        val scanner = TrackingScanner()
        val coordinator = UsbDiagnosticsCoordinator()

        listOf(
            async { coordinator.refresh(scanner) },
            async { coordinator.refresh(scanner) },
            async { coordinator.refresh(scanner) },
        ).awaitAll()

        assertEquals(1, scanner.maxConcurrentScans.get())
        assertEquals(3, scanner.scanCount.get())
    }

    @Test
    fun `clears selected target when refresh returns an error`() = runTest {
        val coordinator = UsbDiagnosticsCoordinator()
        val scanner = QueueScanner(
            ready("usb-1"),
            UsbDiagnosticsState.Error("test failure"),
        )

        coordinator.refresh(scanner)
        coordinator.selectTarget("usb-1")
        coordinator.refresh(scanner)

        assertNull(coordinator.state.value.selectedTransportId)
        assertEquals(
            "test failure",
            (coordinator.state.value.diagnostics as UsbDiagnosticsState.Error).message,
        )
    }

    @Test
    fun `ignores selection for transport absent from current snapshot`() = runTest {
        val coordinator = UsbDiagnosticsCoordinator()
        coordinator.refresh(QueueScanner(ready("usb-1")))

        coordinator.selectTarget("usb-missing")

        assertNull(coordinator.state.value.selectedTransportId)
    }

    @Test
    fun `selection waits for in-flight refresh and uses the newest enumeration`() = runTest {
        val coordinator = UsbDiagnosticsCoordinator()
        coordinator.refresh(QueueScanner(ready("usb-1")))

        val scanStarted = CompletableDeferred<Unit>()
        val releaseScan = CompletableDeferred<Unit>()
        val refresh = async {
            coordinator.refresh(
                BlockingScanner(
                    state = ready("usb-1", "usb-2"),
                    scanStarted = scanStarted,
                    releaseScan = releaseScan,
                ),
            )
        }
        scanStarted.await()

        val selection = async { coordinator.selectTarget("usb-2") }
        releaseScan.complete(Unit)
        refresh.await()
        selection.await()

        assertEquals("usb-2", coordinator.state.value.selectedTransportId)
        assertEquals(
            2,
            (coordinator.state.value.diagnostics as UsbDiagnosticsState.Ready).devices.size,
        )
    }

    private class QueueScanner(
        vararg states: UsbDiagnosticsState,
    ) : UsbDiagnosticsScanner {
        private val queue = ArrayDeque(states.toList())

        override suspend fun scan(): UsbDiagnosticsState = queue.removeFirst()
    }

    private class TrackingScanner : UsbDiagnosticsScanner {
        val scanCount = AtomicInteger(0)
        val maxConcurrentScans = AtomicInteger(0)
        private val activeScans = AtomicInteger(0)

        override suspend fun scan(): UsbDiagnosticsState {
            val scanNumber = scanCount.incrementAndGet()
            val active = activeScans.incrementAndGet()
            maxConcurrentScans.updateAndGet { previous -> maxOf(previous, active) }
            return try {
                delay(10)
                ready("usb-$scanNumber")
            } finally {
                activeScans.decrementAndGet()
            }
        }
    }

    private class BlockingScanner(
        private val state: UsbDiagnosticsState,
        private val scanStarted: CompletableDeferred<Unit>,
        private val releaseScan: CompletableDeferred<Unit>,
    ) : UsbDiagnosticsScanner {
        override suspend fun scan(): UsbDiagnosticsState {
            scanStarted.complete(Unit)
            releaseScan.await()
            return state
        }
    }

    private companion object {
        fun ready(vararg transportIds: String): UsbDiagnosticsState.Ready =
            UsbDiagnosticsState.Ready(
                devices = transportIds.map { transportId -> snapshot(transportId) },
            )

        fun snapshot(transportId: String): UsbDiagnosticsDeviceSnapshot {
            val descriptor = UsbDeviceDescriptor(
                vendorId = RockchipUsbClassifier.ROCKCHIP_VENDOR_ID,
                productId = 0x0001,
                manufacturer = "Rockchip",
                product = "Test device",
                transportId = transportId,
            )
            val topology = UsbDeviceTopology(
                transportId = transportId,
                interfaces = emptyList(),
            )
            return UsbDiagnosticsDeviceSnapshot(
                descriptor = descriptor,
                topology = topology,
                rockchipProbe = RockchipPassiveProbe.probe(descriptor, topology),
            )
        }
    }
}
