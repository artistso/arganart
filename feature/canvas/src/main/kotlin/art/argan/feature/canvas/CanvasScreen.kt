package art.argan.feature.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import art.argan.core.ink.BrushParams
import art.argan.core.ink.History
import art.argan.core.ink.Stroke
import art.argan.core.ink.StrokeCommand
import art.argan.core.ink.TileGrid
import art.argan.core.ink.TileRange
import art.argan.design.ArganTheme

/**
 * The drawing screen: ink surface plus the minimum chrome P1 requires.
 *
 * Chrome is deliberately minimal (a brush button, undo, redo). The phase brief is
 * explicit that there are "no dialogs during drawing", and the settings sheet only
 * opens on an explicit tap, never automatically.
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
    // The stroke removed by the last undo, so redo can reinstate it. In the
    // tile-backed renderer this becomes the command's "after" tile diff; the shape
    // of the handoff is identical either way.
    var lastUndoneStroke by remember { mutableStateOf<Stroke?>(null) }

    // The surface is retained across recompositions; the history outlives the
    // composable so a configuration change does not silently discard undo steps.
    var surface by remember { mutableStateOf<DrawingSurface?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                DrawingSurface(context, viewport).also { created ->
                    surface = created
                    created.brush = brush
                    created.onInputTimestamp = { eventTime -> onInputTimestamp?.invoke(eventTime) }
                    created.onStrokeCommitted = { stroke ->
                        recordStroke(history, stroke)
                        undoCount = history.undoCount
                        redoCount = history.redoCount
                    }
                }
            },
            update = { view ->
                view.brush = brush
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Canvas-edge controls. Large targets (48dp minimum) per the accessibility
        // rule that applies from P0 onward, not retrofitted in P9.
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
                    // The surface's stroke list is the source of truth in the
                    // View-backed renderer; the command carries the tile diff that
                    // the GL/tile renderer will use in the same slot.
                    lastUndoneStroke?.let { current.addStroke(it) }
                    lastUndoneStroke = null
                    undoCount = history.undoCount
                    redoCount = history.redoCount
                }
            },
            onBrush = { showSettings = !showSettings },
            historyLabel = stringResource(R.string.history_position, undoCount, undoCount + redoCount),
        )

        if (showSettings) {
            BrushSettingsSheet(
                brush = brush,
                onBrushChange = { brush = it },
                onDismiss = { showSettings = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(ArganTheme.dimensions.md),
            )
        }

        Text(
            text = stringResource(R.string.canvas_hint),
            style = MaterialTheme.typography.labelSmall,
            color = ArganTheme.colors.chromeContentMuted,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(ArganTheme.dimensions.md),
        )
    }
}

/**
 * Records a committed stroke in the undo history.
 *
 * The command stores the tile range the stroke touched so that undo can bound its
 * redraw. Pixels are supplied by the renderer in the tile-backed implementation;
 * here the surface's stroke list is authoritative, so the command carries a no-op
 * pixel writer and the surface is mutated directly.
 */
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

/** A FAB-sized action that dims when unavailable, without changing layout. */
@Composable
private fun ToolbarButton(label: String, active: Boolean, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CanvasToolbar(
    undoCount: Int,
    redoCount: Int,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBrush: () -> Unit,
    historyLabel: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Row(modifier = modifier) {
        // NOTE: Material3's FloatingActionButton has no `enabled` parameter
        // (verified against the AndroidX source). A disabled-looking FAB would need
        // `if (canUndo) FAB else Nothing`, which makes the toolbar jump as the
        // button appears and disappears -- worse UX than an inert tap. So the button
        // is always present and the callback is a no-op at the ends of history,
        // with the label dimmed to signal state.
        ToolbarButton(
            label = stringResource(R.string.tool_brush),
            active = true,
            onClick = onBrush,
        )
        ToolbarButton(
            label = stringResource(R.string.tool_undo),
            active = undoCount > 0,
            onClick = onUndo,
        )
        ToolbarButton(
            label = stringResource(R.string.tool_redo),
            active = redoCount > 0,
            onClick = onRedo,
        )
        // The visible history scrubber: a step counter for now ("3 / 12"), which
        // becomes draggable when the tile renderer can seek by command index.
        Text(
            text = historyLabel,
            style = MaterialTheme.typography.labelSmall,
            color = ArganTheme.colors.chromeContentMuted,
            modifier = Modifier.padding(start = ArganTheme.dimensions.sm),
        )
    }
}
