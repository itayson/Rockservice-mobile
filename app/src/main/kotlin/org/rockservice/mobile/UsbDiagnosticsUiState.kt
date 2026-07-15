package org.rockservice.mobile

import kotlinx.coroutines.CancellationException
import org.rockservice.core.usb.AndroidUsbHostBackend
import org.rockservice.core.usb.RockchipPassiveProbe
import org.rockservice.core.usb.RockchipPassiveProbeLevel
import org.rockservice.core.usb.UsbDeviceDescriptor

internal sealed interface UsbDiagnosticsUiState {
    data object Loading : UsbDiagnosticsUiState

    data class Ready(
        val devices: List<UsbDeviceUiModel>,
    ) : UsbDiagnosticsUiState

    data class Error(
        val message: String,
    ) : UsbDiagnosticsUiState
}

internal data class UsbDeviceUiModel(
    val descriptor: UsbDeviceDescriptor,
    val transportId: String,
    val title: String,
    val vendorProduct: String,
    val permissionLabel: String,
    val topologyLabel: String,
    val rockchipProbeLabel: String,
)

internal suspend fun scanUsbDiagnostics(
    backend: AndroidUsbHostBackend,
): UsbDiagnosticsUiState =
    try {
        val devices = backend.listDevices().map { device ->
            val topology = backend.inspectTopology(device)
            val probe = RockchipPassiveProbe.probe(device, topology)
            UsbDeviceUiModel(
                descriptor = device,
                transportId = requireNotNull(device.transportId),
                title = device.product ?: device.manufacturer ?: "Dispositivo USB",
                vendorProduct = "VID:PID ${device.vendorId.hex4()}:${device.productId.hex4()}",
                permissionLabel = if (device.hasPermission) {
                    "Permissão USB: concedida"
                } else {
                    "Permissão USB: ainda não solicitada"
                },
                topologyLabel = "${topology.interfaces.size} interface(s), ${topology.endpoints.size} endpoint(s)",
                rockchipProbeLabel = when (probe.level) {
                    RockchipPassiveProbeLevel.NOT_ROCKCHIP -> "Probe: fabricante USB não Rockchip"
                    RockchipPassiveProbeLevel.ROCKCHIP_VENDOR_ONLY -> "Probe: dispositivo Rockchip detectado"
                    RockchipPassiveProbeLevel.ROCKCHIP_BULK_TRANSPORT_CANDIDATE ->
                        "Probe: Rockchip com transporte bulk bidirecional candidato"
                },
            )
        }
        UsbDiagnosticsUiState.Ready(devices)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        UsbDiagnosticsUiState.Error(
            message = error.message ?: "Falha ao enumerar dispositivos USB.",
        )
    }

private fun Int.hex4(): String =
    toString(radix = 16).uppercase().padStart(length = 4, padChar = '0')
