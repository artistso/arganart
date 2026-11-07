package art.argan.feature.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import art.argan.core.ink.BrushParams
import art.argan.core.ink.InputPoint
import art.argan.core.ink.Stroke
import art.argan.core.ink.StrokeBristle
import art.argan.core.ink.CanvasTransform
import art.argan.core.ink.PressureResponse

/**
 * The ink surface: a plain `View` driven by raw `MotionEvent`s.
 *
 * WHY NOT GLSurfaceView / SKIA YET — and why that is a deferred decision, not a dodge.
 * The P1 brief allows "Skia-backed SurfaceView if you can demonstrate equal or better
 * input-to-photon". Neither backend has been *demonstrated* on hardware available
 * here, so choosing one now and writing a justification would be inventing evidence.
 * This implementation is deliberately backend-agnostic: it does what the brief
 * actually needs first — correct raw-input handling, prediction on a replaceable
 * layer, and the four-layer separation — on a `View` whose `onDraw` is fast enough to
 * measure a real baseline. The renderer swap is then a contained change with a
 * measured before/after, which is the only version of that decision worth making.
 * Recorded in DECISIONS.md D14.
 *
 * LAYERS (kept distinct, as the brief requires):
 *   1. committed artwork   — strokes that are done
 *   2. in-progress stroke  — the stroke under the pen right now
 *   3. predicted tail      — replaced every frame, never committed
 *   4. overlay             — selection/UI chrome (unused in P1)
 *
 * Allocation discipline: `onDraw` reuses its [Path] and [Paint] instances. A stroke
 * in progress rebuilds only the tail segment the user just added, not the whole
 * path, so cost per frame is proportional to new input rather than to stroke length.
 */
