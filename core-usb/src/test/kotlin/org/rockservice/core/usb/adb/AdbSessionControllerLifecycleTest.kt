package org.rockservice.core.usb.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbSessionControllerLifecycleTest {
    @Test
    fun `stream close waits for pending WRTE acknowledgement before sending CLSE`() = runTest {
        val transport = FakeAdbMessageTransport()
        val controller = controller(transport)
        controller.start()
        val stream = openStream(controller, transport, remoteId = 31)

        val writing = async { stream.write(byteArrayOf(1, 2, 3)) }
        val write = transport.sent.receive()
        assertEquals(AdbCommand.WRTE, write.command)

        val closing = async { stream.close() }
        assertNull(
            "CLSE must not overtake a WRTE that is still waiting for OKAY.",
            withTimeoutOrNull(100) { transport.sent.receive() },
        )

        transport.incoming.send(
            AdbMessage(AdbCommand.OKAY, arg0 = stream.remoteId, arg1 = stream.localId),
        )
        writing.await()

        val close = transport.sent.receive()
        assertEquals(AdbCommand.CLSE, close.command)
        assertEquals(stream.localId, close.arg0)
        assertEquals(stream.remoteId, close.arg1)
        transport.incoming.send(
            AdbMessage(AdbCommand.CLSE, arg0 = stream.remoteId, arg1 = stream.localId),
        )
        closing.await()
        controller.close()
    }

    @Test
    fun `late OKAY for tombstoned stream receives CLSE without killing session`() = runTest {
        val transport = FakeAdbMessageTransport()
        val controller = controller(transport)
        controller.start()
        val first = openStream(controller, transport, remoteId = 44)

        transport.incoming.send(
            AdbMessage(AdbCommand.CLSE, arg0 = first.remoteId, arg1 = first.localId),
        )
        val reciprocal = transport.sent.receive()
        assertEquals(AdbCommand.CLSE, reciprocal.command)
        assertNull(first.read())

        transport.incoming.send(
            AdbMessage(AdbCommand.OKAY, arg0 = first.remoteId, arg1 = first.localId),
        )
        val cleanup = transport.sent.receive()
        assertEquals(AdbCommand.CLSE, cleanup.command)
        assertEquals(first.localId, cleanup.arg0)
        assertEquals(first.remoteId, cleanup.arg1)

        val second = openStream(controller, transport, remoteId = 45)
        assertEquals(45L, second.remoteId)
        controller.close()
    }

    @Test
    fun `session enforces configured concurrent stream limit before another OPEN`() = runTest {
        val transport = FakeAdbMessageTransport()
        val controller = AdbSessionController(
            transport = transport,
            peer = peer(),
            scope = this,
            receivePollTimeoutMillis = 1_000,
            maximumOpenStreams = 1,
            closeTransportOnClose = false,
        )
        controller.start()
        openStream(controller, transport, remoteId = 51)

        val error = runCatching {
            controller.open(AdbDiagnosticService.KERNEL)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(transport.sent.tryReceive().isFailure)
        controller.close()
    }

    private fun CoroutineScope.controller(
        transport: FakeAdbMessageTransport,
    ): AdbSessionController = AdbSessionController(
        transport = transport,
        peer = peer(),
        scope = this,
        receivePollTimeoutMillis = 1_000,
        closeTransportOnClose = false,
    )

    private fun peer(): AdbConnectedPeer = AdbConnectedPeer(
        protocolVersion = AdbProtocolCodec.DEFAULT_PROTOCOL_VERSION,
        maxDataBytes = 4096,
        banner = "device::ro.product.name=test;",
    )

    private suspend fun CoroutineScope.openStream(
        controller: AdbSessionController,
        transport: FakeAdbMessageTransport,
        remoteId: Long,
    ): AdbSessionStream {
        val opening = async { controller.open(AdbDiagnosticService.PROPERTIES) }
        val open = transport.sent.receive()
        assertEquals(AdbCommand.OPEN, open.command)
        transport.incoming.send(
            AdbMessage(AdbCommand.OKAY, arg0 = remoteId, arg1 = open.arg0),
        )
        return opening.await()
    }

    private class FakeAdbMessageTransport : AdbMessageTransport {
        val incoming = Channel<AdbMessage>(Channel.UNLIMITED)
        val sent = Channel<AdbMessage>(Channel.UNLIMITED)
        val closed = CompletableDeferred<Unit>()

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
