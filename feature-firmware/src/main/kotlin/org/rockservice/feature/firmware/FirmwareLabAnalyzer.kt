package org.rockservice.feature.firmware

import java.io.InputStream

/**
 * Orchestrates the defensive firmware parsers over a reopenable input source.
 *
 * The source is reopened for each parser pass, so complete firmware images are never buffered in
 * memory by this service. No extraction, mounting or mutation is performed.
 */
class FirmwareLabAnalyzer(
    private val firmwareAnalyzer: FirmwareAnalyzer = FirmwareAnalyzer(),
    private val sparseParser: AndroidSparseImageParser = AndroidSparseImageParser(),
    private val bootParser: AndroidBootImageParser = AndroidBootImageParser(),
    private val superParser: AndroidSuperMetadataParser = AndroidSuperMetadataParser(),
    private val maximumListedEntries: Int = DEFAULT_MAXIMUM_LISTED_ENTRIES,
) {
    init {
        require(maximumListedEntries > 0) { "maximumListedEntries deve ser maior que zero." }
    }

    /** Analyzes one document and returns a bounded diagnostic report. */
    fun analyze(
        displayName: String,
        declaredSizeBytes: Long?,
        openStream: () -> InputStream,
    ): FirmwareLabReport {
        require(displayName.isNotBlank()) { "O nome do documento não pode ser vazio." }
        require(declaredSizeBytes == null || declaredSizeBytes >= 0L) {
            "O tamanho declarado do documento não pode ser negativo."
        }

        val baseAnalysis = openStream().use(firmwareAnalyzer::analyze)
        val sections = mutableListOf(
            FirmwareLabReportSections.general(displayName, declaredSizeBytes, baseAnalysis),
        )

        when (baseAnalysis.format) {
            FirmwareFormat.ANDROID_SPARSE -> {
                val metadata = openStream().use(sparseParser::parse)
                sections += FirmwareLabReportSections.sparse(metadata, maximumListedEntries)
            }

            FirmwareFormat.ANDROID_BOOT_IMAGE -> {
                val metadata = openStream().use(bootParser::parse)
                sections += FirmwareLabReportSections.boot(metadata)
            }

            FirmwareFormat.ANDROID_SUPER_RAW -> {
                val metadata = parseSuperWithMetadataFallback(openStream)
                sections += FirmwareLabReportSections.superImage(metadata, maximumListedEntries)
            }

            FirmwareFormat.ZIP,
            FirmwareFormat.ELF,
            FirmwareFormat.ISO_9660,
            FirmwareFormat.UNKNOWN,
            -> sections += FirmwareLabSection(
                title = "Analise estrutural especializada",
                lines = listOf(
                    "Nenhum parser estrutural adicional esta habilitado para este formato nesta versao.",
                    "O arquivo nao foi extraido, montado ou modificado.",
                ),
            )
        }

        return FirmwareLabReport(
            displayName = displayName,
            declaredSizeBytes = declaredSizeBytes,
            detectedFormat = baseAnalysis.format,
            sha256 = baseAnalysis.sha256,
            bytesRead = baseAnalysis.bytesRead,
            warnings = baseAnalysis.warnings,
            sections = sections,
        )
    }

    private fun parseSuperWithMetadataFallback(openStream: () -> InputStream): AndroidSuperMetadata {
        try {
            return openStream().use { source ->
                superParser.parse(source, metadataCopy = AndroidSuperMetadataCopy.PRIMARY)
            }
        } catch (primaryError: IllegalArgumentException) {
            try {
                return openStream().use { source ->
                    superParser.parse(source, metadataCopy = AndroidSuperMetadataCopy.BACKUP)
                }
            } catch (backupError: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Metadata liblp primaria e backup do slot 0 sao invalidas. " +
                        "Primaria: ${primaryError.message}; backup: ${backupError.message}",
                    backupError,
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_MAXIMUM_LISTED_ENTRIES = 200
    }
}
