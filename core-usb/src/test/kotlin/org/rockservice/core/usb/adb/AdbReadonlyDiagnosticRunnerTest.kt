package org.rockservice.core.usb.adb

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbReadonlyDiagnosticRunnerTest {
    @Test
    fun `collect preserves first-seen service order and captures bounded text`() = runTest {
        val opened = mutableListOf<AdbDiagnosticService>()
        val runner = runner { service ->
            opened += service
            FakeReadableStream(
                chunks = ArrayDeque(
                    when (service) {
                        AdbDiagnosticService.PROPERTIES -> listOf("[ro.product.model]: [Box]\n".toByteArray())
                        AdbDiagnosticService.KERNEL -> listOf("Linux test\n".toByteArray())
                        else -> emptyList()
                    },
                ),
            )
        }

        val snapshot = runner.collect(
            listOf(
                AdbDiagnosticService.PROPERTIES,
                AdbDiagnosticService.KERNEL,
                AdbDiagnosticService.PROPERTIES,
            ),
        )

        assertEquals(
            listOf(AdbDiagnosticService.PROPERTIES, AdbDiagnosticService.KERNEL),
            opened,
        )
        assertEquals(2, snapshot.sections.size)
        assertEquals(AdbDiagnosticSectionStatus.SUCCESS, snapshot.sections[0].status)
        assertTrue(snapshot.sections[0].text.contains("ro.product.model"))
        assertEquals(AdbDiagnosticSectionStatus.SUCCESS, snapshot.sections[1].status)
        assertFalse(snapshot.budgetExhausted)
    }

    @Test
    fun `per-service truncation closes stream and total budget skips later service`() = runTest {
        val first = FakeReadableStream(
            chunks = ArrayDeque(
                listOf(
                    "abcd".toByteArray(),
                    "efgh".toByteArray(),
                ),
            ),
        )
        var openCount = 0
        val runner = runner(
            maximumBytesPerService = 5,
            maximumTotalBytes = 5,
        ) {
            openCount += 1
            first
        }

        val snapshot = runner.collect(
            listOf(
                AdbDiagnosticService.PROPERTIES,
                AdbDiagnosticService.KERNEL,
            ),
        )

        assertEquals(1, openCount)
        assertEquals(AdbDiagnosticSectionStatus.TRUNCATED, snapshot.sections[0].status)
        assertEquals("abcde", snapshot.sections[0].text)
        assertEquals(5, snapshot.sections[0].retainedByteCount)
        assertEquals(8L, snapshot.sections[0].observedByteCount)
        assertEquals(1, first.closeCount)
        assertEquals(AdbDiagnosticSectionStatus.SKIPPED_BUDGET, snapshot.sections[1].status)
        assertTrue(snapshot.budgetExhausted)
    }

    @Test
    fun `rejected service is reported as unsupported without consuming budget`() = runTest {
        val runner = runner { service ->
            if (service == AdbDiagnosticService.BATTERY) {
                throw AdbServiceRejectedException("not available")
            }
            FakeReadableStream(ArrayDeque(listOf("ok".toByteArray())))
        }

        val snapshot = runner.collect(
            listOf(AdbDiagnosticService.BATTERY, AdbDiagnosticService.KERNEL),
        )

        assertEquals(AdbDiagnosticSectionStatus.UNSUPPORTED, snapshot.sections[0].status)
        assertEquals(0, snapshot.sections[0].retainedByteCount)
        assertEquals(AdbDiagnosticSectionStatus.SUCCESS, snapshot.sections[1].status)
        assertEquals("ok", snapshot.sections[1].text)
    }

    @Test
    fun `service timeout closes stream and records timeout without hiding later sections`() = runTest {
        val slow = object : AdbDiagnosticReadableStream {
            var closeCount = 0

            override suspend fun read(): ByteArray? {
                delay(60_000)
                return null
            }

            override suspend fun close(timeoutMillis: Long) {
                closeCount += 1
            }
        }
        val runner = AdbReadonlyDiagnosticRunner(
            peer = peer(),
            openStream = { service, _ ->
                if (service == AdbDiagnosticService.CPU_INFO) {
                    slow
                } else {
                    FakeReadableStream(ArrayDeque(listOf("next".toByteArray())))
                }
            },
            serviceTimeoutMillis = 100,
            maximumBytesPerService = 1024,
            maximumTotalBytes = 4096,
        )

        val snapshot = runner.collect(
            listOf(AdbDiagnosticService.CPU_INFO, AdbDiagnosticService.MEMORY_INFO),
        )

        assertEquals(AdbDiagnosticSectionStatus.TIMEOUT, snapshot.sections[0].status)
        assertEquals(1, slow.closeCount)
        assertEquals(AdbDiagnosticSectionStatus.SUCCESS, snapshot.sections[1].status)
        assertEquals("next", snapshot.sections[1].text)
    }

    @Test
    fun `NUL bytes are replaced before diagnostic text is exposed`() = runTest {
        val runner = runner {
            FakeReadableStream(ArrayDeque(listOf(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()))))
        }

        val snapshot = runner.collect(listOf(AdbDiagnosticService.PROPERTIES))

        assertEquals("a\uFFFDb", snapshot.sections.single().text)
    }

    private fun runner(
        maximumBytesPerService: Int = 1024,
        maximumTotalBytes: Int = 4096,
        opener: suspend (AdbDiagnosticService) -> AdbDiagnosticReadableStream,
    ): AdbReadonlyDiagnosticRunner = AdbReadonlyDiagnosticRunner(
        peer = peer(),
        openStream = { service, _ -> opener(service) },
        serviceTimeoutMillis = 1_000,
        maximumBytesPerService = maximumBytesPerService,
        maximumTotalBytes = maximumTotalBytes,
    )

    private fun peer(): AdbConnectedPeer = AdbConnectedPeer(
        protocolVersion = AdbProtocolCodec.DEFAULT_PROTOCOL_VERSION,
        maxDataBytes = 4096,
        banner = "device::ro.product.name=test;",
    )

    private class FakeReadableStream(
        private val chunks: ArrayDeque<ByteArray>,
    ) : AdbDiagnosticReadableStream {
        var closeCount = 0

        override suspend fun read(): ByteArray? =
            if (chunks.isEmpty()) null else chunks.removeFirst().copyOf()

        override suspend fun close(timeoutMillis: Long) {
            closeCount += 1
        }
    }
}
