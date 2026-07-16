package org.rockservice.mobile

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.rockservice.core.usb.UsbDeviceDescriptor
import org.rockservice.core.usb.rockchip.AndroidRockchipReadOnlyBackupClient
import org.rockservice.core.usb.rockchip.RockchipBackupManifestCodec
import org.rockservice.core.usb.rockchip.RockchipBackupVerifier
import org.rockservice.core.usb.rockchip.RockchipBoundedBackupProgress
import org.rockservice.core.usb.rockchip.RockchipBoundedBackupResult
import org.rockservice.core.usb.rockchip.toBackupManifest

internal sealed interface RockchipBackupState {
    data object Idle : RockchipBackupState
    data class Running(
        val progress: RockchipBoundedBackupProgress?,
        val destinationMayContainPartialData: Boolean,
    ) : RockchipBackupState
    data class VerifyingStoredFile(
        val result: RockchipBoundedBackupResult,
    ) : RockchipBackupState
    data class Completed(
        val result: RockchipBoundedBackupResult,
        val manifestExportRunning: Boolean = false,
        val manifestExportMessage: String? = null,
    ) : RockchipBackupState
    data class StoredFileVerificationFailed(
        val result: RockchipBoundedBackupResult,
        val message: String,
    ) : RockchipBackupState
    data class Failed(
        val message: String,
        val destinationMayContainPartialData: Boolean,
    ) : RockchipBackupState
}

/** Coordinates one explicit local SAF export of a bounded Rockchip read-only range. */
internal class RockchipBackupViewModel : ViewModel() {
    private val mutableState = MutableStateFlow<RockchipBackupState>(RockchipBackupState.Idle)
    private val stateLock = Any()
    private var job: Job? = null
    private var manifestExportJob: Job? = null
    private var generation = 0L

    val state = mutableState.asStateFlow()

