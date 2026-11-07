package art.argan.app.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import art.argan.core.diagnostics.FrameReport
import art.argan.design.ArganTheme

/**
 * The debug-only frame-time overlay, toggled by a hidden gesture (P0 brief).
 *
 * **Hidden gesture:** three rapid taps in the bottom-left corner, inside
 * [HOTSPOT_DP]. Chosen because it cannot be triggered by drawing, panning or
 * two-finger zoom, and because it needs no chrome and therefore does not leak
 * developer affordances into the product.
 *
 * Debug builds only: the caller gates this on `BuildConfig.DEBUG`. A release build
 * must not be able to show a frame counter to a user.
 */
@Composable
public fun DebugFrameOverlay(
    report: FrameReport,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    var tapCount by remember { mutableStateOf(0) }
    var lastTapMillis by remember { mutableStateOf(0L) }

    Column(modifier = modifier) {
        // The hotspot is a fixed-size transparent box: tapping it is the gesture,
        // not tapping the overlay text, so the overlay can be dismissed the same way.
        Text(
            text = "",
            modifier = Modifier
                .padding(ArganTheme.dimensions.md)
                .clip(RoundedCornerShape(ArganTheme.dimensions.sm))
                .pointerInput(Unit) {
                    detectTapGestures {
                        val now = System.currentTimeMillis()
                        tapCount = if (now - lastTapMillis <= TAP_WINDOW_MILLIS) tapCount + 1 else 1
                        lastTapMillis = now
                        if (tapCount >= TAPS_TO_TOGGLE) {
                            visible = !visible
                            tapCount = 0
                        }
                    }
                },
        )

        if (visible) {
            Column(
                modifier = Modifier
                    .padding(ArganTheme.dimensions.md)
                    .clip(RoundedCornerShape(ArganTheme.dimensions.sm))
                    .background(OverlayBackground)
                    .padding(ArganTheme.dimensions.sm),
            ) {
                OverlayLine(report.summary())
                OverlayLine("frame med=${fmt(report.medianFrameMs)}ms p95=${fmt(report.p95FrameMs)}ms")
                OverlayLine("over 8ms budget: ${report.overInkBudgetCount}/${report.sampleCount}")
                OverlayLine("i2p: ${report.medianInputToPhotonMs?.let(::fmt) ?: "n/a (P1)"}")
            }
        }
    }
}

@Composable
private fun OverlayLine(text: String) {
    Text(
        text = text,
        color = OverlayContent,
        // Monospace so digits do not shift horizontally as values change; a
        // jittering overlay is unreadable and is itself a source of misreads.
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    )
}

private fun fmt(value: Double?): String = value?.let { String.format("%.1f", it) } ?: "-"

/** Number of taps in the corner required to toggle the overlay. */
private const val TAPS_TO_TOGGLE = 3

/** Max gap between taps for them to count as one gesture. */
private const val TAP_WINDOW_MILLIS = 600L

/**
 * Deliberately literal colours: this is a developer tool, not product chrome.
 * It must stay legible over any canvas colour and must not participate in theming.
 */
private val OverlayBackground = Color(0xCC000000)
private val OverlayContent = Color(0xFF7CFFB2)
