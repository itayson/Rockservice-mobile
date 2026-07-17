package org.rockservice.mobile

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rockservice.core.usb.rockchip.RockchipBackupManifest
import org.rockservice.core.usb.rockchip.RockchipBackupManifestCodec
import org.rockservice.core.usb.rockchip.RockchipBackupVerifier

data class LocalBackupVerificationUiState(
    val running: Boolean = false,
    val resultMessage: String? = null,
    val verificationPassed: Boolean = false,
    val manifestLoading: Boolean = false,
    val manifestMessage: String? = null,
    val loadedManifest: RockchipBackupManifest? = null,
    val manifestRevision: Long = 0L,
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
    private var manifestLoadJob: Job? = null
    private var manifestRequestGeneration = 0L

    val state: StateFlow<LocalBackupVerificationUiState> = _state.asStateFlow()

    fun clearResult() {
        _state.value = _state.value.copy(
            resultMessage = null,
            verificationPassed = false,
        )
    }

    fun markMetadataEdited() {
        _state.value = _state.value.copy(
            resultMessage = null,
            verificationPassed = false,
            manifestMessage = null,
            loadedManifest = null,
        )
    }

    fun loadManifest(resolver: ContentResolver, uri: Uri) {
        if (_state.value.running) return

        manifestLoadJob?.cancel()
        val requestGeneration = ++manifestRequestGeneration
        _state.value = _state.value.copy(
            manifestLoading = true,
            manifestMessage = null,
            resultMessage = null,
            verificationPassed = false,
        )

        manifestLoadJob = viewModelScope.launch {
            val result = try {
                val manifest = withContext(Dispatchers.IO) {
                    val source = resolver.openInputStream(uri)
                        ?: throw IOException("O manifesto selecionado não pode ser aberto.")
                    source.use { input -> RockchipBackupManifestCodec.decode(input) }
                }
                ManifestLoadResult.Success(manifest)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                ManifestLoadResult.Failure(
                    "O acesso ao manifesto foi negado. Selecione o arquivo novamente e tente outra vez.",
                )
            } catch (_: IOException) {
                ManifestLoadResult.Failure(
                    "Não foi possível ler o manifesto. Confirme que o arquivo ainda está disponível.",
                )
            } catch (_: IllegalArgumentException) {
                ManifestLoadResult.Failure(
                    "O arquivo selecionado não é um manifesto RockService válido ou está corrompido.",
                )
            }

            if (requestGeneration != manifestRequestGeneration) return@launch
            when (result) {
                is ManifestLoadResult.Success -> {
                    _state.value = _state.value.copy(
                        manifestLoading = false,
                        manifestMessage = "Manifesto carregado. Os metadados foram preenchidos automaticamente.",
                        loadedManifest = result.manifest,
                        manifestRevision = _state.value.manifestRevision + 1L,
                    )
                }
                is ManifestLoadResult.Failure -> {
                    _state.value = _state.value.copy(
                        manifestLoading = false,
                        manifestMessage = result.message,
                    )
                }
            }
        }
    }

    fun verify(
        resolver: ContentResolver,
        uri: Uri,
        startSectorText: String,
        sectorCountText: String,
        sha256Text: String,
    ) {
        if (_state.value.running || _state.value.manifestLoading) return

        val input = LocalBackupVerificationInput.parse(startSectorText, sectorCountText, sha256Text)
            .getOrElse { error ->
                _state.value = _state.value.copy(
                    resultMessage = error.message,
                    verificationPassed = false,
                )
                return
            }

        _state.value = _state.value.copy(
            running = true,
            resultMessage = null,
            verificationPassed = false,
        )
        viewModelScope.launch {
            val result = try {
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
                        VerificationExecutionResult(
                            message = "Arquivo íntegro: tamanho e SHA-256 correspondem ao manifesto.",
                            passed = true,
                        )
                    } else {
                        VerificationExecutionResult(
                            message = "Verificação falhou. Tamanho: ${if (verification.sizeMatches) "OK" else "DIVERGENTE"}. " +
                                "SHA-256: ${if (verification.sha256Matches) "OK" else "DIVERGENTE"}.",
                            passed = false,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                VerificationExecutionResult(
                    "Não foi possível ler o arquivo. Confirme que ele ainda está disponível e tente novamente.",
                    false,
                )
            } catch (_: SecurityException) {
                VerificationExecutionResult(
                    "O acesso ao arquivo foi negado. Selecione o arquivo novamente e tente outra vez.",
                    false,
                )
            } catch (_: IllegalArgumentException) {
                VerificationExecutionResult(
                    "Os metadados são inválidos. Revise o LBA, a quantidade de setores e o SHA-256.",
                    false,
                )
            }
            _state.value = _state.value.copy(
                running = false,
                resultMessage = result.message,
                verificationPassed = result.passed,
            )
        }
    }

    override fun onCleared() {
        manifestLoadJob?.cancel()
        manifestRequestGeneration += 1L
        super.onCleared()
    }

    private data class VerificationExecutionResult(
        val message: String,
        val passed: Boolean,
    )

    private sealed interface ManifestLoadResult {
        data class Success(val manifest: RockchipBackupManifest) : ManifestLoadResult
        data class Failure(val message: String) : ManifestLoadResult
    }
}
