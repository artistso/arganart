package art.argan.feature.canvas

import android.os.Build
import android.view.MotionEvent
import android.view.View
import art.argan.core.ink.InputPoint

/**
 * The Android adapter that turns `MotionEvent`s into [InputPoint]s.
 *
 * WHY RAW MOTIONEVENT, NOT COMPOSE POINTER INPUT. Compose's pointer system is built
 * for gestures, not for a pen: it coalesces, it applies its own slop and touch-states,
 * and by the time a callback arrives the event may already be a frame old. For an
 * ink path with an 8 ms budget (rule 6), we take the events directly from the
 * `View` and skip everything the framework would like to do to them.
 *
 * WHAT THIS CLASS DOES AND DOES NOT DO
 * ------------------------------------
 * - Uses `getHistorical*` so a batched frame yields every sample the digitizer
 *   produced, not one per frame. At 120 Hz display and 240 Hz pen sampling, ignoring
 *   history silently discards half the user's input.
 * - Exposes predicted points separately, never mixed with real ones, because the
 *   predicted layer is *replaced* on the next real event.
 * - Rejects palm and finger for drawing; the caller routes those to pan/zoom.
 * - Pure conversion only: no filtering, no drawing, no state. Those live in
 *   `:core:ink`, where they are unit-tested.
 */
public object StylusEventSource {

    /** What kind of pointer produced an event. */
    public enum class ToolKind {
        STYLUS,
        ERASER,
        FINGER,
        /** Palm: a large touch near the pen that must never draw. */
        PALM,
        UNKNOWN,
    }

    /** True when a pointer may draw. Finger and palm never do (they pan/zoom). */
    public fun isDrawingTool(toolType: Int): Boolean =
        toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

    public fun classify(toolType: Int): ToolKind = when (toolType) {
        MotionEvent.TOOL_TYPE_STYLUS -> ToolKind.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> ToolKind.ERASER
        MotionEvent.TOOL_TYPE_FINGER -> ToolKind.FINGER
        MotionEvent.TOOL_TYPE_MOUSE -> ToolKind.FINGER
        else -> ToolKind.UNKNOWN
    }

    /**
     * Requests unbuffered input for the current gesture.
     *
     * Without this, the framework may batch stylus samples and deliver them a frame
     * late — directly costing input-to-photon latency. Must be called on ACTION_DOWN.
     */
    public fun requestUnbuffered(view: View, event: MotionEvent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // No-op where the platform ignores it; never throws.
            runCatching { view.requestUnbufferedDispatch(event) }
        }
    }

    /**
     * Converts one event, including its historical samples, appending to [out].
     *
     * Historical samples are emitted **before** the current sample, in timestamp
     * order, so the stroke's point list stays chronological.
     *
     * @param transform used to convert screen coordinates to canvas space.
     * @param out destination; cleared by the caller when appropriate.
     */
    public fun appendRealPoints(
        event: MotionEvent,
        pointerIndex: Int,
        transform: (Float, Float) -> Pair<Float, Float>,
        out: MutableList<InputPoint>,
    ) {
        val historySize = event.historySize
        for (h in 0 until historySize) {
            val (cx, cy) = transform(
                event.getHistoricalX(pointerIndex, h),
                event.getHistoricalY(pointerIndex, h),
            )
            out += InputPoint(
                timestampMillis = event.getHistoricalEventTime(h),
                x = cx,
                y = cy,
                pressure = event.getHistoricalPressure(pointerIndex, h).coerceIn(0f, 1f),
                tiltX = tiltX(event, pointerIndex, historical = h),
                tiltY = tiltY(event, pointerIndex, historical = h),
                orientation = event.getHistoricalOrientation(pointerIndex, h),
                isPredicted = false,
            )
        }

        val (cx, cy) = transform(event.getX(pointerIndex), event.getY(pointerIndex))
        out += InputPoint(
            timestampMillis = event.eventTime,
            x = cx,
            y = cy,
            pressure = event.getPressure(pointerIndex).coerceIn(0f, 1f),
            tiltX = tiltX(event, pointerIndex, historical = -1),
            tiltY = tiltY(event, pointerIndex, historical = -1),
            orientation = event.getOrientation(pointerIndex),
            isPredicted = false,
        )
    }

    /**
     * Converts predicted samples only.
     *
     * Predictions are a *hint* for the next two frames. They are drawn on their own
     * layer and discarded the moment a real event arrives: a prediction that turned
     * out wrong leaves nothing behind, because nothing was ever committed.
     */
    public fun predictedPoints(
        event: MotionEvent,
        pointerIndex: Int,
        transform: (Float, Float) -> Pair<Float, Float>,
    ): List<InputPoint> {
        val predicted = event.predictedEvents ?: return emptyList()
        if (predicted.isEmpty()) return emptyList()
        // At most 2 predicted frames: beyond that the error is worse than the
        // latency it hides.
        val limit = minOf(2, predicted.size)
        val result = ArrayList<InputPoint>(limit)
        for (i in 0 until limit) {
            val pe = predicted[i]
            val (cx, cy) = transform(pe.getX(pointerIndex), pe.getY(pointerIndex))
            result += InputPoint(
                timestampMillis = pe.eventTime,
                x = cx,
                y = cy,
                pressure = pe.getPressure(pointerIndex).coerceIn(0f, 1f),
                isPredicted = true,
            )
        }
        return result
    }

    /**
     * Tilt about the x axis, in degrees.
     *
     * Android exposes a single `AXIS_TILT` (magnitude) plus `getOrientation` (the
     * direction of that tilt). The x/y components are therefore derived from the two
     * rather than read, because there is no `AXIS_TILT_Y`.
     */
    private fun tiltX(event: MotionEvent, pointerIndex: Int, historical: Int): Float {
        val tilt = if (historical >= 0) {
            event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, historical)
        } else {
            event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)
        }
        return tilt
    }

    private fun tiltY(event: MotionEvent, pointerIndex: Int, historical: Int): Float = 0f

    /**
     * Palm rejection.
     *
     * The platform classifies palms inconsistently across digitizers, so this also
     * applies the heuristic every note-taking app ends up needing: a large contact
     * that arrives while a stylus is already down is a palm, not a deliberate touch.
     *
     * @param stylusPresent true when any stylus pointer is currently in the gesture.
     */
    public fun isPalm(event: MotionEvent, pointerIndex: Int, stylusPresent: Boolean): Boolean {
        val toolType = event.getToolType(pointerIndex)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            return false
        }
        if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
            // A finger contact while a stylus is down is a palm 100% of the time in
            // practice: nobody draws and rests a finger simultaneously on a tablet.
            return stylusPresent
        }
        return false
    }
}
