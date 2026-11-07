package art.argan.feature.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import art.argan.core.ink.CanvasTransform
import art.argan.design.ArganTheme

/**
 * The empty infinite canvas (P0 deliverable).
 *
 * Pan and zoom only. There is no drawing, no stylus handling, no strokes, no
 * motion and no AI in this file or any caller of it — that is the phase boundary,
 * verified by `tools/check-architecture.py` as well as by review.
 *
 * The grid is drawn in canvas space so panning and zooming are visibly infinite
 * and anchored under the fingers; it is intentionally the only visual content.
 */
@Composable
public fun InfiniteCanvas(
    viewport: CanvasViewport,
    modifier: Modifier = Modifier,
) {
    // Tokens are resolved here, in composition: `Canvas`'s content lambda is a
    // DrawScope block, not a composable scope, so it cannot read CompositionLocals.
    val background = ArganTheme.colors.canvasBackground
    val gridColor = ArganTheme.colors.canvasGrid

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures(
                    panZoomLock = false,
                ) { centroid, pan, zoom, _ ->
                    // Pan first, then zoom about the centroid: applying them in
                    // this order keeps the point under the fingers stationary
                    // even when a single gesture does both in one frame.
                    if (pan.x != 0f || pan.y != 0f) {
                        viewport.pan(pan.x, pan.y)
                    }
                    if (zoom != 1f) {
                        viewport.zoom(centroid.x, centroid.y, zoom)
                    }
                }
            },
    ) {
        drawRect(color = background, size = size)
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        // Reading the viewport's state inside the draw block is what makes the
        // canvas redraw on pan/zoom without recomposing the composable tree.
        drawGrid(viewport.transform, size, gridColor)
    }
}

private const val GRID_STEP_CANVAS_UNITS = 80f
private const val GRID_LINE_WIDTH = 1f

/**
 * Draws the canvas grid.
 *
 * A plain function (not a composable) so the geometry can be reasoned about. It
 * walks only the visible range, so cost is bounded by screen size rather than by
 * zoom level.
 */
private fun DrawScope.drawGrid(transform: CanvasTransform, canvasSize: Size, color: Color) {
    val step = GRID_STEP_CANVAS_UNITS * transform.scale
    if (step < 4f) return // below this the grid is visual noise; draw nothing.

    var x = transform.translateX % step
    if (x < 0f) x += step
    while (x < canvasSize.width) {
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, canvasSize.height),
            strokeWidth = GRID_LINE_WIDTH,
        )
        x += step
    }

    var y = transform.translateY % step
    if (y < 0f) y += step
    while (y < canvasSize.height) {
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(canvasSize.width, y),
            strokeWidth = GRID_LINE_WIDTH,
        )
        y += step
    }
}
