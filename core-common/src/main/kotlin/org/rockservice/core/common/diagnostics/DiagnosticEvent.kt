package org.rockservice.core.common.diagnostics

/** Severity used by sanitized technical diagnostics exported by the application. */
enum class DiagnosticSeverity {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
}

/**
 * One sanitized diagnostic event.
 *
 * Callers must not expect raw sensitive values to survive recording. The recorder applies field
 * limits and redaction before an event is retained or exposed.
 */
data class DiagnosticEvent(
    val sequence: Long,
    val timestampEpochMillis: Long,
    val severity: DiagnosticSeverity,
    val component: String,
    val action: String,
    val message: String,
    val metadata: Map<String, String>,
)
