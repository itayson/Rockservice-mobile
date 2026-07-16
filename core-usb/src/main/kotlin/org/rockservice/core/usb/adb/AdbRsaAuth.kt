package org.rockservice.core.usb.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/** Encodes and signs the RSA material required by the classic ADB AUTH handshake. */
object AdbRsaAuth {
    const val RSA_BITS = 2048
    const val RSA_BYTES = RSA_BITS / 8
    const val ANDROID_PUBLIC_KEY_BYTES = 524
    const val AUTH_TOKEN_BYTES = 20
    const val SUPPORTED_PUBLIC_EXPONENT = 65537L

    /** Encodes the binary Android/ADB public-key structure used before Base64 wrapping. */
    fun encodeAndroidPublicKey(publicKey: RSAPublicKey): ByteArray {
        validatePublicKey(publicKey)
        val modulus = publicKey.modulus
        val radix32 = BigInteger.ONE.shiftLeft(32)
        val n0 = modulus.and(radix32.subtract(BigInteger.ONE))
        val n0Inverse = n0.modInverse(radix32)
        val n0Inv = radix32.subtract(n0Inverse).and(radix32.subtract(BigInteger.ONE))
        val rr = BigInteger.ONE.shiftLeft(RSA_BITS * 2).mod(modulus)

        return ByteArray(ANDROID_PUBLIC_KEY_BYTES).also { encoded ->
            val header = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(0, RSA_BITS / 32)
            header.putInt(4, n0Inv.toLong().toInt())
            modulus.toFixedLittleEndian(RSA_BYTES).copyInto(encoded, destinationOffset = 8)
            rr.toFixedLittleEndian(RSA_BYTES).copyInto(encoded, destinationOffset = 8 + RSA_BYTES)
            header.putInt(8 + (2 * RSA_BYTES), SUPPORTED_PUBLIC_EXPONENT.toInt())
        }
    }

    /** Encodes the Base64 public-key record sent through AUTH RSA_PUBLIC_KEY. */
    fun encodePublicKeyRecord(
        publicKey: RSAPublicKey,
        comment: String = DEFAULT_COMMENT,
    ): String {
        val safeComment = validateComment(comment)
        return encodeBase64(encodeAndroidPublicKey(publicKey)) + " " + safeComment
    }

    /**
     * Signs exactly one 20-byte ADB AUTH token as a precomputed SHA-1 digest.
     *
     * ADB expects PKCS#1 v1.5 DigestInfo(SHA-1, token) rather than hashing the token a second time.
     */
    fun signToken(
        privateKey: RSAPrivateKey,
        token: ByteArray,
    ): ByteArray {
        validatePrivateKey(privateKey)
        require(token.size == AUTH_TOKEN_BYTES) {
            "Token AUTH ADB deve conter exatamente $AUTH_TOKEN_BYTES bytes."
        }

        val digestInfo = SHA1_DIGEST_INFO_PREFIX + token
        val paddingLength = RSA_BYTES - digestInfo.size - 3
        require(paddingLength >= 8) { "Bloco PKCS#1 v1.5 ADB nao possui padding suficiente." }

        val encodedMessage = ByteArray(RSA_BYTES)
        encodedMessage[0] = 0
        encodedMessage[1] = 1
        encodedMessage.fill(0xFF.toByte(), fromIndex = 2, toIndex = 2 + paddingLength)
        encodedMessage[2 + paddingLength] = 0
        digestInfo.copyInto(encodedMessage, destinationOffset = 3 + paddingLength)

        val representative = BigInteger(1, encodedMessage)
        require(representative < privateKey.modulus) {
            "Bloco AUTH ADB excede o modulo RSA."
        }
        return representative
            .modPow(privateKey.privateExponent, privateKey.modulus)
            .toFixedBigEndian(RSA_BYTES)
    }

    private fun validatePublicKey(publicKey: RSAPublicKey) {
        require(publicKey.modulus.bitLength() == RSA_BITS) {
            "ADB AUTH exige modulo RSA de exatamente $RSA_BITS bits."
        }
        require(publicKey.publicExponent == BigInteger.valueOf(SUPPORTED_PUBLIC_EXPONENT)) {
            "ADB AUTH suporta expoente publico $SUPPORTED_PUBLIC_EXPONENT neste gate."
        }
        require(publicKey.modulus.testBit(0)) { "Modulo RSA ADB deve ser impar." }
    }

    private fun validatePrivateKey(privateKey: RSAPrivateKey) {
        require(privateKey.modulus.bitLength() == RSA_BITS) {
            "ADB AUTH exige chave privada RSA de exatamente $RSA_BITS bits."
        }
        require(privateKey.privateExponent.signum() > 0) {
            "Expoente privado RSA ADB deve ser positivo."
        }
    }

    private fun validateComment(comment: String): String {
        val normalized = comment.trim()
        require(normalized.isNotEmpty()) { "Comentario da chave publica ADB nao pode ser vazio." }
        require(normalized.length <= MAXIMUM_COMMENT_LENGTH) {
            "Comentario da chave publica ADB excede $MAXIMUM_COMMENT_LENGTH caracteres."
        }
        require(normalized.none { character -> character == '\u0000' || character == '\n' || character == '\r' }) {
            "Comentario da chave publica ADB nao pode conter NUL ou quebra de linha."
        }
        return normalized
    }

    private fun BigInteger.toFixedLittleEndian(size: Int): ByteArray =
        toFixedBigEndian(size).reversedArray()

    private fun BigInteger.toFixedBigEndian(size: Int): ByteArray {
        require(signum() >= 0) { "Inteiro RSA nao pode ser negativo." }
        val raw = toByteArray().let { bytes ->
            if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }
        require(raw.size <= size) { "Inteiro RSA excede $size bytes." }
        return ByteArray(size).also { output ->
            raw.copyInto(output, destinationOffset = size - raw.size)
        }
    }

    private fun encodeBase64(bytes: ByteArray): String {
        val output = StringBuilder(((bytes.size + 2) / 3) * 4)
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = bytes.getOrNull(index + 1)?.toInt()?.and(0xFF)
            val b2 = bytes.getOrNull(index + 2)?.toInt()?.and(0xFF)

            output.append(BASE64_ALPHABET[b0 ushr 2])
            output.append(BASE64_ALPHABET[((b0 and 0x03) shl 4) or ((b1 ?: 0) ushr 4)])
            output.append(
                if (b1 == null) '=' else BASE64_ALPHABET[((b1 and 0x0F) shl 2) or ((b2 ?: 0) ushr 6)],
            )
            output.append(if (b2 == null) '=' else BASE64_ALPHABET[b2 and 0x3F])
            index += 3
        }
        return output.toString()
    }

    private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E,
        0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14,
    )
    private const val DEFAULT_COMMENT = "rockservice@android"
    private const val MAXIMUM_COMMENT_LENGTH = 128
    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
}
