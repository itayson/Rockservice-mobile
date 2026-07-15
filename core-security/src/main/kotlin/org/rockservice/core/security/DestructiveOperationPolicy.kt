package org.rockservice.core.security

import org.rockservice.core.common.model.RiskLevel

data class OperationRequest(
    val operationId: String,
    val targetDisplayName: String,
    val riskLevel: RiskLevel,
    val typedConfirmation: String?,
)

sealed interface PolicyDecision {
    data object Allowed : PolicyDecision
    data class Denied(val reason: String) : PolicyDecision
}

class DestructiveOperationPolicy {
    companion object {
        const val REQUIRED_CONFIRMATION = "GRAVAR NO DISPOSITIVO SELECIONADO"
    }

    fun evaluate(request: OperationRequest): PolicyDecision {
        if (request.riskLevel < RiskLevel.HIGH) return PolicyDecision.Allowed
        if (request.typedConfirmation != REQUIRED_CONFIRMATION) {
            return PolicyDecision.Denied("A confirmação textual obrigatória não foi fornecida.")
        }
        if (request.targetDisplayName.isBlank()) {
            return PolicyDecision.Denied("Nenhum alvo foi selecionado.")
        }
        return PolicyDecision.Allowed
    }
}
