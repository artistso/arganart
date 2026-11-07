package art.argan.feature.motion

/**
 * Placeholder for the motion ribbon editor (P2).
 *
 * Intentionally contains no UI. It exists so the module compiles and so the
 * architecture check can prove that the ribbon, Poses, springs and the ribbon's
 * gesture vocabulary have not been started early.
 */
public object MotionFeaturePhaseMarker {
    /** The phase that will implement this module. */
    public const val IMPLEMENTED_IN_PHASE: String = "P2"
}
