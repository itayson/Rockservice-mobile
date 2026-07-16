package org.rockservice.core.usb.adb

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbSyncReadonlyClientTest {
    @Test
    fun `openSync sends only the dedicated sync service name`() = runTest {
        val transport = FakeAdbMessageTransport()
        val controller = controller(transport)
        controller.start()

        val opening = async { controller.openSync() }
        val open = transport.sent.receive()

        assertEquals(AdbCommand.OPEN, open.command)
        assertArrayEquals("sync:\u0000".toByteArray(), open.payload)
        transport.incoming.send(
            AdbMessage(AdbCommand.OKAY, arg0 = 41, arg1 = open.arg0),
        )
        val stream = opening.await()
        assertEquals(41L, stream.remoteId)
        controller.close()
    }

    @Test
    fun `client performs OPEN RECV DATA DONE QUIT and closes dedicated stream`() = runTest {
        val transport = FakeAdbMessageTransport()
        val controller = controller(transport)
        controller.start()
        val client = AdbSyncReadonlyClient(controller)
        val sink = ByteArrayOutputStream()

        val pulling = async {
            client.pull(
                remotePath = "/sdcard/test.bin",
                timeoutMillis = 5_000,
                onData = { bytes -> sink.write(bytes) },
            )
        }

        val open = transport.sent.receive()
        assertEquals(AdbCommand.OPEN, open.command)
        assertArrayEquals("sync:\u0000".toByteArray(), open.payload)
        val localId = open.arg0
        val remoteId = 77L
        transport.incoming.send(
            AdbMessage(AdbCommand.OKAY, arg0 = remoteId, arg1 = localId),
        )

        val receiveRequest = transport.sent.receive()
        assertEquals(AdbCommand.WRTE, receiveRequest.command)
        assertArrayEquals(
            AdbSyncPullCodec.encodeReceiveRequest("/sdcard/test.bin"),
            receiveRequest.payload,
        )
        transport.incoming.send(
            AdbMessage(AdbCommand.OKAY, arg0 = remoteId, arg1 = localId),
        )

        val syncPayload = syncFrame("DATA", "payload".toByteArray()) + syncHeader("DONE", 0)
        transport.incoming.send(
            AdbMessage(
                AdbCommand.WRTE,
                arg0 = remoteId,
                arg1 = localId,
                payload = syncPayload,
            ),
        )

        var sawInboundAck = false
        var sawQuit = false
        repeat(2) {
            when (val outbound = transport.sent.receive()) {
                is AdbMessage -> when (outbound.command) {
                    AdbCommand.OKAY -> {
                        assertEquals(localId, outbound.arg0)
                        assertEquals(remoteId, outbound.arg1)
                        sawInboundAck = true
                    }
                    AdbCommand.WRTE -> {
                        assertArrayEquals(AdbSyncPullCodec.encodeQuitRequest(), outbound.payload)
                        sawQuit = true
                    }
                    else -> error("Unexpected outbound message while finishing pull: ${outbound.command}")
                }
            }
        }
        assertTrue(sawInboundAck)
        assertTrue(sawQuit)

        transport.incoming.send(
            AdbMessage(AdbCommand.OKAY, arg0 = remoteId, arg1 = localId),
        )
        transport.incoming.send(
            AdbMessage(AdbCommand.CLSE, arg0 = remoteId, arg1 = localId),
        )
        val close = transport.sent.receive()
        assertEquals(AdbCommand.CLSE, close.command)
        assertEquals(localId, close.arg0)
        assertEquals(remoteId, close.arg1)

        val result = pulling.await()
        assertArrayEquals("payload".toByteArray(), sink.toByteArray())
        assertEquals(7L, result.byteCount)
        assertEquals(
            "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5",
            result.sha256,
        )
        controller.close()
    }

    @Test
    fun `invalid request is rejected before opening sync service`() = runTest {
        val transport = FakeAdbMessageTransport()
        val controller = controller(transport)
        controller.start()
        val client = AdbSyncReadonlyClient(controller)

        val error = runCatching {
            client.pull(remotePath = "", onData = {})
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(transport.sent.tryReceive().isFailure)
        controller.close()
    }

    @Test
    fun `peer rejection of sync OPEN propagates before any RECV write`() = runTest {
        val transport = FakeAdbMessageTransport()
        val controller = controller(transport)
        controller.start()
        val client = AdbSyncReadonlyClient(controller)

        val pulling = async {
            runCatching {
                client.pull(remotePath = "/file", onData = {})
            }.exceptionOrNull()
        }
        val open = transport.sent.receive()
        transport.incoming.send(
            AdbMessage(AdbCommand.CLSE, arg0 = 0, arg1 = open.arg0),
        )

        assertTrue(pulling.await() is AdbServiceRejectedException)
        assertTrue(transport.sent.tryReceive().isFailure)
        controller.close()
    }

    @Test
    fun `client timeout includes time spent waiting for sync OPEN`() = runTest {
        val transport = FakeAdbMessageTransport()
        val controller = controller(transport)
        controller.start()
        val client = AdbSyncReadonlyClient(controller)

        val pulling = async {
            runCatching {
                client.pull(
                    remotePath = "/slow",
                    timeoutMillis = 100,
                    onData = {},
                )
            }.exceptionOrNull()
        }
        val open = transport.sent.receive()
        assertEquals(AdbCommand.OPEN, open.command)

        assertTrue(pulling.await() is TimeoutCancellationException)
        controller.close()
    }

    private fun CoroutineScope.controller(
        transport: FakeAdbMessageTransport,
    ): AdbSessionController = AdbSessionController(
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

    private fun syncFrame(command: String, payload: ByteArray): ByteArray =
        syncHeader(command, payload.size) + payload

    private fun syncHeader(command: String, length: Int): ByteArray =
        ByteArray(AdbSyncPullCodec.HEADER_SIZE_BYTES).also { header ->
            ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0, AdbSyncPullCodec.commandId(command))
                putInt(4, length)
            }
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
