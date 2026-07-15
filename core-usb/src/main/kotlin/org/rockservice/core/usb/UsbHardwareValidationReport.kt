package org.rockservice.core.usb

import java.time.Instant
import java.util.Locale

/** Non-sensitive Android host information included in a hardware-validation report. */
data class UsbHardwareValidationHostInfo(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
)

/** Optional operator-provided context that cannot be discovered reliably through USB descriptors. */
data class UsbHardwareValidationNotes(
    val boardOrDeviceModel: String = "",
    val knownSoc: String = "",
    val otgAdapter: String = "",
)

/** Result of the bounded descriptor-read validation used to prove permission and connection opening. */
data class UsbHardwareValidationDescriptorCheck(
    val succeeded: Boolean,
    val bytesRead: Int,
    val sha256: String?,
    val detail: String,
) {
    init {
        require(bytesRead >= 0) { "bytesRead must be non-negative." }
        require(sha256 == null || sha256.matches(Regex("[0-9a-f]{64}"))) {
            "sha256 must be a lowercase hexadecimal SHA-256 digest."
        }
    }
}

/** Sanitized attach/detach observation. Transport identifiers are intentionally excluded. */
data class UsbHardwareValidationEvent(
    val kind: UsbAttachmentEventKind,
    val timestampEpochMillis: Long,
)

/** Sanitized USB device snapshot. Serial numbers and Android transport paths are never retained. */
data class UsbHardwareValidationDevice(
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String?,
    val product: String?,
    val deviceClass: Int?,
    val deviceSubclass: Int?,
    val deviceProtocol: Int?,
    val permissionBeforeValidation: Boolean,
    val interfaces: List<UsbInterfaceDescriptor>,
    val probeLevel: RockchipPassiveProbeLevel,
    val hasBulkIn: Boolean,
    val hasBulkOut: Boolean,
) {
    companion object {
        /** Removes serial number and transport identity while preserving reproducible USB topology data. */
        fun from(snapshot: UsbDiagnosticsDeviceSnapshot): UsbHardwareValidationDevice =
            UsbHardwareValidationDevice(
                vendorId = snapshot.descriptor.vendorId,
                productId = snapshot.descriptor.productId,
                manufacturer = snapshot.descriptor.manufacturer,
                product = snapshot.descriptor.product,
                deviceClass = snapshot.descriptor.deviceClass,
                deviceSubclass = snapshot.descriptor.deviceSubclass,
                deviceProtocol = snapshot.descriptor.deviceProtocol,
                permissionBeforeValidation = snapshot.descriptor.hasPermission,
                interfaces = snapshot.topology.interfaces,
                probeLevel = snapshot.rockchipProbe.level,
                hasBulkIn = snapshot.rockchipProbe.hasBulkIn,
                hasBulkOut = snapshot.rockchipProbe.hasBulkOut,
            )
    }
}

