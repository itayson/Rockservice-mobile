package org.rockservice.feature.firmware

/** One titled block of human-readable diagnostic information. */
data class FirmwareLabSection(
    val title: String,
    val lines: List<String>,
)

/** Complete read-only firmware laboratory result suitable for UI rendering and text export. */
data class FirmwareLabReport(
    val displayName: String,
    val declaredSizeBytes: Long?,
    val detectedFormat: FirmwareFormat,
    val sha256: String,
    val bytesRead: Long,
    val warnings: List<String>,
    val sections: List<FirmwareLabSection>,
) {
    /** Suggested filename for exporting this report without exposing the original document URI. */
    val suggestedReportFileName: String
        get() {
            val base = displayName
                .substringBeforeLast('.', missingDelimiterValue = displayName)
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trim('_')
                .ifBlank { "firmware" }
            return "$base-rockservice-report.txt"
        }

    /** Renders the diagnostic report as plain text. */
    fun toPlainText(): String = buildString {
        appendLine("RockService Mobile - Relatorio de Firmware")
        appendLine("========================================")
        appendLine()
        sections.forEachIndexed { index, section ->
            appendLine(section.title)
            appendLine("-".repeat(section.title.length.coerceAtLeast(3)))
            section.lines.forEach(::appendLine)
            if (index != sections.lastIndex) appendLine()
        }
    }
}
