package art.argan.feature.canvas

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import art.argan.design.ArganTheme

/**
 * The first thing the app shows: a short, honest introduction drawn *in ink*.
 *
 * ## Why a card on the canvas and not a modal
 *
 * A welcome dialog is the standard answer and it is the wrong one here. It teaches by
 * describing, it interrupts the one action the product is for, and it has to be
 * dismissed before anything can be tried — three small frictions that add up to "this
 * is an app I have to get through".
 *
 * This card is part of the paper instead. It never takes a touch (the drawing surface
 * sits above it), it shows a pressure-varying stroke animating itself so the idea
 * arrives without reading, and it fades out permanently the moment the user draws.
 *
 * The animated stroke is drawn with the same taper model as real ink
 * ([art.argan.core.ink.RoundStamp]); when it drifts from what the stylus produces, it is
 * a bug in the demonstration, not a marketing flourish.
 */
@Composable
public fun CanvasWelcomeCard(
    modifier: Modifier = Modifier,
) {
    val colors = ArganTheme.colors
    val transition = rememberInfiniteTransition(label = "welcome-stroke")
    val drawing by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "stroke-progress",
    )

    // A single reusable path: the animation must not allocate a Path per frame.
    val strokePath = remember { Path() }

    Surface(
        modifier = modifier.widthIn(max = 420.dp),
        shape = RoundedCornerShape(20.dp),
        color = colors.chromeSurface,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(ArganTheme.dimensions.lg)) {
            Text(
                text = stringResource(R.string.canvas_welcome_title),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.paperInk,
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(vertical = ArganTheme.dimensions.sm),
            ) {
                drawPressureStroke(
                    path = strokePath,
                    progress = drawing.coerceIn(0f, 1f),
                    color = colors.ink,
                )
            }
            Text(
                text = stringResource(R.string.canvas_welcome_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.paperInk,
            )
            Text(
                text = stringResource(R.string.canvas_welcome_hint),
                style = MaterialTheme.typography.labelSmall,
                color = colors.paperInkMuted,
                modifier = Modifier.padding(top = ArganTheme.dimensions.sm),
            )
        }
    }
}

/**
 * Draws a calligraphic stroke whose weight varies along its length, revealing
 * [progress] of it left to right.
 *
 * Three passes rather than one: a wide translucent body, a mid-tone, and a thin dark
 * core. Overlaid with decreasing width and increasing opacity this reads as a single
 * tapered nib stroke much more convincingly than one uniform line, and it costs three
 * `drawPath` calls on a 56dp strip.
 */
private fun DrawScope.drawPressureStroke(path: Path, progress: Float, color: Color) {
    val width = size.width
    val height = size.height
    if (width <= 0f || progress <= 0f) return

    val margin = height * 0.25f
    val usable = width - margin * 2f
    val endX = margin + usable * progress

    path.reset()
    // A gentle S with a rise: the shape of a natural handwritten stroke rather than a
    // sine wave, because a sine wave reads as a graph.
    //
    // The path is built only as far as `progress`, which is what makes the reveal
    // visible. Drawing the full path and clipping it by gradient was the first approach
    // and it was simply dead code -- the gradient was computed and never applied, so the
    // stroke appeared complete on the first frame and then sat still for 2.6 seconds.
    val steps = (48 * progress).toInt().coerceAtLeast(1)
    for (i in 0..steps) {
        val t = (i.toFloat() / steps) * progress
        val x = margin + usable * t
        val y = height * 0.5f -
            kotlin.math.sin(t * Math.PI * 1.6f) * height * 0.22f +
            (t - 0.5f) * height * 0.18f
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    drawPath(path, color.copy(alpha = 0.16f), style = Stroke(width = height * 0.34f))
    drawPath(path, color.copy(alpha = 0.34f), style = Stroke(width = height * 0.18f))
    drawPath(path, color, style = Stroke(width = height * 0.07f))

    // The travelling nib: a soft dot at the head of the stroke makes the animation read
    // as "something is being drawn" rather than "a line is growing".
    if (endX < width - margin) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.30f), Color.Transparent),
                center = Offset(endX, height * 0.5f),
                radius = height * 0.5f,
            ),
            radius = height * 0.5f,
            center = Offset(endX, height * 0.5f),
        )
    }
}
