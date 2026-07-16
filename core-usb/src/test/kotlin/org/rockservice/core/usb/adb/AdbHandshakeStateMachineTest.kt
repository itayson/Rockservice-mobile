package org.rockservice.core.usb.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdbHandshakeStateMachineTest {
    @Test
    fun `start emits one CNXN and direct peer CNXN completes handshake`() {
        val identity = FakeIdentity()
        val machine = AdbHandshakeStateMachine(identity)

        val start = machine.start()
        val connected = machine.receive(peerConnection())

        assertEquals(AdbCommand.CNXN, start.outbound?.command)
        assertEquals(AdbHandshakeState.AwaitingConnectionOrAuth, start.state)
        assertTrue(connected.state is AdbHandshakeState.Connected)
        assertNull(connected.outbound)
        val peer = (connected.state as AdbHandshakeState.Connected).peer
        assertEquals("device::ro.product.name=test;", peer.banner)
        assertEquals(4096L, peer.maxDataBytes)
        assertEquals(0, identity.signCalls)
    }

    @Test
    fun `first auth token sends signature and second sends public key`() {
        val identity = FakeIdentity()
        val machine = AdbHandshakeStateMachine(identity)
        machine.start()
        val firstToken = ByteArray(20) { 1 }
        val secondToken = ByteArray(20) { 2 }

        val signature = machine.receive(authToken(firstToken))
        val publicKey = machine.receive(authToken(secondToken))

        assertEquals(AdbHandshakeState.SignatureSent, signature.state)
        assertEquals(AdbCommand.AUTH, signature.outbound?.command)
        assertEquals(AdbAuthType.SIGNATURE.wireValue, signature.outbound?.arg0)
        assertArrayEquals(ByteArray(AdbRsaAuth.RSA_BYTES) { 0x5A }, signature.outbound?.payload)
        assertArrayEquals(firstToken, identity.lastToken)

        assertEquals(AdbHandshakeState.PublicKeySent, publicKey.state)
        assertEquals(AdbAuthType.RSA_PUBLIC_KEY.wireValue, publicKey.outbound?.arg0)
        assertEquals(0, publicKey.outbound?.payload?.last()?.toInt())
        assertEquals(1, identity.signCalls)
        assertEquals(1, identity.publicKeyCalls)
    }

    @Test
    fun `connection after signature completes handshake`() {
        val machine = AdbHandshakeStateMachine(FakeIdentity())
        machine.start()
        machine.receive(authToken(ByteArray(20)))

        val result = machine.receive(peerConnection())

        assertTrue(result.state is AdbHandshakeState.Connected)
    }

    @Test
    fun `third token after public key fails closed`() {
        val machine = AdbHandshakeStateMachine(FakeIdentity())
        machine.start()
        machine.receive(authToken(ByteArray(20) { 1 }))
        machine.receive(authToken(ByteArray(20) { 2 }))

        val result = machine.receive(authToken(ByteArray(20) { 3 }))

        assertTrue(result.state is AdbHandshakeState.Failed)
        assertNull(result.outbound)
    }

    @Test
    fun `unexpected command during handshake fails closed`() {
        val machine = AdbHandshakeStateMachine(FakeIdentity())
        machine.start()

        val result = machine.receive(AdbMessage(AdbCommand.OKAY, 1, 2))

        assertTrue(result.state is AdbHandshakeState.Failed)
    }

    @Test
    fun `rejects malformed auth token before invoking identity`() {
        val identity = FakeIdentity()
        val machine = AdbHandshakeStateMachine(identity)
        machine.start()

        expectIllegalArgument {
            machine.receive(
                AdbMessage(
                    command = AdbCommand.AUTH,
                    arg0 = AdbAuthType.TOKEN.wireValue,
                    arg1 = 0,
                    payload = ByteArray(19),
                ),
            )
        }

        assertEquals(0, identity.signCalls)
    }

    @Test
    fun `rejects malformed peer connection fields`() {
        val machine = AdbHandshakeStateMachine(FakeIdentity())
        machine.start()

        expectIllegalArgument {
            machine.receive(
                AdbMessage(
                    command = AdbCommand.CNXN,
                    arg0 = 0x01000001,
                    arg1 = 4096,
                    payload = "device::missing-nul".toByteArray(),
                ),
            )
        }
    }

    @Test
    fun `cannot start twice or receive before start`() {
        val machine = AdbHandshakeStateMachine(FakeIdentity())

        expectIllegalArgument { machine.receive(peerConnection()) }
        machine.start()
        expectIllegalArgument { machine.start() }
    }

    private fun peerConnection(): AdbMessage = AdbMessage(
        command = AdbCommand.CNXN,
        arg0 = 0x01000001,
        arg1 = 4096,
        payload = "device::ro.product.name=test;\u0000".toByteArray(),
    )

    private fun authToken(token: ByteArray): AdbMessage = AdbMessage(
        command = AdbCommand.AUTH,
        arg0 = AdbAuthType.TOKEN.wireValue,
        arg1 = 0,
        payload = token,
    )

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException = try {
        block()
        fail("Expected IllegalArgumentException")
        error("unreachable")
    } catch (error: IllegalArgumentException) {
        error
    }

    private class FakeIdentity : AdbHandshakeIdentity {
        var signCalls = 0
        var publicKeyCalls = 0
        var lastToken: ByteArray? = null

        override fun signToken(token: ByteArray): ByteArray {
            signCalls += 1
            lastToken = token.copyOf()
            return ByteArray(AdbRsaAuth.RSA_BYTES) { 0x5A }
        }

        override fun publicKeyRecord(): String {
            publicKeyCalls += 1
            return "AAAA rockservice@test"
        }
    }
}
