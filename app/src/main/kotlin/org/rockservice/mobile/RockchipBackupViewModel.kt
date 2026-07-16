package org.rockservice.mobile

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.BufferedOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.rockservice.core.usb.UsbDeviceDescriptor
import org.rockservice.core.usb.rockchip.AndroidRockchipReadOnlyBackupClient
import org.rockservice.core.usb.rockchip.RockchipBoundedBackupProgress
import org.rockservice.core.usb.rockchip.RockchipBoundedBackupResult

internal sealed interface RockchipBackupState {
    data object Idle : RockchipBackupState
    data class Running(
        val progress: RockchipBoundedBackupProgress?,
        val destinationMayContainPartialData: Boolean,
    ) : RockchipBackupState
    data class Completed(val result: RockchipBoundedBackupResult) : RockchipBackupState
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

                BufferedOutputStream(rawOutput).use { output ->
                    val result = client.backup(
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
                    RockchipBackupState.Completed(result)
                }
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

    fun cancel() {
        job?.cancel()
        job = null
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
        synchronized(stateLock) { generation += 1L }
        super.onCleared()
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

    private companion object {
        const val MAXIMUM_ERROR_LENGTH = 240
    }
}
