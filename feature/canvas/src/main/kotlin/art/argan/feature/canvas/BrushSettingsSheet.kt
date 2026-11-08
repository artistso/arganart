package art.argan.feature.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import art.argan.core.ink.BrushParams
import art.argan.core.ink.PressureResponse
import art.argan.design.ArganTheme

/**
 * Brush settings, with a **live pressure-response preview**.
 *
 * The preview is the point of this sheet. A curve described in words ("soft start,
 * fuller body") is meaningless to a user choosing between three of them; a stroke
 * drawn through the actual response function at the actual size is immediately
 * comparable. It uses the same `PressureResponse` code the renderer uses, so the
 * preview cannot drift from reality.
 */
@Composable
public fun BrushSettingsSheet(
    brush: BrushParams,
    onBrushChange: (BrushParams) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = ArganTheme.dimensions.sm,
    ) {
        Column(modifier = Modifier.padding(ArganTheme.dimensions.md)) {
            Text(
                text = stringResource(R.string.canvas_brush_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(ArganTheme.dimensions.md))

            PressurePreview(brush)

            Spacer(Modifier.height(ArganTheme.dimensions.md))

            SliderRow(
                label = stringResource(R.string.canvas_brush_size),
                value = brush.size,
                range = 1f..40f,
                onChange = { onBrushChange(brush.copy(size = it)) },
            )
            SliderRow(
                label = stringResource(R.string.canvas_brush_smoothing),
                value = brush.smoothingStrength,
                range = 0f..1f,
                onChange = { onBrushChange(brush.copy(smoothingStrength = it)) },
            )
            SliderRow(
                label = stringResource(R.string.canvas_brush_stabilisation),
                value = brush.stabilizerStrength,
                range = 0f..1f,
                onChange = { onBrushChange(brush.copy(stabilizerStrength = it)) },
            )

            Spacer(Modifier.height(ArganTheme.dimensions.sm))
            Text(
                text = stringResource(R.string.canvas_brush_response),
                style = MaterialTheme.typography.labelMedium,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                PressureResponse.entries.forEach { response ->
                    Button(
                        onClick = { onBrushChange(brush.copy(pressureResponse = response)) },
                        modifier = Modifier.padding(end = ArganTheme.dimensions.xs),
                    ) {
                        Text(response.name.lowercase())
                    }
                }
            }

            Spacer(Modifier.height(ArganTheme.dimensions.md))
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.canvas_brush_done))
            }
        }
    }
}

/**
 * Draws one stroke whose width follows the selected pressure response.
 *
 * Simulated pressure rises and falls across the stroke, so all three curves are
 * distinguishable at a glance.
 */
@Composable
private fun PressurePreview(brush: BrushParams, modifier: Modifier = Modifier) {
    val ink = ArganTheme.colors.ink
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dpValue()),
    ) {
        val steps = 64
        var previous = Offset.Zero
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = size.width * t
            // A smooth pressure arc peaking in the middle, like a natural stroke.
            val simulatedPressure = 1f - kotlin.math.abs(0.5f - t) * 2f
            val radius = brush.size * 0.5f *
                (0.25f + 0.75f * brush.pressureResponse.map(simulatedPressure))
            val y = size.height * 0.5f
            val current = Offset(x, y)
            if (i > 0) {
                drawLine(
                    color = ink,
                    start = previous,
                    end = current,
                    strokeWidth = radius * 2f,
                    cap = StrokeCap.Round,
                )
            }
            previous = current
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(120.dpValue()))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Small helper so this file reads without importing Dp everywhere. */
private fun Int.dpValue(): androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(this.toFloat())
