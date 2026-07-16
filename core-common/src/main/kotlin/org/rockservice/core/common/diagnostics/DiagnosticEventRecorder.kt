package org.rockservice.core.common.diagnostics

import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Thread-safe bounded recorder for sanitized technical diagnostics.
 *
 * Events exist only in memory until a caller explicitly exports a snapshot. Sensitive metadata
 * fields are redacted before they enter the buffer.
 */
class DiagnosticEventRecorder(
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
) {
    private val lock = Any()
    private val buffer = ArrayDeque<DiagnosticEvent>(maxEvents)
    private val mutableEvents = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    private var nextSequence = 1L

    init {
        require(maxEvents in 1..MAX_ALLOWED_EVENTS) {
            "maxEvents must be between 1 and $MAX_ALLOWED_EVENTS."
        }
    }

    /** Current immutable event snapshot, suitable for presentation by Android UI. */
    val events = mutableEvents.asStateFlow()

    /** Records one event after applying length limits and metadata redaction. */
    fun record(
        severity: DiagnosticSeverity,
        component: String,
        action: String,
        message: String,
        metadata: Map<String, String> = emptyMap(),
        timestampEpochMillis: Long = System.currentTimeMillis(),
    ): DiagnosticEvent {
        require(timestampEpochMillis >= 0L) { "timestampEpochMillis must be non-negative." }

        return synchronized(lock) {
            val event = DiagnosticEvent(
                sequence = nextSequence++,
                timestampEpochMillis = timestampEpochMillis,
                severity = severity,
                component = component.sanitizedText(MAX_COMPONENT_LENGTH, DEFAULT_COMPONENT),
                action = action.sanitizedText(MAX_ACTION_LENGTH, DEFAULT_ACTION),
                message = message.sanitizedText(MAX_MESSAGE_LENGTH, DEFAULT_MESSAGE),
                metadata = sanitizeMetadata(metadata),
            )
            if (buffer.size == maxEvents) buffer.removeFirst()
            buffer.addLast(event)
            mutableEvents.value = buffer.toList()
            event
        }
    }

    /** Returns a stable immutable copy of the current in-memory buffer. */
    fun snapshot(): List<DiagnosticEvent> = synchronized(lock) { buffer.toList() }

    /** Clears all retained events without resetting the monotonic sequence counter. */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            mutableEvents.value = emptyList()
        }
    }

    /** Serializes the current sanitized snapshot as one valid JSON object per line. */
    fun exportJsonLines(events: List<DiagnosticEvent> = snapshot()): String =
        events.joinToString(separator = "\n", postfix = if (events.isEmpty()) "" else "\n") { event ->
            buildJsonObject {
                put("sequence", event.sequence)
                put("timestampEpochMillis", event.timestampEpochMillis)
                put("severity", event.severity.name)
                put("component", event.component)
                put("action", event.action)
                put("message", event.message)
                putJsonObject("metadata") {
                    event.metadata.toSortedMap().forEach { (key, value) -> put(key, value) }
                }
            }.toString()
        }

    private fun sanitizeMetadata(metadata: Map<String, String>): Map<String, String> =
        metadata.entries
            .sortedBy { entry -> entry.key }
            .take(MAX_METADATA_ENTRIES)
            .associate { (rawKey, rawValue) ->
                val key = rawKey.sanitizedText(MAX_METADATA_KEY_LENGTH, DEFAULT_METADATA_KEY)
                val value = if (isSensitiveKey(key)) {
                    REDACTED_VALUE
                } else {
                    rawValue.sanitizedText(MAX_METADATA_VALUE_LENGTH, DEFAULT_METADATA_VALUE)
                }
                key to value
            }
            .toSortedMap()

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase().filter(Char::isLetterOrDigit)
        return SENSITIVE_KEY_FRAGMENTS.any(normalized::contains)
    }

    private fun String.sanitizedText(maxLength: Int, fallback: String): String =
        trim()
            .replace(Regex("\\s+"), " ")
            .take(maxLength)
            .ifBlank { fallback }

    private companion object {
        const val DEFAULT_MAX_EVENTS = 500
        const val MAX_ALLOWED_EVENTS = 5_000
        const val MAX_COMPONENT_LENGTH = 80
        const val MAX_ACTION_LENGTH = 120
        const val MAX_MESSAGE_LENGTH = 500
        const val MAX_METADATA_ENTRIES = 20
        const val MAX_METADATA_KEY_LENGTH = 64
        const val MAX_METADATA_VALUE_LENGTH = 240
        const val DEFAULT_COMPONENT = "unknown"
        const val DEFAULT_ACTION = "unknown"
        const val DEFAULT_MESSAGE = "sem mensagem"
        const val DEFAULT_METADATA_KEY = "field"
        const val DEFAULT_METADATA_VALUE = "-"
        const val REDACTED_VALUE = "[redacted]"
        val SENSITIVE_KEY_FRAGMENTS = setOf(
            "apikey",
            "authorization",
            "cookie",
            "credential",
            "password",
            "privatekey",
            "secret",
            "serial",
            "sessionid",
            "token",
            "transportid",
        )
    }
}
