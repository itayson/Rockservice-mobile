package org.rockservice.mobile

import org.rockservice.core.usb.RockchipPassiveProbeLevel
import org.rockservice.core.usb.UsbDiagnosticsDeviceSnapshot

/** Presentation-only model derived from a validated passive USB diagnostic snapshot. */
internal data class UsbDeviceUiModel(
    val transportId: String,
    val title: String,
    val vendorProduct: String,
    val permissionLabel: String,
    val topologyLabel: String,
    val rockchipProbeLabel: String,
)

/** Converts core USB diagnostic data into localized labels without changing target identity. */
internal fun UsbDiagnosticsDeviceSnapshot.toUiModel(): UsbDeviceUiModel {
    val device = descriptor
    return UsbDeviceUiModel(
        transportId = requireNotNull(device.transportId),
        title = device.product ?: device.manufacturer ?: "Dispositivo USB",
        vendorProduct = "VID:PID ${device.vendorId.hex4()}:${device.productId.hex4()}",
        permissionLabel = if (device.hasPermission) {
            "Permissão USB: concedida"
        } else {
            "Permissão USB: ainda não solicitada"
        },
        topologyLabel = "${topology.interfaces.size} interface(s), ${topology.endpoints.size} endpoint(s)",
        rockchipProbeLabel = when (rockchipProbe.level) {
            RockchipPassiveProbeLevel.NOT_ROCKCHIP -> "Probe: fabricante USB não Rockchip"
            RockchipPassiveProbeLevel.ROCKCHIP_VENDOR_ONLY -> "Probe: dispositivo Rockchip detectado"
            RockchipPassiveProbeLevel.ROCKCHIP_BULK_TRANSPORT_CANDIDATE ->
                "Probe: Rockchip com transporte bulk bidirecional candidato"
        },
    )
}

private fun Int.hex4(): String =
    toString(radix = 16).uppercase().padStart(length = 4, padChar = '0')
