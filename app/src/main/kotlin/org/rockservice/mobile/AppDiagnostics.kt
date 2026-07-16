package org.rockservice.mobile

import org.rockservice.core.common.diagnostics.DiagnosticEventRecorder

/** Process-local sanitized diagnostics buffer. No event is persisted automatically. */
internal object AppDiagnostics {
    val recorder = DiagnosticEventRecorder(maxEvents = 1_000)
}
