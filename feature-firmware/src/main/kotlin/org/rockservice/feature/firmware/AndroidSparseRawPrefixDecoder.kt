package org.rockservice.feature.firmware

import java.io.InputStream
import java.io.OutputStream

/**
 * Decodes exactly a bounded raw prefix from an Android Sparse stream.
 *
 * Chunk semantics are delegated to AndroidSparseImageExpander. Expansion is intentionally stopped
 * as soon as the requested raw prefix is captured, so the full image is never materialized.
 */
internal class AndroidSparseRawPrefixDecoder(
    private val maximumPrefixBytes: Int = DEFAULT_MAXIMUM_PREFIX_BYTES,
) {
    init {
        require(maximumPrefixBytes > 0) { "maximumPrefixBytes deve ser maior que zero." }
    }

    fun decodeExactly(source: InputStream, prefixBytes: Int): ByteArray {
        require(prefixBytes in 1..maximumPrefixBytes) {
            "Prefixo raw solicitado deve estar entre 1 e $maximumPrefixBytes bytes."
        }

        val capture = PrefixCaptureOutputStream(prefixBytes)
        try {
            AndroidSparseImageExpander().expand(source, capture)
        } catch (_: PrefixCompleteException) {
            return capture.requireComplete()
        }
        return capture.requireComplete()
    }

    private class PrefixCaptureOutputStream(
        private val expectedBytes: Int,
    ) : OutputStream() {
        private val bytes = ByteArray(expectedBytes)
        private var size = 0

        override fun write(value: Int) {
            if (size == expectedBytes) throw PrefixCompleteException()
            bytes[size++] = value.toByte()
            if (size == expectedBytes) throw PrefixCompleteException()
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
                "Faixa de escrita inválida no decoder de prefixo sparse."
            }
            if (size == expectedBytes) throw PrefixCompleteException()
            val remaining = expectedBytes - size
            val copyLength = minOf(length, remaining)
            buffer.copyInto(bytes, destinationOffset = size, startIndex = offset, endIndex = offset + copyLength)
            size += copyLength
            if (size == expectedBytes) throw PrefixCompleteException()
        }

        fun requireComplete(): ByteArray {
            require(size == expectedBytes) {
                "Android Sparse expandiu somente $size bytes; prefixo solicitado: $expectedBytes."
            }
            return bytes.copyOf()
        }
    }

    private class PrefixCompleteException : RuntimeException(null, null, false, false)

    private companion object {
        const val DEFAULT_MAXIMUM_PREFIX_BYTES = 128 * 1024 * 1024
    }
}
