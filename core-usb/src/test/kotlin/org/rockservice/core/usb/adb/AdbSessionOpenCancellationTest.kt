package org.rockservice.core.usb.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbSessionOpenCancellationTest {
    @Test
    fun `cancelled sync OPEN is tombstoned and late OKAY is cleaned without killing session`() = runTest {
        val transport = FakeAdbMessageTransport()
        val controller = AdbSessionController(
            transport = transport,
            peer = AdbConnectedPeer(
                protocolVersion = AdbProtocolCodec.DEFAULT_PROTOCOL_VERSION,
                maxDataBytes = 4096,
                banner = "device::test;",
            ),
            scope = this,
            receivePollTimeoutMillis = 1_000,
            closeTransportOnClose = false,
        )
        controller.start()

        val opening = async {
            runCatching {
                controller.openSync(timeoutMillis = 100)
            }.exceptionOrNull()
        }
        val firstOpen = transport.sent.receive()
        assertEquals(AdbCommand.OPEN, firstOpen.command)
        assertTrue(opening.await() is TimeoutCancellationException)

        transport.incoming.send(
            AdbMessage(AdbCommand.OKAY, arg0 = 91, arg1 = firstOpen.arg0),
        )
        val cleanup = transport.sent.receive()
        assertEquals(AdbCommand.CLSE, cleanup.command)
        assertEquals(firstOpen.arg0, cleanup.arg0)
        assertEquals(91L, cleanup.arg1)

        val secondOpening = async {
            controller.open(AdbDiagnosticService.KERNEL, timeoutMillis = 1_000)
        }
        val secondOpen = transport.sent.receive()
        assertEquals(AdbCommand.OPEN, secondOpen.command)
        transport.incoming.send(
            AdbMessage(AdbCommand.OKAY, arg0 = 92, arg1 = secondOpen.arg0),
        )
        val secondStream = secondOpening.await()
        assertEquals(92L, secondStream.remoteId)

        controller.close()
    }

    private class FakeAdbMessageTransport : AdbMessageTransport {
        val incoming = Channel<AdbMessage>(Channel.UNLIMITED)
        val sent = Channel<AdbMessage>(Channel.UNLIMITED)
        private val closed = CompletableDeferred<Unit>()

        override suspend fun send(message: AdbMessage, timeoutMillis: Long) {
            check(!closed.isCompleted) { "Fake transport is closed." }
            sent.send(message)
        }

        override suspend fun receive(timeoutMillis: Long, requireChecksum: Boolean): AdbMessage =
            incoming.receive()

        override suspend fun close() {
            if (!closed.isCompleted) closed.complete(Unit)
            incoming.cancel()
        }
    }
}
