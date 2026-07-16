package org.rockservice.core.usb.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbSessionControllerTest {
    @Test
    fun `open sends allowlisted service and binds remote stream id`() = runTest {
        val transport = FakeAdbMessageTransport()
        val controller = controller(transport)
        controller.start()

        val opening = async { controller.open(AdbDiagnosticService.PROPERTIES) }
        val open = transport.sent.receive()

        assertEquals(AdbCommand.OPEN, open.command)
        assertArrayEquals("exec:getprop\u0000".toByteArray(), open.payload)
        transport.incoming.send(
            AdbMessage(AdbCommand.OKAY, arg0 = 41, arg1 = open.arg0),
        )

        val stream = opening.await()
        assertEquals(open.arg0, stream.localId)
        assertEquals(41L, stream.remoteId)
        controller.close()
    }

    @Test
    fun `incoming WRTE is delivered and acknowledged with reversed stream ids`() = runTest {
        val transport = FakeAdbMessageTransport()
        val controller = controller(transport)
        controller.start()
        val stream = openStream(controller, transport, remoteId = 77)
        val payload = "ro.product.model=RockService".toByteArray()

        transport.incoming.send(
            AdbMessage(
                AdbCommand.WRTE,
                arg0 = stream.remoteId,
                arg1 = stream.localId,
                payload = payload,
            ),
        )

        assertArrayEquals(payload, stream.read())
        val acknowledgement = transport.sent.receive()
        assertEquals(AdbCommand.OKAY, acknowledgement.command)
        assertEquals(stream.localId, acknowledgement.arg0)
        assertEquals(stream.remoteId, acknowledgement.arg1)
        controller.close()
    }

    @Test
    fun `write chunks by negotiated maxdata and waits for OKAY after every WRTE`() = runTest {
        val transport = FakeAdbMessageTransport()
        val controller = controller(transport, maxDataBytes = 4)
        controller.start()
        val stream = openStream(controller, transport, remoteId = 52)
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)

        val writing = async { stream.write(payload) }
        val receivedChunks = mutableListOf<ByteArray>()
        repeat(3) {
            val write = transport.sent.receive()
            assertEquals(AdbCommand.WRTE, write.command)
            assertEquals(stream.localId, write.arg0)
            assertEquals(stream.remoteId, write.arg1)
            receivedChunks += write.payload
            transport.incoming.send(
                AdbMessage(AdbCommand.OKAY, arg0 = stream.remoteId, arg1 = stream.localId),
            )
        }
        writing.await()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), receivedChunks[0])
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), receivedChunks[1])
        assertArrayEquals(byteArrayOf(9), receivedChunks[2])
        controller.close()
    }

    @Test
    fun `remote CLSE closes stream and receives reciprocal CLSE`() = runTest {
        val transport = FakeAdbMessageTransport()
        val controller = controller(transport)
        controller.start()
        val stream = openStream(controller, transport, remoteId = 18)

        transport.incoming.send(
            AdbMessage(AdbCommand.CLSE, arg0 = stream.remoteId, arg1 = stream.localId),
        )

        val reciprocal = transport.sent.receive()
        assertEquals(AdbCommand.CLSE, reciprocal.command)
        assertEquals(stream.localId, reciprocal.arg0)
        assertEquals(stream.remoteId, reciprocal.arg1)
        assertNull(stream.read())
        controller.close()
    }

    @Test
    fun `peer can reject OPEN with CLSE before assigning remote id`() = runTest {
        val transport = FakeAdbMessageTransport()
        val controller = controller(transport)
        controller.start()

        val opening = async {
            runCatching { controller.open(AdbDiagnosticService.BATTERY) }.exceptionOrNull()
        }
        val open = transport.sent.receive()
        transport.incoming.send(
            AdbMessage(AdbCommand.CLSE, arg0 = 0, arg1 = open.arg0),
        )

        assertTrue(opening.await() is AdbServiceRejectedException)
        controller.close()
    }

    @Test
    fun `unknown stream traffic fails session closed and releases owned transport`() = runTest {
        val transport = FakeAdbMessageTransport()
        val controller = AdbSessionController(
            transport = transport,
            peer = peer(),
            scope = this,
            receivePollTimeoutMillis = 1_000,
            closeTransportOnClose = true,
        )
        controller.start()

        transport.incoming.send(
            AdbMessage(
                AdbCommand.WRTE,
                arg0 = 99,
                arg1 = 123,
                payload = byteArrayOf(1),
            ),
        )

        withTimeout(1_000) { transport.closed.await() }
        val error = runCatching {
            controller.open(AdbDiagnosticService.KERNEL)
        }.exceptionOrNull()
        assertTrue(error is AdbSessionException)
        controller.close()
    }

    private fun controller(
        transport: FakeAdbMessageTransport,
        maxDataBytes: Long = 4096,
    ): AdbSessionController = AdbSessionController(
        transport = transport,
        peer = peer(maxDataBytes),
        scope = this,
        receivePollTimeoutMillis = 1_000,
        closeTransportOnClose = false,
    )

    private fun peer(maxDataBytes: Long = 4096): AdbConnectedPeer = AdbConnectedPeer(
        protocolVersion = AdbProtocolCodec.DEFAULT_PROTOCOL_VERSION,
        maxDataBytes = maxDataBytes,
        banner = "device::ro.product.name=test;",
    )

    private suspend fun openStream(
        controller: AdbSessionController,
        transport: FakeAdbMessageTransport,
        remoteId: Long,
    ): AdbSessionStream {
        val opening = async { controller.open(AdbDiagnosticService.PROPERTIES) }
        val open = transport.sent.receive()
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
