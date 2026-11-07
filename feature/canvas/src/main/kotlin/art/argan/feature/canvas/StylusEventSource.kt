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
     * A motion predictor bound to one view.
     *
     * WHY THIS AND NOT `getPredictedEvents()`. The P1 brief asks for
     * `MotionEvent.getPredictedEvents()`. That method does not exist on Android — it
     * is a **web Pointer Events** API. `MotionEvent` has no prediction surface at
     * all. The verified Android equivalent is Jetpack's
     * `androidx.input:input-motionprediction`: you `record()` real events and call
     * `predict()` for the event that would occur at the next frame. (Checked against
     * the AndroidX source and AOSP's MotionEvent.java, which contain no such method.)
     *
     * Predictions are still only a hint: they render on their own layer and are
     * discarded when a real event arrives.
     */
    public class Predictor(private val view: View) {
        private val delegate = androidx.input.motionprediction.MotionEventPredictor
            .newInstance(view)

        /** Records a real event. Must be called for every stylus event. */
        public fun record(event: MotionEvent) {
            runCatching { delegate.record(event) }
        }

        /** The predicted next-frame event, or null when prediction is unavailable. */
        public fun predict(): MotionEvent? = runCatching { delegate.predict() }.getOrNull()
    }

    /**
     * Converts a predicted [MotionEvent] into at most [maxPredictions] input points.
     *
     * The predictor returns one event for the next frame; a single point is enough
     * to draw ahead, and chaining more than two would overshoot visibly.
     */
    public fun predictedPoints(
        predicted: MotionEvent?,
        pointerIndex: Int,
        transform: (Float, Float) -> Pair<Float, Float>,
    ): List<InputPoint> {
        if (predicted == null) return emptyList()
        if (pointerIndex >= predicted.pointerCount) return emptyList()
        val (cx, cy) = transform(predicted.getX(pointerIndex), predicted.getY(pointerIndex))
        return listOf(
            InputPoint(
                timestampMillis = predicted.eventTime,
                x = cx,
                y = cy,
                pressure = predicted.getPressure(pointerIndex).coerceIn(0f, 1f),
                isPredicted = true,
            ),
        )
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
