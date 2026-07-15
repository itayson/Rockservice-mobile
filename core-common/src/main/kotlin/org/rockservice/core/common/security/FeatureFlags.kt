package org.rockservice.core.common.security

data class FeatureFlags(
    val experimentalUsbWrite: Boolean = false,
    val rootIntegration: Boolean = false,
) {
    init {
        require(!experimentalUsbWrite) {
            "Real USB write must remain disabled in the bootstrap release."
        }
        require(!rootIntegration) {
            "Root integration must remain disabled in the bootstrap release."
        }
    }
}