/** Complete passive hardware-validation evidence suitable for export and issue #18 review. */
data class UsbHardwareValidationReport(
    val generatedAtEpochMillis: Long,
    val host: UsbHardwareValidationHostInfo,
    val notes: UsbHardwareValidationNotes,
    val device: UsbHardwareValidationDevice,
    val descriptorCheck: UsbHardwareValidationDescriptorCheck,
    val events: List<UsbHardwareValidationEvent>,
) {
    /** Suggested export name that contains no device identifier. */
    val suggestedFileName: String = "rockservice-hardware-validation.txt"

    /** Renders a deterministic, sanitized plain-text report. */
    fun toPlainText(): String = buildString {
        appendLine("RockService Mobile - Validacao de Hardware USB")
        appendLine("===============================================")
        appendLine("Gerado em UTC: ${Instant.ofEpochMilli(generatedAtEpochMillis)}")
        appendLine()

        appendLine("Host Android")
        appendLine("------------")
        appendLine("Fabricante: ${host.manufacturer.safeText()}")
        appendLine("Modelo: ${host.model.safeText()}")
        appendLine("Android: ${host.androidRelease.safeText()} (SDK ${host.sdkInt})")
        appendLine()

        appendLine("Contexto informado pelo tecnico")
        appendLine("-------------------------------")
        appendLine("Placa/TV Box/TV: ${notes.boardOrDeviceModel.safeText()}")
        appendLine("SoC conhecido: ${notes.knownSoc.safeText()}")
        appendLine("Cabo/adaptador OTG: ${notes.otgAdapter.safeText()}")
        appendLine()

        appendLine("Dispositivo USB sanitizado")
        appendLine("---------------------------")
        appendLine("VID:PID: ${device.vendorId.hex4()}:${device.productId.hex4()}")
        appendLine("Fabricante USB: ${device.manufacturer.safeText()}")
        appendLine("Produto USB: ${device.product.safeText()}")
        appendLine("Classe/Subclasse/Protocolo: ${device.deviceClass.hex2()}/${device.deviceSubclass.hex2()}/${device.deviceProtocol.hex2()}")
        appendLine("Permissao antes do teste: ${if (device.permissionBeforeValidation) "concedida" else "nao concedida"}")
        appendLine("Probe passivo: ${device.probeLevel}")
        appendLine("Bulk IN: ${device.hasBulkIn}")
        appendLine("Bulk OUT: ${device.hasBulkOut}")
        appendLine("Interfaces: ${device.interfaces.size}")
        device.interfaces.forEach { usbInterface ->
            appendLine(
                "- interface ${usbInterface.id} alt=${usbInterface.alternateSetting} " +
                    "class=${usbInterface.interfaceClass.hex2()} " +
                    "subclass=${usbInterface.interfaceSubclass.hex2()} " +
                    "protocol=${usbInterface.interfaceProtocol.hex2()} endpoints=${usbInterface.endpoints.size}",
            )
            usbInterface.endpoints.forEach { endpoint ->
                appendLine(
                    "  - endpoint 0x${endpoint.address.toString(16).uppercase(Locale.US).padStart(2, '0')} " +
                        "${endpoint.direction} ${endpoint.transferType} maxPacket=${endpoint.maxPacketSize} interval=${endpoint.interval}",
                )
            }
        }
        appendLine()

        appendLine("Teste seguro de permissao e descritores")
        appendLine("---------------------------------------")
        appendLine("Resultado: ${if (descriptorCheck.succeeded) "SUCESSO" else "FALHA"}")
        appendLine("Bytes de descritor lidos: ${descriptorCheck.bytesRead}")
        appendLine("SHA-256 dos bytes lidos: ${descriptorCheck.sha256 ?: "nao disponivel"}")
        appendLine("Detalhe: ${descriptorCheck.detail.safeText()}")
        appendLine()

        appendLine("Eventos attach/detach observados")
        appendLine("--------------------------------")
        if (events.isEmpty()) {
            appendLine("Nenhum evento observado durante esta sessao.")
        } else {
            events.forEach { event ->
                appendLine("${Instant.ofEpochMilli(event.timestampEpochMillis)} - ${event.kind}")
            }
        }
        appendLine()

        appendLine("Fronteira de seguranca")
        appendLine("----------------------")
        appendLine("Este teste nao reivindica interfaces USB e nao envia comandos Rockchip.")
        appendLine("A leitura e limitada aos descritores USB expostos pelo Android Host.")
        appendLine("Numero serial e transportId/caminho USB nao sao incluidos neste relatorio.")
    }
}

private fun String?.safeText(): String = this?.trim()?.takeIf(String::isNotEmpty) ?: "nao informado"

private fun Int.hex4(): String = toString(16).uppercase(Locale.US).padStart(4, '0')

private fun Int?.hex2(): String = this?.toString(16)?.uppercase(Locale.US)?.padStart(2, '0') ?: "--"
