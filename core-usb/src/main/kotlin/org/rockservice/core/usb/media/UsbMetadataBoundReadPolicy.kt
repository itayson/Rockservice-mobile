package org.rockservice.core.usb.media

/** Fixed read-only probes whose exact ranges have been explicitly approved by a hardware gate. */
enum class UsbApprovedFixedProbe(
    internal val startBlock: Long,
    internal val blockCount: Long,
) {
    /** Two-sector LBA 0-1 inspection gated by the documented hardware-validation workflow. */
    LBA_0_1_INSPECTION(startBlock = 0L, blockCount = 2L),
}

/**
 * Opaque authorization issued only by trusted validation paths inside the core-usb module.
 *
 * Callers outside this module cannot construct arbitrary attestations and therefore cannot turn an
 * unvalidated range into an executable plan merely by selecting an evidence enum value.
 */
sealed class UsbReadRangeAuthorization private constructor() {
    internal abstract val startBlock: Long
    internal abstract val blockCount: Long
    internal abstract val description: String

    internal class ValidatedMetadata internal constructor(
        override val startBlock: Long,
        override val blockCount: Long,
        override val description: String,
    ) : UsbReadRangeAuthorization()

    internal class ApprovedFixedProbe internal constructor(
        val probe: UsbApprovedFixedProbe,
        override val description: String,
    ) : UsbReadRangeAuthorization() {
        override val startBlock: Long = probe.startBlock
        override val blockCount: Long = probe.blockCount
    }
}

/**
 * Trusted issuance boundary for read authorizations.
 *
 * This object is internal so application/UI code cannot mint attestations directly. Metadata
 * parsers and hardware-gate integrations inside core-usb may issue an authorization only after
 * their own validation has completed successfully.
 */
internal object UsbReadRangeAuthorizationIssuer {
    fun validatedMetadata(
        startBlock: Long,
        blockCount: Long,
        description: String,
    ): UsbReadRangeAuthorization =
        UsbReadRangeAuthorization.ValidatedMetadata(
            startBlock = startBlock,
            blockCount = blockCount,
            description = description,
        )

    fun approvedFixedProbe(
        probe: UsbApprovedFixedProbe,
        description: String,
    ): UsbReadRangeAuthorization =
        UsbReadRangeAuthorization.ApprovedFixedProbe(
            probe = probe,
            description = description,
        )
}

/**
 * Fail-closed policy for turning trusted range attestations into immutable [UsbBlockReadPlan]s.
 *
 * The policy performs no USB I/O. It accepts only opaque authorizations issued by trusted core-usb
 * validation paths, enforces a conservative per-operation byte ceiling, and delegates all geometry
 * and arithmetic checks to [UsbBlockReadPlanner].
 */
class UsbMetadataBoundReadPolicy(
    private val planner: UsbBlockReadPlanner = UsbBlockReadPlanner(),
    private val maximumAuthorizedBytes: Long = DEFAULT_MAXIMUM_AUTHORIZED_BYTES,
) {
    init {
        require(maximumAuthorizedBytes > 0L) { "maximumAuthorizedBytes must be greater than zero." }
    }

    fun authorize(
        geometry: UsbBlockDeviceGeometry,
        authorization: UsbReadRangeAuthorization,
    ): UsbBlockReadPlan {
        require(authorization.description.isNotBlank()) {
            "Authorized read range must include a description."
        }

        val plan = planner.plan(
            geometry = geometry,
            startBlock = authorization.startBlock,
            blockCount = authorization.blockCount,
        )
        require(plan.byteCount <= maximumAuthorizedBytes) {
            "Authorized read exceeds the policy byte ceiling: requested=${plan.byteCount}, " +
                "maximum=$maximumAuthorizedBytes."
        }
        return plan
    }

    companion object {
        const val DEFAULT_MAXIMUM_AUTHORIZED_BYTES = 2L * 1024L * 1024L
    }
}
