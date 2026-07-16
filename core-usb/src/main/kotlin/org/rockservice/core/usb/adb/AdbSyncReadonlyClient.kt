package org.rockservice.core.usb.adb

import kotlinx.coroutines.withTimeout

/**
 * Opens the dedicated `sync:` ADB service and performs one bounded read-only RECV operation.
 *
 * This API intentionally exposes no arbitrary service name and no SEND/push operation.
 */
class AdbSyncReadonlyClient(
    private val session: AdbSessionController,
    private val engine: AdbSyncPullEngine = AdbSyncPullEngine(),
) {
    /**
     * Pulls one remote path through a fresh dedicated Sync stream.
     *
     * The supplied timeout is a total client budget covering service OPEN plus the complete pull.
     * The opened Sync stream is owned and closed by the pull engine once OPEN succeeds.
     */
    suspend fun pull(
        remotePath: String,
        maximumBytes: Long = AdbSyncPullEngine.DEFAULT_MAXIMUM_BYTES,
        timeoutMillis: Long = AdbSyncPullEngine.DEFAULT_TIMEOUT_MILLIS,
        onData: suspend (ByteArray) -> Unit,
    ): AdbSyncPullResult {
        validateRequestBeforeOpen(
            remotePath = remotePath,
            maximumBytes = maximumBytes,
            timeoutMillis = timeoutMillis,
        )

        return withTimeout(timeoutMillis) {
            val stream = session.openSync(timeoutMillis = timeoutMillis)
            engine.pull(
                stream = stream,
                remotePath = remotePath,
                maximumBytes = maximumBytes,
                timeoutMillis = timeoutMillis,
                onData = onData,
            )
        }
    }

    private fun validateRequestBeforeOpen(
        remotePath: String,
        maximumBytes: Long,
        timeoutMillis: Long,
    ) {
        require(maximumBytes in 1L..AdbSyncPullEngine.MAXIMUM_ALLOWED_BYTES) {
            "Limite de ADB Sync pull deve estar entre 1 e ${AdbSyncPullEngine.MAXIMUM_ALLOWED_BYTES} bytes."
        }
        require(timeoutMillis in 1L..AdbSyncPullEngine.MAXIMUM_ALLOWED_TIMEOUT_MILLIS) {
            "Timeout de ADB Sync pull deve estar entre 1 e ${AdbSyncPullEngine.MAXIMUM_ALLOWED_TIMEOUT_MILLIS} ms."
        }
        // Validate path framing before opening a remote service so invalid caller input cannot leave
        // a newly opened Sync stream outside engine ownership.
        AdbSyncPullCodec.encodeReceiveRequest(remotePath)
    }
}
