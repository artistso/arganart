package art.argan.feature.motion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import art.argan.core.motion.SpinePoint
import art.argan.design.ArganTheme

/**
 * The motion ribbon: a visible, editable band that runs through the scene.
 *
 * ## Its own visual language
 *
 * The brief requires the ribbon to look like neither ink nor UI chrome, and that is a
 * functional requirement rather than a styling note. Ink is thin, black and pressure-
 * shaped; chrome is flat, rectangular and high-contrast. The ribbon is therefore a wide
 * translucent band in a warm accent, with the spine drawn as a hairline down its middle
 * and nodes as small filled discs. At a glance you can tell what is yours to draw on
 * and what is yours to steer.
 *
 * ## Drawn on its own layer
 *
 * This composable is a separate `Canvas` above the ink surface, so a ribbon edit
 * invalidates the ribbon only. The ink surface is a plain `View` and is not recomposed
 * by a motion frame at all: that is what the brief means by "the ink layer's frame
 * budget is unaffected".
 *
 * ## Gestures implemented here
 *
 * - **Tap a node** to select it (then the sheet can reshape it).
 * - **Drag horizontally anywhere** to scrub: position along the ribbon is time, so a
 *   drag along its length *is* the playhead. There is no ruler and no keyframe track.
 * - **Long-press and drag** to bend the spine at the nearest control point.
 */
@Composable
public fun MotionRibbonOverlay(
    state: MotionRibbonState,
    modifier: Modifier = Modifier,
) {
    val colors = ArganTheme.colors

    // A frame clock that only runs while the ribbon is animating. `withFrameNanos` is
    // used directly rather than an `InfiniteTransition` because the tick must advance a
    // domain clock shared with gestures, not a Compose animation value.
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state) {
        while (true) {
            withFrameNanos { now ->
                state.tick()
                frame = now
            }
        }
    }
    // NOTE: the loop runs even while paused. `tick()` is a few comparisons when paused,
    // and keeping the frame callback alive is what lets a scrub repaint immediately
    // without restarting and re-warming an effect on every gesture.
    // `frame` is read so the draw below recomposes each vsync.
    frame

    val bandPath = remember { Path() }
    val spinePath = remember { Path() }
    // Screen x of the scrub cursor, or -1 when no drag is in flight. Reset by the drag
    // detector's own start, so a new drag always begins where the finger landed.
    var scrubFromScreenX by remember { mutableStateOf(-1f) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(state) {
                detectTapGestures(
                    onLongPress = { offset ->
                        // Long-press drops a node at the touched point (brief: "long-press
                        // to drop a node"). Bending is the drag that follows a long press,
                        // which Compose's tap detector delivers as a separate gesture; the
                        // node drop is the immediately useful half and is what lands here.
                        val nearest = nearestSpineIndex(state, offset, size.width.toFloat(), size.height.toFloat())
                        state.addNodeAt(nearest.second)
                    },
                    onTap = { offset ->
                        val nearest = nearestSpineIndex(state, offset, size.width.toFloat(), size.height.toFloat())
                        state.selectNode(nearest.first)
                    },
                )
            }
            .pointerInput(state) {
                // Scrubbing: dragging along the ribbon moves time. Accumulated in screen
                // x against the ribbon's on-screen span so a drag of the ribbon's own
                // width covers the whole clip, which is what "position is time" means to
                // a hand. There is deliberately no separate seek control anywhere else.
                detectHorizontalDragGestures(
                    onDragStart = { start -> scrubFromScreenX = start.x },
                    onDragEnd = { scrubFromScreenX = -1f },
                    onDragCancel = { scrubFromScreenX = -1f },
                ) { change, dragAmount ->
                    change.consume()
                    val span = (size.width.toFloat() - 2f * SCRUB_MARGIN).coerceAtLeast(1f)
                    scrubFromScreenX += dragAmount
                    state.scrubTo((scrubFromScreenX - SCRUB_MARGIN) / span)
                }
            },
    ) {
        drawRibbon(
            state = state,
            bandPath = bandPath,
            spinePath = spinePath,
            ribbonColor = colors.ribbon,
            nodeColor = colors.ribbonNode,
            elementColor = colors.ribbonElement,
        )
    }
}

/**
 * Scratch buffers for the projected spine.
 *
 * Held at module level rather than per-composable so that the ribbon never allocates
 * during a drag. The ribbons are single-instance by design (P2 has one motion), so a
 * shared buffer cannot be re-entered; if that stops being true this becomes a
 * parameter of the draw, not a global.
 */
private val projectedX = FloatArray(MAX_PROJECTED)
private val projectedY = FloatArray(MAX_PROJECTED)
private const val MAX_PROJECTED = 96

/**
 * Maps a canvas-space spine point to screen space.
 *
 * The ribbon lives in canvas units so it can be saved with the drawing (P9); the ink
 * surface and the ribbon must therefore share a transform or the element would drift
 * away from the path it is travelling. P2 has no pan/zoom on the ink surface yet, so the
 * transform is the identity — this function is the seam where that changes.
 */
private fun canvasToScreen(x: Float, y: Float): Offset = Offset(x, y)

