package org.rockservice.core.usb.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdbRsaAuthTest {
    @Test
    fun `android public key record has expected binary layout`() {
        val pair = rsaKeyPair()
        val publicKey = pair.public as RSAPublicKey

        val encoded = AdbRsaAuth.encodeAndroidPublicKey(publicKey)
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(AdbRsaAuth.ANDROID_PUBLIC_KEY_BYTES, encoded.size)
        assertEquals(64, buffer.getInt(0))
        assertEquals(AdbRsaAuth.SUPPORTED_PUBLIC_EXPONENT.toInt(), buffer.getInt(520))

        val modulus = encoded.copyOfRange(8, 8 + AdbRsaAuth.RSA_BYTES).reversedArray().toUnsignedBigInteger()
        assertEquals(publicKey.modulus, modulus)

        val n0 = publicKey.modulus.and(BigInteger("FFFFFFFF", 16))
        val n0Inv = buffer.getInt(4).toLong() and 0xFFFF_FFFFL
        assertEquals(
            BigInteger("FFFFFFFF", 16),
            n0.multiply(BigInteger.valueOf(n0Inv)).and(BigInteger("FFFFFFFF", 16)),
        )

        val rr = encoded.copyOfRange(8 + AdbRsaAuth.RSA_BYTES, 8 + 2 * AdbRsaAuth.RSA_BYTES)
            .reversedArray()
            .toUnsignedBigInteger()
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(publicKey.modulus), rr)
    }

    @Test
    fun `public key record base64 decodes to adb structure and sanitized comment`() {
        val publicKey = rsaKeyPair().public as RSAPublicKey

        val record = AdbRsaAuth.encodePublicKeyRecord(publicKey, "  rockservice@test  ")
        val parts = record.split(' ', limit = 2)

        assertEquals(2, parts.size)
        assertEquals("rockservice@test", parts[1])
        assertArrayEquals(
            AdbRsaAuth.encodeAndroidPublicKey(publicKey),
            Base64.getDecoder().decode(parts[0]),
        )
    }

    @Test
    fun `token signature recovers exact adb sha1 digest info block`() {
        val pair = rsaKeyPair()
        val privateKey = pair.private as RSAPrivateKey
        val publicKey = pair.public as RSAPublicKey
        val token = ByteArray(AdbRsaAuth.AUTH_TOKEN_BYTES) { index -> (index * 7 + 3).toByte() }

        val signature = AdbRsaAuth.signToken(privateKey, token)

        assertEquals(AdbRsaAuth.RSA_BYTES, signature.size)
        val recovered = signature.toUnsignedBigInteger()
            .modPow(publicKey.publicExponent, publicKey.modulus)
            .toFixedBigEndian(AdbRsaAuth.RSA_BYTES)
        assertArrayEquals(expectedEncodedMessage(token), recovered)
    }

    @Test
    fun `rejects token with wrong size`() {
        val privateKey = rsaKeyPair().private as RSAPrivateKey

        expectIllegalArgument { AdbRsaAuth.signToken(privateKey, ByteArray(19)) }
        expectIllegalArgument { AdbRsaAuth.signToken(privateKey, ByteArray(21)) }
    }

    @Test
    fun `rejects rsa keys outside required size`() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(1024)
        val pair = generator.generateKeyPair()

        expectIllegalArgument { AdbRsaAuth.encodeAndroidPublicKey(pair.public as RSAPublicKey) }
        expectIllegalArgument {
            AdbRsaAuth.signToken(pair.private as RSAPrivateKey, ByteArray(AdbRsaAuth.AUTH_TOKEN_BYTES))
        }
    }

    @Test
    fun `rejects unsafe public key comments`() {
        val publicKey = rsaKeyPair().public as RSAPublicKey

        expectIllegalArgument { AdbRsaAuth.encodePublicKeyRecord(publicKey, "") }
        expectIllegalArgument { AdbRsaAuth.encodePublicKeyRecord(publicKey, "bad\u0000comment") }
        expectIllegalArgument { AdbRsaAuth.encodePublicKeyRecord(publicKey, "bad\ncomment") }
        expectIllegalArgument { AdbRsaAuth.encodePublicKeyRecord(publicKey, "x".repeat(129)) }
    }

    private fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").run {
        initialize(AdbRsaAuth.RSA_BITS)
        generateKeyPair()
    }

    private fun expectedEncodedMessage(token: ByteArray): ByteArray {
        val digestInfoPrefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E,
            0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14,
        )
        val digestInfo = digestInfoPrefix + token
        val paddingLength = AdbRsaAuth.RSA_BYTES - digestInfo.size - 3
        return ByteArray(AdbRsaAuth.RSA_BYTES).also { encoded ->
            encoded[0] = 0
            encoded[1] = 1
            encoded.fill(0xFF.toByte(), 2, 2 + paddingLength)
            encoded[2 + paddingLength] = 0
            digestInfo.copyInto(encoded, 3 + paddingLength)
        }
    }

    private fun ByteArray.toUnsignedBigInteger(): BigInteger = BigInteger(1, this)

    private fun BigInteger.toFixedBigEndian(size: Int): ByteArray {
        val raw = toByteArray().let { bytes ->
            if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }
        require(raw.size <= size)
        return ByteArray(size).also { output -> raw.copyInto(output, size - raw.size) }
    }

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException = try {
        block()
        fail("Expected IllegalArgumentException")
        error("unreachable")
    } catch (error: IllegalArgumentException) {
        error
    }
}
