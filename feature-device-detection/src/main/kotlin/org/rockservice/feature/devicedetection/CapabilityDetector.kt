package org.rockservice.feature.devicedetection

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.rockservice.core.common.model.Availability
import org.rockservice.core.common.model.Capability
import org.rockservice.core.common.model.RiskLevel

class CapabilityDetector(private val context: Context) {
    fun detect(): List<Capability> {
        val pm = context.packageManager
        val usbHost = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        val arm64 = Build.SUPPORTED_ABIS.contains("arm64-v8a")

        return listOf(
            Capability(
                id = "firmware_analysis",
                title = "Análise local de firmware",
                availability = Availability.AVAILABLE,
                reason = "Disponível por Storage Access Framework; não exige root.",
            ),
            Capability(
                id = "usb_host",
                title = "USB Host/OTG",
                availability = if (usbHost) Availability.AVAILABLE else Availability.UNAVAILABLE,
                reason = if (usbHost) {
                    "O dispositivo Android declara suporte a USB Host."
                } else {
                    "O dispositivo Android não declara FEATURE_USB_HOST."
                },
            ),
            Capability(
                id = "native_arm64",
                title = "Ferramentas nativas arm64-v8a",
                availability = if (arm64) Availability.AVAILABLE else Availability.UNAVAILABLE,
                reason = "ABIs declaradas: ${Build.SUPPORTED_ABIS.joinToString()}.",
            ),
            Capability(
                id = "rockchip_write",
                title = "Gravação Rockchip real",
                availability = Availability.UNAVAILABLE,
                reason = "Desativada no bootstrap; requer backend validado, feature flag e teste em hardware.",
                riskLevel = RiskLevel.CRITICAL,
            ),
            Capability(
                id = "root",
                title = "Integração root",
                availability = Availability.UNKNOWN,
                reason = "Não é inferida por exploração. A verificação autorizada será adicionada em fase posterior.",
                riskLevel = RiskLevel.HIGH,
            ),
        )
    }
}
