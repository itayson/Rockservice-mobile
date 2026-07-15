package org.rockservice.core.common.security

import org.junit.Test

class FeatureFlagsTest {
    @Test(expected = IllegalArgumentException::class)
    fun `rejects experimental usb write in bootstrap`() {
        FeatureFlags(experimentalUsbWrite = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects root integration in bootstrap`() {
        FeatureFlags(rootIntegration = true)
    }
}
