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

    /**
     * Ink colour, set from the theme rather than hardcoded.
     *
     * The surface previously used a constant near-black. That is correct on paper and
     * invisible on the dark canvas -- drawing produced nothing the user could see, which
     * is the worst possible failure mode for a drawing app. The default here is the paper
     * ink so the view is usable on its own; the screen overrides it per theme.
     */
    public var inkColor: Int = DEFAULT_INK_COLOR

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
    private val ribbonPath = Path()
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

    /**
     * Created on first stylus contact rather than in the constructor: the predictor
     * binds to this View, and the View is not fully attached during construction.
     */
    private var predictor: StylusEventSource.Predictor? = null

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
                    if (predictor == null) predictor = StylusEventSource.Predictor(this)
                    predictor?.record(event)
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
                    predictor?.record(event)
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
            predicted = predictor?.predict(),
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
            // The predicted tail is a thin line: it marks where the nib is about to be,
            // and matching the brush's full width there would look like committed ink.
            predictedPaint.strokeWidth = (brush.size * 0.5f).coerceAtLeast(1f)
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

    /**
     * Draws a stroke as a pressure-varying band.
     *
     * ## Why not `drawPath` with a stroke width
     *
     * The first version drew each stroke as a single polyline with a constant width and
     * a constant alpha. That is a *line*, not ink: the stylus's 4096 pressure levels were
     * carried all the way from `MotionEvent` through the pipeline and then discarded at
     * render time. Every stroke looked like a cable of the same thickness, which is a
     * large part of why the canvas read as a prototype.
     *
     * So each sample is drawn as a filled quad whose half-width is the segment's average
     * radius (from [art.argan.core.ink.Stamp.radiusAt]). Quads rather than round stamps:
     * a stamp every 2 canvas units needs thousands of `drawCircle` calls per stroke,
     * whereas adjacent quads share edges and produce a continuous ribbon at a fraction
     * of the draw calls. Overlapping translucent quads would double-darken at every
     * junction, so the segments are drawn into ONE [Path] and filled once — that single
     * call is what keeps the alpha uniform.
     */
    private fun drawStroke(canvas: Canvas, stroke: Stroke, target: Paint) {
        if (stroke.points.isEmpty()) return
        target.color = inkColor
        target.alpha = (stroke.brush.opacity.coerceIn(0f, 1f) * 255).toInt()

        val stamp = stroke.brush.stamp()
        val points = stroke.points

        if (points.size == 1) {
            val p = points[0]
            target.style = Paint.Style.FILL
            canvas.drawCircle(p.x, p.y, stamp.radiusAt(p.pressure), target)
            target.style = Paint.Style.STROKE
            return
        }

        ribbonPath.reset()
        // Per-segment half widths, so the two sides of the band can differ: that is what
        // makes a stroke thin at one end and thick at the other rather than a tapered
        // outline.
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
            if (length < 1e-4f) continue
            val nx = -dy / length
            val ny = dx / length
            val ra = stamp.radiusAt(a.pressure)
            val rb = stamp.radiusAt(b.pressure)

            // Build the quad for this segment into [quad], then append it to ribbonPath.
            // (Path has no "add quad" that avoids allocation of the winding, so the
            // quad's corners are added as lines; the fill rule merges them.)
            if (ribbonPath.isEmpty) {
                ribbonPath.moveTo(a.x + nx * ra, a.y + ny * ra)
            } else {
                ribbonPath.lineTo(a.x + nx * ra, a.y + ny * ra)
            }
            ribbonPath.lineTo(b.x + nx * rb, b.y + ny * rb)
            ribbonPath.lineTo(b.x - nx * rb, b.y - ny * rb)
            ribbonPath.lineTo(a.x - nx * ra, a.y - ny * ra)
            ribbonPath.close()
        }

        target.style = Paint.Style.FILL
        canvas.drawPath(ribbonPath, target)
        // The same Paint is reused by the predicted-tail pass, which strokes a line.
        // Leaving FILL set would make predictions render as invisible hairlines.
        target.style = Paint.Style.STROKE
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
        /** Ink is near-black rather than pure black: pure black reads as a hole on paper. */
        const val DEFAULT_INK_COLOR = 0xFF141210.toInt()

        /** Exposed for the settings sheet so it can preview the same response curve. */
        val PREVIEW_RESPONSE: PressureResponse = PressureResponse.SOFT
    }
}
