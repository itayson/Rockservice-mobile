package org.rockservice.core.usb.media

/** Provenance attached to a bounded read request before it can become an executable block plan. */
enum class UsbReadRangeEvidence {
    /** Range comes from metadata that was parsed and structurally validated locally. */
    VALIDATED_METADATA,

    /** Fixed diagnostic probe approved by an explicit hardware validation gate. */
    VALIDATED_FIXED_PROBE,
}

/** One candidate block range plus the evidence that justifies reading it. */
data class UsbAuthorizedReadRange(
    val startBlock: Long,
    val blockCount: Long,
    val evidence: UsbReadRangeEvidence,
    val description: String,
)

/**
 * Fail-closed policy for turning evidence-backed ranges into immutable [UsbBlockReadPlan] instances.
 *
 * The policy does not perform USB I/O. It enforces a conservative per-operation byte ceiling and
 * accepts only explicitly recognized evidence classes before delegating geometry/range arithmetic to
 * [UsbBlockReadPlanner]. This keeps future physical integrations from bypassing the same bounds.
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
        range: UsbAuthorizedReadRange,
    ): UsbBlockReadPlan {
        require(range.description.isNotBlank()) { "Authorized read range must include a description." }
        require(
            range.evidence == UsbReadRangeEvidence.VALIDATED_METADATA ||
                range.evidence == UsbReadRangeEvidence.VALIDATED_FIXED_PROBE,
        ) { "Unsupported read-range evidence." }

        val plan = planner.plan(
            geometry = geometry,
            startBlock = range.startBlock,
            blockCount = range.blockCount,
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
