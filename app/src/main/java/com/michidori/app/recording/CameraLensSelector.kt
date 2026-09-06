package com.michidori.app.recording

/** Camera2 physical lens candidate discovered below a CameraX logical camera. */
internal data class PhysicalLensCandidate(
    val cameraId: String,
    val focalLengthMm: Float,
)

/**
 * Chooses a materially wider physical camera without assuming OEM camera IDs.
 * The reference focal length is the logical camera's default/main lens.
 */
internal object CameraLensSelector {
    private const val ULTRA_WIDE_REFERENCE_RATIO = 0.75f

    fun chooseUltraWide(
        mainFocalLengthMm: Float?,
        candidates: List<PhysicalLensCandidate>,
    ): PhysicalLensCandidate? {
        val validCandidates = candidates.filter { candidate ->
            candidate.cameraId.isNotBlank() &&
                candidate.focalLengthMm.isFinite() &&
                candidate.focalLengthMm > 0f
        }
        val referenceFocalLengthMm = mainFocalLengthMm
            ?.takeIf { it.isFinite() && it > 0f }
            ?: validCandidates.maxOfOrNull(PhysicalLensCandidate::focalLengthMm)
            ?: return null
        val maxUltraWideFocalLengthMm = referenceFocalLengthMm * ULTRA_WIDE_REFERENCE_RATIO
        return validCandidates
            .filter { it.focalLengthMm <= maxUltraWideFocalLengthMm }
            .minByOrNull(PhysicalLensCandidate::focalLengthMm)
    }
}
