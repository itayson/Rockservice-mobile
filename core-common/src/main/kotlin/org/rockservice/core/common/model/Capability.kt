package org.rockservice.core.common.model

enum class Availability { AVAILABLE, UNAVAILABLE, UNKNOWN }
enum class RiskLevel { INFORMATIONAL, LOW, MODERATE, HIGH, CRITICAL }

data class Capability(
    val id: String,
    val title: String,
    val availability: Availability,
    val reason: String,
    val riskLevel: RiskLevel = RiskLevel.INFORMATIONAL,
)