    fun start(
        contentResolver: ContentResolver,
        destination: Uri,
        device: UsbDeviceDescriptor,
        client: AndroidRockchipReadOnlyBackupClient,
        startSector: Long,
        sectorCount: Long,
        timeoutMillis: Long,
    ) {
        cancel()
        val runGeneration = synchronized(stateLock) {
            val next = ++generation
            mutableState.value = RockchipBackupState.Running(
                progress = null,
                destinationMayContainPartialData = false,
            )
            next
        }

        job = viewModelScope.launch(Dispatchers.IO) {
            var destinationOpened = false
            val outcome = try {
                val rawOutput = contentResolver.openOutputStream(destination, "w")
                    ?: throw IOException("O destino selecionado não pode ser aberto para escrita.")
                destinationOpened = true
                publishRunning(runGeneration, progress = null, destinationOpened = true)

                val result = BufferedOutputStream(rawOutput).use { output ->
                    val backupResult = client.backup(
                        device = device,
                        startSector = startSector,
                        sectorCount = sectorCount,
                        timeoutMillis = timeoutMillis,
                        onData = { bytes -> output.write(bytes) },
                        onProgress = { progress ->
                            output.flush()
                            publishRunning(runGeneration, progress, destinationOpened = true)
                        },
                    )
                    output.flush()
                    backupResult
                }

                publishStoredFileVerification(runGeneration, result)
                verifyStoredFile(contentResolver, destination, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                RockchipBackupState.Failed(
                    message = error.message?.take(MAXIMUM_ERROR_LENGTH)?.ifBlank { null }
                        ?: "O Android negou acesso ao destino ou dispositivo USB.",
                    destinationMayContainPartialData = destinationOpened,
                )
            } catch (error: IOException) {
                RockchipBackupState.Failed(
                    message = error.message?.take(MAXIMUM_ERROR_LENGTH)?.ifBlank { null }
                        ?: "Falha de entrada/saída durante o backup.",
                    destinationMayContainPartialData = destinationOpened,
                )
            } catch (error: RuntimeException) {
                RockchipBackupState.Failed(
                    message = error.message?.take(MAXIMUM_ERROR_LENGTH)?.ifBlank { null }
                        ?: "Falha ${error.javaClass.simpleName} durante o backup.",
                    destinationMayContainPartialData = destinationOpened,
                )
            }

            synchronized(stateLock) {
                if (runGeneration == generation) mutableState.value = outcome
            }
        }
    }

    fun exportManifest(contentResolver: ContentResolver, destination: Uri) {
        val export = synchronized(stateLock) {
            val completed = mutableState.value as? RockchipBackupState.Completed ?: return
            if (completed.manifestExportRunning) return
            val text = RockchipBackupManifestCodec.encode(completed.result.toBackupManifest())
            mutableState.value = completed.copy(
                manifestExportRunning = true,
                manifestExportMessage = null,
            )
            generation to text
        }

        manifestExportJob?.cancel()
        manifestExportJob = viewModelScope.launch(Dispatchers.IO) {
            val message = try {
                val rawOutput = contentResolver.openOutputStream(destination, "w")
                    ?: throw IOException("O destino do manifesto não pode ser aberto para escrita.")
                rawOutput.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    writer.write(export.second)
                    writer.flush()
                }
                "Manifesto local exportado com sucesso."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                error.message?.take(MAXIMUM_ERROR_LENGTH)?.ifBlank { null }
                    ?: "O Android negou acesso ao destino do manifesto."
            } catch (error: IOException) {
                error.message?.take(MAXIMUM_ERROR_LENGTH)?.ifBlank { null }
                    ?: "Falha de entrada/saída ao exportar o manifesto."
            }

            synchronized(stateLock) {
                if (export.first != generation) return@synchronized
                val completed = mutableState.value as? RockchipBackupState.Completed ?: return@synchronized
                mutableState.value = completed.copy(
                    manifestExportRunning = false,
                    manifestExportMessage = message,
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        manifestExportJob?.cancel()
        manifestExportJob = null
        synchronized(stateLock) {
            generation += 1L
            mutableState.value = RockchipBackupState.Idle
        }
    }

    fun reset() {
        cancel()
    }

    override fun onCleared() {
        job?.cancel()
        manifestExportJob?.cancel()
        synchronized(stateLock) { generation += 1L }
        super.onCleared()
    }

    private fun verifyStoredFile(
        contentResolver: ContentResolver,
        destination: Uri,
        result: RockchipBoundedBackupResult,
    ): RockchipBackupState = try {
        val source = contentResolver.openInputStream(destination)
            ?: throw IOException("O arquivo salvo não pode ser reaberto para verificação.")
        val verification = source.use { input ->
            RockchipBackupVerifier.verify(result.toBackupManifest(), input)
        }
        if (verification.verified) {
            RockchipBackupState.Completed(result)
        } else {
            RockchipBackupState.StoredFileVerificationFailed(
                result = result,
                message = "O arquivo persistido diverge do backup lido. " +
                    "Tamanho: ${if (verification.sizeMatches) "OK" else "DIVERGENTE"}; " +
                    "SHA-256: ${if (verification.sha256Matches) "OK" else "DIVERGENTE"}.",
            )
        }
    } catch (_: SecurityException) {
        RockchipBackupState.StoredFileVerificationFailed(
            result = result,
            message = "O backup foi salvo, mas o Android negou a releitura necessária para validar o arquivo persistido.",
        )
    } catch (_: IOException) {
        RockchipBackupState.StoredFileVerificationFailed(
            result = result,
            message = "O backup foi salvo, mas não foi possível reler o arquivo para confirmar tamanho e SHA-256.",
        )
    } catch (_: IllegalArgumentException) {
        RockchipBackupState.StoredFileVerificationFailed(
            result = result,
            message = "O backup foi salvo, mas seus metadados de integridade não puderam ser validados.",
        )
    }

    private fun publishRunning(
        runGeneration: Long,
        progress: RockchipBoundedBackupProgress?,
        destinationOpened: Boolean,
    ) {
        synchronized(stateLock) {
            if (runGeneration != generation) return
            mutableState.value = RockchipBackupState.Running(
                progress = progress,
                destinationMayContainPartialData = destinationOpened,
            )
        }
    }

    private fun publishStoredFileVerification(
        runGeneration: Long,
        result: RockchipBoundedBackupResult,
    ) {
        synchronized(stateLock) {
            if (runGeneration != generation) return
            mutableState.value = RockchipBackupState.VerifyingStoredFile(result)
        }
    }

    private companion object {
        const val MAXIMUM_ERROR_LENGTH = 240
    }
}
