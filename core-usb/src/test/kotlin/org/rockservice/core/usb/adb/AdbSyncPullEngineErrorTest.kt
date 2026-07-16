package org.rockservice.core.usb.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbSyncPullEngineErrorTest {
    @Test
    fun `remote failure keeps full protocol message but bounds user-facing exception text`() {
        val remote = "x".repeat(4_096) + "\u0001tail"

        val error = AdbSyncRemoteFailureException(remote)

        assertEquals(remote, error.remoteMessage)
        val display = error.message.orEmpty()
        assertTrue(display.startsWith("ADB Sync remoto: "))
        assertTrue(display.endsWith("…"))
        assertTrue(display.length <= "ADB Sync remoto: ".length + 512)
        assertFalse('\u0001' in display)
    }

    @Test
    fun `blank remote failure uses stable generic message`() {
        val error = AdbSyncRemoteFailureException("   ")

        assertEquals("ADB Sync remoto retornou FAIL.", error.message)
    }
}