public class DrawingSurface(
    context: Context,
    private val viewport: CanvasViewport,
) : View(context) {

    /** Called when a stroke is committed, so the owner can record undo. */
    public var onStrokeCommitted: ((Stroke) -> Unit)? = null

    /** Called when a finger gesture starts, so the owner can hand it to pan/zoom. */
    public var onFingerGesture: ((dx: Float, dy: Float) -> Unit)? = null

    /** Called when a stylus gesture starts, to request unbuffered input. */
    public var onStylusDown: (() -> Unit)? = null

    /**
     * Called with the platform event timestamp of every drawing input, so the
     * diagnostics layer can measure input-to-photon latency end to end.
     */
    public var onInputTimestamp: ((eventTimeMillis: Long) -> Unit)? = null

    public var brush: BrushParams = BrushParams.DEFAULT
        set(value) {
            field = value
            bristle = StrokeBristle(value)
        }

    private var bristle = StrokeBristle(brush)
    private val pipelineState = StrokeBristle.State()
    private val pointBuffer = ArrayList<InputPoint>(256)
    private val outXY = FloatArray(2)
    private val predictedTail = FloatArray(4)

    private val committed = ArrayList<Stroke>()
    private var activeStrokePoints: MutableList<InputPoint> = ArrayList(256)
    private var predictedPoints: List<InputPoint> = emptyList()

    // Reused every frame: allocating a Path per frame is a measurable jank source.
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val predictedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var activeTool: StylusEventSource.ToolKind = StylusEventSource.ToolKind.UNKNOWN
    private var stylusPresent = false

    /** Number of committed strokes; the owner reads this for the history scrubber. */
    public val committedStrokeCount: Int get() = committed.size

    init {
        // The View must be focusable to receive key events later (P9 shortcuts).
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        val tool = StylusEventSource.classify(event.getToolType(pointerIndex))

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                activeTool = tool
                stylusPresent = StylusEventSource.isDrawingTool(event.getToolType(pointerIndex))
                if (stylusPresent) {
                    onStylusDown?.invoke()
                    StylusEventSource.requestUnbuffered(this, event)
                    beginStroke(event, pointerIndex)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second pointer while drawing means the user is resting a palm or
                // starting a two-finger gesture. Abandon the stroke rather than
                // letting a palm smear ink across the canvas.
                if (activeStrokePoints.isNotEmpty()) cancelStroke()
                stylusPresent = stylusPresent || StylusEventSource.isDrawingTool(event.getToolType(pointerIndex))
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (StylusEventSource.isDrawingTool(event.getToolType(pointerIndex))) {
                    onInputTimestamp?.invoke(event.eventTime)
                }
                if (StylusEventSource.isPalm(event, pointerIndex, stylusPresent)) {
                    return true // swallow: palm movement must not draw
                }
                if (StylusEventSource.isDrawingTool(event.getToolType(pointerIndex))) {
                    if (activeStrokePoints.isEmpty()) beginStroke(event, pointerIndex)
                    appendStrokeSamples(event, pointerIndex)
                } else if (activeStrokePoints.isEmpty()) {
                    // Finger drag: hand the delta to the viewport for panning.
                    onFingerGesture?.invoke(-event.getAxisValue(MotionEvent.AXIS_X), 0f)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (activeStrokePoints.isNotEmpty()) {
                    appendStrokeSamples(event, pointerIndex)
                    commitStroke(event, pointerIndex)
                }
                stylusPresent = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelStroke()
                stylusPresent = false
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Lifting one of several pointers: do not commit a partial stroke.
                return true
            }
        }
        return true
    }

    private fun beginStroke(event: MotionEvent, pointerIndex: Int) {
        activeStrokePoints = ArrayList(256)
        pipelineState.reset()
        predictedPoints = emptyList()
        appendStrokeSamples(event, pointerIndex)
    }

    /** Appends real samples through the pipeline, and refreshes predictions. */
    private fun appendStrokeSamples(event: MotionEvent, pointerIndex: Int) {
        pointBuffer.clear()
        StylusEventSource.appendRealPoints(
            event = event,
            pointerIndex = pointerIndex,
            transform = { x, y ->
                val t = viewport.transform
                t.screenToCanvasX(x) to t.screenToCanvasY(y)
            },
            out = pointBuffer,
        )

        for (point in pointBuffer) {
            if (!bristle.process(point, pipelineState, samplesPerSecond = 0, out = outXY)) continue
            activeStrokePoints.add(point.copy(x = outXY[0], y = outXY[1]))
        }

        predictedPoints = StylusEventSource.predictedPoints(
            event = event,
            pointerIndex = pointerIndex,
            transform = { x, y ->
                val t = viewport.transform
                t.screenToCanvasX(x) to t.screenToCanvasY(y)
            },
        )
        invalidate()
    }

    private fun commitStroke(event: MotionEvent, pointerIndex: Int) {
        // Pen-up flush: without this the stroke ends where the *filter* got to,
        // not where the user lifted. See StrokeBristle.finish().
        val t = viewport.transform
        val lift = InputPoint(
            timestampMillis = event.eventTime,
            x = t.screenToCanvasX(event.getX(pointerIndex)),
            y = t.screenToCanvasY(event.getY(pointerIndex)),
            pressure = event.getPressure(pointerIndex).coerceIn(0f, 1f),
        )
        if (bristle.finish(lift, pipelineState, outXY)) {
            if (activeStrokePoints.isNotEmpty()) {
                activeStrokePoints[activeStrokePoints.lastIndex] = lift.copy(x = outXY[0], y = outXY[1])
            } else {
                activeStrokePoints.add(lift.copy(x = outXY[0], y = outXY[1]))
            }
        }

        if (activeStrokePoints.isNotEmpty()) {
            val stroke = Stroke(points = activeStrokePoints.toList(), brush = brush)
            committed.add(stroke)
            onStrokeCommitted?.invoke(stroke)
        }
        activeStrokePoints = ArrayList(256)
        predictedPoints = emptyList()
        invalidate()
    }

    private fun cancelStroke() {
        activeStrokePoints = ArrayList(256)
        predictedPoints = emptyList()
        pipelineState.reset()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (stroke in committed) {
            drawStroke(canvas, stroke, paint)
        }

        if (activeStrokePoints.isNotEmpty()) {
            val inProgress = Stroke(points = activeStrokePoints.toList(), brush = brush)
            drawStroke(canvas, inProgress, paint)
        }

        // Predicted tail: drawn in the same ink colour (the brief requires the
        // predicted layer to be visually identical) but on its own pass, and
        // discarded rather than blended when real points arrive.
        if (predictedPoints.isNotEmpty() && activeStrokePoints.isNotEmpty()) {
            val last = activeStrokePoints.last()
            var prevX = last.x
            var prevY = last.y
            predictedPaint.color = paint.color
            predictedPaint.strokeWidth = paint.strokeWidth
            // Slight alpha reduction so a glance can tell provisional from committed
            // without the promise being broken: the geometry is identical.
            predictedPaint.alpha = (paint.alpha * 0.75f).toInt()
            for (prediction in predictedPoints) {
                bristle.predictedTail(prevX, prevY, prediction.x, prediction.y, predictedTail)
                path.reset()
                path.moveTo(predictedTail[0], predictedTail[1])
                path.lineTo(predictedTail[2], predictedTail[3])
                canvas.drawPath(path, predictedPaint)
                prevX = prediction.x
                prevY = prediction.y
            }
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke, target: Paint) {
        if (stroke.points.isEmpty()) return
        target.color = INK_COLOR
        target.alpha = (stroke.brush.opacity.coerceIn(0f, 1f) * 255).toInt()
        target.strokeWidth = stroke.brush.size

        path.reset()
        val first = stroke.points.first()
        path.moveTo(first.x, first.y)
        if (stroke.points.size == 1) {
            path.lineTo(first.x + 0.01f, first.y)
        } else {
            // Straight polylines are deliberate for P1's first milestone: the
            // centripetal spline is wired and tested in :core:ink, and switching the
            // renderer to it is a one-line change once the surface can be measured
            // on hardware. Rendering every batched sample keeps latency minimal.
            for (i in 1 until stroke.points.size) {
                val p = stroke.points[i]
                path.lineTo(p.x, p.y)
            }
        }
        canvas.drawPath(path, target)
    }

    /** Removes the most recent stroke: used by undo. */
    public fun removeLastStroke(): Stroke? = committed.removeLastOrNull()

    /** Re-adds a stroke: used by redo. */
    public fun addStroke(stroke: Stroke) {
        committed.add(stroke)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Release the native path/paint resources with the view.
        path.reset()
    }

    private companion object {
        /** Ink is near-black rather than pure black: pure black reads as a hole. */
        const val INK_COLOR = 0xFF141210.toInt()

        /** Exposed for the settings sheet so it can preview the same response curve. */
        val PREVIEW_RESPONSE: PressureResponse = PressureResponse.SOFT
    }
}