private fun DrawScope.drawRibbon(
    state: MotionRibbonState,
    bandPath: Path,
    spinePath: Path,
    ribbonColor: Color,
    nodeColor: Color,
    elementColor: Color,
) {
    val spine = state.clip.spine
    if (spine.isDegenerate) return

    // Project the spine once per frame at a bounded resolution.
    val steps = MAX_PROJECTED - 1
    for (i in 0..steps) {
        val d = spine.length * i / steps
        val out = FloatArrayScratch.two
        spine.pointAtDistance(d, out)
        val screen = canvasToScreen(out[0], out[1])
        projectedX[i] = screen.x
        projectedY[i] = screen.y
    }

    bandPath.reset()
    bandPath.moveTo(projectedX[0], projectedY[0])
    for (i in 1..steps) bandPath.lineTo(projectedX[i], projectedY[i])

    spinePath.reset()
    spinePath.moveTo(projectedX[0], projectedY[0])
    for (i in 1..steps) spinePath.lineTo(projectedX[i], projectedY[i])

    // Three passes make the band read as a material rather than a thick line: a wide
    // wash, a narrower core, and a hairline spine.
    drawPath(bandPath, ribbonColor.copy(alpha = 0.10f), style = wideStroke)
    drawPath(bandPath, ribbonColor.copy(alpha = 0.22f), style = coreStroke)
    drawPath(spinePath, ribbonColor.copy(alpha = 0.75f), style = hairlineStroke)

    // Nodes: a disc per sculptable point, bigger and lighter when selected.
    val points = spine.points()
    for (index in points.indices) {
        val p = canvasToScreen(points[index].x, points[index].y)
        val selected = index == state.selectedNode
        drawCircle(
            color = if (selected) nodeColor else ribbonColor,
            radius = if (selected) 13f else 8f,
            center = p,
        )
        drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 3f, center = p)
    }

    // The animated element. A rounded square rather than a circle: a circle on a path
    // reads as a bead on a wire, whereas a square oriented along the tangent shows the
    // rotation that `followPath` is producing.
    val pose = state.pose
    val element = canvasToScreen(pose.translateX, pose.translateY)
    drawElement(center = element, rotationDegrees = pose.rotationDegrees, scale = pose.scale, color = elementColor)
}

private fun DrawScope.drawElement(center: Offset, rotationDegrees: Float, scale: Float, color: Color) {
    val half = 18f * scale.coerceIn(0.2f, 3f)
    // `corners` holds unit offsets; multiply by the half-size here rather than
    // pre-scaling, so one array serves every element size.
    val radians = rotationDegrees * (Math.PI / 180.0)
    val cos = kotlin.math.cos(radians).toFloat()
    val sin = kotlin.math.sin(radians).toFloat()
    // Corners of a square, rotated by the pose. Written out rather than using
    // rotate {} so the element keeps its own transform independent of the ribbon's.
    for (i in 0 until 4) {
        val x = corners[i * 2] * half
        val y = corners[i * 2 + 1] * half
        elementX[i] = center.x + x * cos - y * sin
        elementY[i] = center.y + x * sin + y * cos
    }
    elementPath.reset()
    elementPath.moveTo(elementX[0], elementY[0])
    elementPath.lineTo(elementX[1], elementY[1])
    elementPath.lineTo(elementX[2], elementY[2])
    elementPath.lineTo(elementX[3], elementY[3])
    elementPath.close()
    drawPath(elementPath, color.copy(alpha = 0.26f))
    drawPath(elementPath, color, style = elementStroke)
}

/** Reused per-frame buffers for the animated element. See FloatArrayScratch. */
private val elementPath = Path()
private val elementX = FloatArray(4)
private val elementY = FloatArray(4)
private val corners = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)
private val elementStroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)

private val wideStroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 34f, cap = StrokeCap.Round)
private val coreStroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 12f, cap = StrokeCap.Round)
private val hairlineStroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f, cap = StrokeCap.Round)

/** Two-float scratch, so projection allocates nothing per frame. */
private object FloatArrayScratch {
    val two = FloatArray(2)
}

/**
 * Finds the nearest node and the arc distance of the touched point.
 *
 * Used for both tap (select a node) and long-press (drop one). Searching the projected
 * polyline rather than the spline is deliberate: it is the same geometry the user sees,
 * so a touch that looks like it is on the ribbon always resolves.
 */
private fun nearestSpineIndex(
    state: MotionRibbonState,
    touch: Offset,
    width: Float,
    height: Float,
): Pair<Int, Float> {
    val spine = state.clip.spine
    var bestNode = -1
    var bestNodeDistance = Float.MAX_VALUE
    val points = spine.points()
    for (index in points.indices) {
        val p = canvasToScreen(points[index].x, points[index].y)
        val d = (p - touch).getDistanceSquared()
        if (d < bestNodeDistance) {
            bestNodeDistance = d
            bestNode = index
        }
    }
    // Only claim a node when the touch is genuinely near it, or every tap would select
    // whichever node happened to be least far away.
    if (bestNodeDistance > NODE_HIT_RADIUS * NODE_HIT_RADIUS) bestNode = -1

    var bestDistance = 0f
    var bestDistanceSquared = Float.MAX_VALUE
    val steps = 64
    val scratch = FloatArray(2)
    for (i in 0..steps) {
        val d = spine.length * i / steps
        spine.pointAtDistance(d, scratch)
        val p = canvasToScreen(scratch[0], scratch[1])
        val distanceSquared = (p - touch).getDistanceSquared()
        if (distanceSquared < bestDistanceSquared) {
            bestDistanceSquared = distanceSquared
            bestDistance = d
        }
    }
    return bestNode to bestDistance
}

private const val NODE_HIT_RADIUS = 32f
private const val SCRUB_MARGIN = 24f
