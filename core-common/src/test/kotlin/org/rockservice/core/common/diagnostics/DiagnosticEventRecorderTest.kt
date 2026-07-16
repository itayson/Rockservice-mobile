package org.rockservice.core.common.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticEventRecorderTest {
    @Test
    fun `bounded recorder discards oldest events`() {
        val recorder = DiagnosticEventRecorder(maxEvents = 2)

        recorder.record(DiagnosticSeverity.INFO, "usb", "one", "first", timestampEpochMillis = 1)
        recorder.record(DiagnosticSeverity.INFO, "usb", "two", "second", timestampEpochMillis = 2)
        recorder.record(DiagnosticSeverity.WARNING, "usb", "three", "third", timestampEpochMillis = 3)

        val snapshot = recorder.snapshot()
        assertEquals(listOf(2L, 3L), snapshot.map(DiagnosticEvent::sequence))
        assertEquals(listOf("second", "third"), snapshot.map(DiagnosticEvent::message))
    }

    @Test
    fun `sensitive metadata is redacted before retention`() {
        val recorder = DiagnosticEventRecorder()

        val event = recorder.record(
            severity = DiagnosticSeverity.ERROR,
            component = " usb \n host ",
            action = "selection",
            message = " target changed\tunexpectedly ",
            metadata = mapOf(
                "transportId" to "/dev/bus/usb/001/002",
                "deviceSerial" to "ABC123",
                "access_token" to "top-secret-token",
                "vendorId" to "2207",
            ),
            timestampEpochMillis = 10,
        )

        assertEquals("usb host", event.component)
        assertEquals("target changed unexpectedly", event.message)
        assertEquals("[redacted]", event.metadata.getValue("transportId"))
        assertEquals("[redacted]", event.metadata.getValue("deviceSerial"))
        assertEquals("[redacted]", event.metadata.getValue("access_token"))
        assertEquals("2207", event.metadata.getValue("vendorId"))
        assertFalse(recorder.exportJsonLines().contains("/dev/bus/usb"))
        assertFalse(recorder.exportJsonLines().contains("ABC123"))
        assertFalse(recorder.exportJsonLines().contains("top-secret-token"))
    }

    @Test
    fun `json lines export is valid deterministic structured data`() {
        val recorder = DiagnosticEventRecorder()
        recorder.record(
            severity = DiagnosticSeverity.INFO,
            component = "firmware",
            action = "analyze",
            message = "completed",
            metadata = linkedMapOf("zeta" to "2", "alpha" to "1"),
            timestampEpochMillis = 42,
        )

        val lines = recorder.exportJsonLines().trimEnd().lines()
        assertEquals(1, lines.size)
        val json = Json.parseToJsonElement(lines.single()).jsonObject

        assertEquals("1", json.getValue("sequence").jsonPrimitive.content)
        assertEquals("42", json.getValue("timestampEpochMillis").jsonPrimitive.content)
        assertEquals("INFO", json.getValue("severity").jsonPrimitive.content)
        assertEquals("firmware", json.getValue("component").jsonPrimitive.content)
        assertEquals("analyze", json.getValue("action").jsonPrimitive.content)
        assertTrue(lines.single().indexOf("alpha") < lines.single().indexOf("zeta"))
    }

    @Test
    fun `clear removes retained events but sequence remains monotonic`() {
        val recorder = DiagnosticEventRecorder()
        recorder.record(DiagnosticSeverity.INFO, "app", "start", "one", timestampEpochMillis = 1)
        recorder.clear()
        val event = recorder.record(DiagnosticSeverity.INFO, "app", "restart", "two", timestampEpochMillis = 2)

        assertEquals(2L, event.sequence)
        assertEquals(1, recorder.snapshot().size)
    }
}
