package art.argan.feature.canvas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import art.argan.core.ink.BrushParams
import art.argan.core.ink.CanvasTransform
import art.argan.core.ink.History
import art.argan.core.ink.Stroke
import art.argan.core.ink.StrokeCommand
import art.argan.core.ink.TileGrid
import art.argan.core.ink.TileRange
import art.argan.design.ArganTheme

/**
 * The drawing screen.
 *
 * ## Design notes (P2 review response)
 *
 * The first version of this screen was a near-black rectangle with three floating
 * text chips in a row. It worked, and it looked like scaffolding — because it was:
 * the canvas had no visible structure, nothing explained the stylus, and the chrome
 * used internal words. Three changes, none of which add capability:
 *
 * 1. The default theme is paper, not the system's dark preference. A dark surface is
 *    a choice for a dark room, not the thing the product should open to.
 * 2. The canvas draws the *tile lattice* faintly — the same 512-unit tiles the model
 *    already uses. It is not decoration: it is the actual spatial structure of the
 *    document, and it gives an empty canvas somewhere to look.
 * 3. A welcome card [CanvasWelcomeCard] sits on the paper, showing a pressure-varying
 *    stroke drawing itself. It never intercepts a touch and it disappears for good on
 *    the first stroke — teaching by showing instead of by describing.
 */
@Composable
public fun CanvasScreen(
    modifier: Modifier = Modifier,
    /** Receives stylus event timestamps for input-to-photon measurement. */
    onInputTimestamp: ((Long) -> Unit)? = null,
) {
    val viewport = rememberCanvasViewport()
    var brush by remember { mutableStateOf(BrushParams.DEFAULT) }
    var showSettings by remember { mutableStateOf(false) }
    var undoCount by remember { mutableStateOf(0) }
    var redoCount by remember { mutableStateOf(0) }
    val history = remember { History() }
    var lastUndoneStroke by remember { mutableStateOf<Stroke?>(null) }
    var surface by remember { mutableStateOf<DrawingSurface?>(null) }

    // The welcome card is shown until the first committed stroke. Kept as state rather
    // than derived from `undoCount` so that undoing every stroke does not bring the
    // introduction back to someone who has already read it.
    var hasDrawn by remember { mutableStateOf(false) }

    val colors = ArganTheme.colors

    Box(modifier = modifier.fillMaxSize()) {
        // The paper. Drawn before the surface so ink sits on top of it, and clipped to
        // the visible area so an infinite lattice costs the same as a small one.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = colors.canvasBackground)
            drawTileLattice(
                transform = viewport.transform,
                color = colors.canvasGrid,
            )
        }

        // Ink colour follows the theme. Without this the surface drew near-black ink on
        // the dark canvas, i.e. nothing visible at all.
        val inkColorInt = colors.ink.toArgb()

        AndroidView(
            factory = { context ->
                DrawingSurface(context, viewport).also { created ->
                    surface = created
                    created.inkColor = inkColorInt
                    created.brush = brush
                    created.onInputTimestamp = { eventTime -> onInputTimestamp?.invoke(eventTime) }
                    created.onStrokeCommitted = { stroke ->
                        hasDrawn = true
                        recordStroke(history, stroke)
                        undoCount = history.undoCount
                        redoCount = history.redoCount
                    }
                }
            },
            update = { view ->
                view.brush = brush
                view.inkColor = inkColorInt
            },
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = !hasDrawn,
            enter = fadeIn(tween(600)),
            exit = fadeOut(tween(400)),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(ArganTheme.dimensions.xl),
        ) {
            CanvasWelcomeCard()
        }

        CanvasToolbar(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(ArganTheme.dimensions.md),
            undoCount = undoCount,
            redoCount = redoCount,
            onUndo = {
                val current = surface
                if (current != null && history.undo() != null) {
                    lastUndoneStroke = current.removeLastStroke()
                    undoCount = history.undoCount
                    redoCount = history.redoCount
                }
            },
            onRedo = {
                val current = surface
                if (current != null && history.redo() != null) {
                    lastUndoneStroke?.let { current.addStroke(it) }
                    lastUndoneStroke = null
                    undoCount = history.undoCount
                    redoCount = history.redoCount
                }
            },
            onBrush = { showSettings = !showSettings },
        )

        AnimatedVisibility(
            visible = showSettings,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BrushSettingsSheet(
                brush = brush,
                onBrushChange = { brush = it },
                onDismiss = { showSettings = false },
                modifier = Modifier.padding(ArganTheme.dimensions.md),
            )
        }
    }
}

