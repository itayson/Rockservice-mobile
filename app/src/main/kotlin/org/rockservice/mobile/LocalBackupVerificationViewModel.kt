package org.rockservice.mobile

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rockservice.core.usb.rockchip.RockchipBackupManifest
import org.rockservice.core.usb.rockchip.RockchipBackupVerifier

data class LocalBackupVerificationUiState(
    val running: Boolean = false,
    val resultMessage: String? = null,
)

internal data class LocalBackupVerificationInput(
    val startSector: Long,
    val sectorCount: Long,
    val sha256: String,
) {
    val byteCount: Long = sectorCount * LOGICAL_SECTOR_SIZE

    companion object {
        private const val LOGICAL_SECTOR_SIZE = 512L
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")
        private val MAXIMUM_SECTOR_COUNT = Long.MAX_VALUE / LOGICAL_SECTOR_SIZE

        fun parse(startSectorText: String, sectorCountText: String, sha256Text: String): Result<LocalBackupVerificationInput> {
            val startSector = startSectorText.toLongOrNull()
                ?: return Result.failure(IllegalArgumentException("Informe um LBA inicial válido."))
            if (startSector < 0L) {
                return Result.failure(IllegalArgumentException("O LBA inicial não pode ser negativo."))
            }

            val sectorCount = sectorCountText.toLongOrNull()
                ?: return Result.failure(IllegalArgumentException("Informe uma quantidade de setores válida."))
            if (sectorCount !in 1L..MAXIMUM_SECTOR_COUNT) {
                return Result.failure(
                    IllegalArgumentException("A quantidade de setores deve estar entre 1 e $MAXIMUM_SECTOR_COUNT."),
                )
            }

            val normalizedSha256 = sha256Text.trim().lowercase()
            if (!SHA256_REGEX.matches(normalizedSha256)) {
                return Result.failure(
                    IllegalArgumentException("Informe um SHA-256 hexadecimal com exatamente 64 caracteres."),
                )
            }

            return Result.success(LocalBackupVerificationInput(startSector, sectorCount, normalizedSha256))
        }
    }
}

class LocalBackupVerificationViewModel : ViewModel() {
    private val _state = MutableStateFlow(LocalBackupVerificationUiState())
    val state: StateFlow<LocalBackupVerificationUiState> = _state.asStateFlow()

    fun clearResult() {
        _state.value = _state.value.copy(resultMessage = null)
    }

    fun verify(
        resolver: ContentResolver,
        uri: Uri,
        startSectorText: String,
        sectorCountText: String,
        sha256Text: String,
    ) {
        val input = LocalBackupVerificationInput.parse(startSectorText, sectorCountText, sha256Text)
            .getOrElse { error ->
                _state.value = LocalBackupVerificationUiState(resultMessage = error.message)
                return
            }

        if (_state.value.running) return
        _state.value = LocalBackupVerificationUiState(running = true)
        viewModelScope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val manifest = RockchipBackupManifest(
                        startSector = input.startSector,
                        sectorCount = input.sectorCount,
                        byteCount = input.byteCount,
                        sha256 = input.sha256,
                    )
                    val source = resolver.openInputStream(uri)
                        ?: throw IOException("O arquivo selecionado não pode ser aberto.")
                    val verification = source.use { RockchipBackupVerifier.verify(manifest, it) }
                    if (verification.verified) {
                        "Arquivo íntegro: tamanho e SHA-256 correspondem ao manifesto."
                    } else {
                        "Verificação falhou. Tamanho: ${if (verification.sizeMatches) "OK" else "DIVERGENTE"}. " +
                            "SHA-256: ${if (verification.sha256Matches) "OK" else "DIVERGENTE"}."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                error.message ?: "Falha de leitura ao verificar o arquivo local."
            } catch (error: SecurityException) {
                "O aplicativo não tem permissão para ler o arquivo selecionado."
            } catch (error: IllegalArgumentException) {
                error.message ?: "Os metadados informados são inválidos."
            }
            _state.value = LocalBackupVerificationUiState(resultMessage = message)
        }
    }
}
