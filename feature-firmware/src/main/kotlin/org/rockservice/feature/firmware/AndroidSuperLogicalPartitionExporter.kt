package org.rockservice.feature.firmware

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Deterministic result of exporting one validated liblp logical partition plan. */
data class AndroidSuperLogicalPartitionExportReport(
    val partitionName: String,
    val bytesWritten: Long,
    val outputSha256: String,
    val linearExtentCount: Int,
    val zeroExtentCount: Int,
)

/**
 * Streams one validated logical partition plan into a caller-owned destination.
 *
 * Every source stream returned by [openBlockDevice] is closed by this exporter. The destination
 * remains owned by the caller and is never closed here.
 */
class AndroidSuperLogicalPartitionExporter(
    private val bufferSizeBytes: Int = DEFAULT_BUFFER_SIZE_BYTES,
) {
    init {
        require(bufferSizeBytes in MINIMUM_BUFFER_SIZE_BYTES..MAXIMUM_BUFFER_SIZE_BYTES) {
            "bufferSizeBytes deve estar entre $MINIMUM_BUFFER_SIZE_BYTES e $MAXIMUM_BUFFER_SIZE_BYTES."
        }
    }

    /**
     * Exports exactly [plan.sizeBytes] bytes.
     *
     * [checkpoint] is invoked before potentially blocking operations and after every copied/written
     * chunk. A caller can throw from it to abort cooperatively. [onProgress] receives monotonically
     * increasing output byte counts.
     */
    fun export(
        plan: AndroidSuperLogicalPartitionPlan,
        openBlockDevice: (Int) -> InputStream,
        destination: OutputStream,
        checkpoint: () -> Unit = {},
        onProgress: (writtenBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): AndroidSuperLogicalPartitionExportReport {
        require(plan.name.isNotBlank()) { "O nome da partição lógica não pode ser vazio." }
        require(plan.sizeBytes >= 0L) { "O tamanho da partição lógica não pode ser negativo." }

        val plannedSize = plan.extents.fold(0L) { total, extent ->
            require(extent.lengthBytes >= 0L) { "Extent lógico possui tamanho negativo." }
            checkedAdd(total, extent.lengthBytes, "Tamanho total do plano ${plan.name}")
        }
        require(plannedSize == plan.sizeBytes) {
            "Plano ${plan.name} declara ${plan.sizeBytes} bytes, mas seus extents totalizam $plannedSize."
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val transferBuffer = ByteArray(bufferSizeBytes)
        val zeroBuffer = ByteArray(bufferSizeBytes)
        var writtenBytes = 0L
        var linearExtentCount = 0
        var zeroExtentCount = 0

        onProgress(0L, plan.sizeBytes)
        plan.extents.forEachIndexed { extentIndex, extent ->
            checkpoint()
            when (extent) {
                is AndroidSuperLogicalExtentPlan.Linear -> {
                    require(extent.blockDeviceIndex >= 0) {
                        "Extent LINEAR #$extentIndex possui índice de block device negativo."
                    }
                    require(extent.sourceOffsetBytes >= 0L) {
                        "Extent LINEAR #$extentIndex possui offset de origem negativo."
                    }
                    linearExtentCount += 1
                    val source = try {
                        openBlockDevice(extent.blockDeviceIndex)
                    } catch (error: IOException) {
                        throw IOException(
                            "Falha ao abrir block device ${extent.blockDeviceIndex} para o extent #$extentIndex de ${plan.name}.",
                            error,
                        )
                    }
                    source.use { input ->
                        skipExactly(
                            input = input,
                            byteCount = extent.sourceOffsetBytes,
                            scratch = transferBuffer,
                            checkpoint = checkpoint,
                            label = "offset do extent #$extentIndex de ${plan.name}",
                        )
                        copyExactly(
                            input = input,
                            output = destination,
                            byteCount = extent.lengthBytes,
                            buffer = transferBuffer,
                            digest = digest,
                            checkpoint = checkpoint,
                            onChunkWritten = { chunkBytes ->
                                writtenBytes = checkedAdd(
                                    writtenBytes,
                                    chunkBytes.toLong(),
                                    "Bytes exportados de ${plan.name}",
                                )
                                onProgress(writtenBytes, plan.sizeBytes)
                            },
                            label = "payload do extent #$extentIndex de ${plan.name}",
                        )
                    }
                }

                is AndroidSuperLogicalExtentPlan.Zero -> {
                    zeroExtentCount += 1
                    writeZeros(
                        output = destination,
                        byteCount = extent.lengthBytes,
                        zeroBuffer = zeroBuffer,
                        digest = digest,
                        checkpoint = checkpoint,
                        onChunkWritten = { chunkBytes ->
                            writtenBytes = checkedAdd(
                                writtenBytes,
                                chunkBytes.toLong(),
                                "Bytes exportados de ${plan.name}",
                            )
                            onProgress(writtenBytes, plan.sizeBytes)
                        },
                    )
                }
            }
        }

        require(writtenBytes == plan.sizeBytes) {
            "Exportação de ${plan.name} produziu $writtenBytes bytes; esperado: ${plan.sizeBytes}."
        }
        checkpoint()

        return AndroidSuperLogicalPartitionExportReport(
            partitionName = plan.name,
            bytesWritten = writtenBytes,
            outputSha256 = digest.digest().toHex(),
            linearExtentCount = linearExtentCount,
            zeroExtentCount = zeroExtentCount,
        )
    }

    private fun skipExactly(
        input: InputStream,
        byteCount: Long,
        scratch: ByteArray,
        checkpoint: () -> Unit,
        label: String,
    ) {
        var remaining = byteCount
        while (remaining > 0L) {
            checkpoint()
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }

            val requested = minOf(remaining, scratch.size.toLong()).toInt()
            val read = input.read(scratch, 0, requested)
            when {
                read < 0 -> throw IOException("Origem truncada ao avançar até $label.")
                read == 0 -> {
                    val single = input.read()
                    if (single < 0) throw IOException("Origem truncada ao avançar até $label.")
                    remaining -= 1L
                }
                else -> remaining -= read.toLong()
            }
        }
    }

    private fun copyExactly(
        input: InputStream,
        output: OutputStream,
        byteCount: Long,
        buffer: ByteArray,
        digest: MessageDigest,
        checkpoint: () -> Unit,
        onChunkWritten: (Int) -> Unit,
        label: String,
    ) {
        var remaining = byteCount
        while (remaining > 0L) {
            checkpoint()
            val requested = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, requested)
            when {
                read < 0 -> throw IOException("Origem truncada ao ler $label; faltam $remaining bytes.")
                read == 0 -> {
                    val single = input.read()
                    if (single < 0) throw IOException("Origem truncada ao ler $label; faltam $remaining bytes.")
                    output.write(single)
                    digest.update(single.toByte())
                    remaining -= 1L
                    onChunkWritten(1)
                }
                else -> {
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    remaining -= read.toLong()
                    onChunkWritten(read)
                }
            }
            checkpoint()
        }
    }

    private fun writeZeros(
        output: OutputStream,
        byteCount: Long,
        zeroBuffer: ByteArray,
        digest: MessageDigest,
        checkpoint: () -> Unit,
        onChunkWritten: (Int) -> Unit,
    ) {
        var remaining = byteCount
        while (remaining > 0L) {
            checkpoint()
            val count = minOf(remaining, zeroBuffer.size.toLong()).toInt()
            output.write(zeroBuffer, 0, count)
            digest.update(zeroBuffer, 0, count)
            remaining -= count.toLong()
            onChunkWritten(count)
            checkpoint()
        }
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE_BYTES = 64 * 1024
        const val MINIMUM_BUFFER_SIZE_BYTES = 512
        const val MAXIMUM_BUFFER_SIZE_BYTES = 1024 * 1024
    }
}

private fun checkedAdd(left: Long, right: Long, label: String): Long = try {
    Math.addExact(left, right)
} catch (_: ArithmeticException) {
    throw IllegalArgumentException("$label excede o intervalo suportado.")
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