/**
 * The tile lattice, drawn as faint points at tile corners.
 *
 * Points rather than lines: a full grid at 100% zoom reads as graph paper and fights
 * the drawing, whereas a point at each tile corner (every 512 canvas units) is quiet at
 * any zoom and tells you where you are when you pan into nothing.
 */
private fun DrawScope.drawTileLattice(
    transform: CanvasTransform,
    color: Color,
) {
    val step = TileGrid.TILE_SIZE * transform.scale
    // Below this spacing the lattice becomes noise; above it, the screen holds too few
    // points to cover. An infinite loop is impossible because both bounds are checked.
    if (step < 24f || step > size.width * 4f) return

    val firstX = ((0f - transform.translateX) / step).toInt() - 1
    val firstY = ((0f - transform.translateY) / step).toInt() - 1
    val columns = (size.width / step).toInt() + 3
    val rows = (size.height / step).toInt() + 3
    val radius = 1.5f

    for (row in 0 until rows) {
        if (row > 200) break
        val y = (firstY + row) * step + transform.translateY
        if (y < -radius || y > size.height + radius) continue
        for (column in 0 until columns) {
            if (column > 200) break
            val x = (firstX + column) * step + transform.translateX
            if (x < -radius || x > size.width + radius) continue
            drawCircle(color = color, radius = radius, center = Offset(x, y))
        }
    }
}

private fun recordStroke(history: History, stroke: Stroke) {
    val range: TileRange = TileGrid.rangeFor(stroke.bounds())
    history.push(
        StrokeCommand(
            label = "stroke",
            affectedTiles = range,
            tileBefores = emptyMap(),
            tileAfters = emptyMap(),
            writeTile = { _, _ -> },
        ),
    )
}

/**
 * The canvas toolbar: three labelled actions and a step counter.
 *
 * Replaces a row of `FloatingActionButton`s containing raw text. FABs are for one
 * primary action; using three of them as list items is why the previous version read as
 * a prototype. A single tonal surface grouping three text buttons says "these belong
 * together", and the labels are actions ("Undo"), not nouns from the code ("brush").
 */
@Composable
private fun CanvasToolbar(
    undoCount: Int,
    redoCount: Int,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBrush: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ArganTheme.colors
    val canUndo = undoCount > 0
    val canRedo = redoCount > 0

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = colors.chromeSurface,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = ArganTheme.dimensions.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarTextButton(
                label = stringResource(R.string.canvas_brush),
                onClick = onBrush,
                enabled = true,
            )
            ToolbarDivider()
            ToolbarTextButton(
                label = stringResource(R.string.canvas_undo),
                onClick = onUndo,
                enabled = canUndo,
            )
            ToolbarTextButton(
                label = stringResource(R.string.canvas_redo),
                onClick = onRedo,
                enabled = canRedo,
            )
            // The step counter is the visible history state. It sits in the same pill so
            // "how far back can I go" is answered where the buttons are, not in a corner.
            Text(
                text = stringResource(R.string.canvas_history_position, undoCount, undoCount + redoCount),
                style = MaterialTheme.typography.labelSmall,
                color = colors.paperInkMuted,
                modifier = Modifier.padding(horizontal = ArganTheme.dimensions.sm),
            )
        }
    }
}

@Composable
private fun ToolbarTextButton(label: String, onClick: () -> Unit, enabled: Boolean) {
    val colors = ArganTheme.colors
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(width = 84.dp, height = ArganTheme.dimensions.minTouchTarget)
            .semantics { contentDescription = label },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color = if (enabled) colors.paperInk else colors.paperInkMuted.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun ToolbarDivider() {
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 20.dp)
            .padding(vertical = 2.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = ArganTheme.colors.canvasGrid)
        }
    }
}
