package org.rockservice.core.security

import org.junit.Assert.assertTrue
import org.junit.Test
import org.rockservice.core.common.model.RiskLevel

class DestructiveOperationPolicyTest {
    private val policy = DestructiveOperationPolicy()

    @Test
    fun `critical operation is denied without typed confirmation`() {
        val result = policy.evaluate(
            OperationRequest("flash", "RK3566 board", RiskLevel.CRITICAL, "OK")
        )
        assertTrue(result is PolicyDecision.Denied)
    }

    @Test
    fun `critical operation is allowed with exact confirmation`() {
        val result = policy.evaluate(
            OperationRequest(
                "flash",
                "RK3566 board",
                RiskLevel.CRITICAL,
                DestructiveOperationPolicy.REQUIRED_CONFIRMATION,
            )
        )
        assertTrue(result is PolicyDecision.Allowed)
    }
}
