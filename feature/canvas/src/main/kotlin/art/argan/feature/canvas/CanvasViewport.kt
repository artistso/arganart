package art.argan.feature.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import art.argan.core.ink.CanvasTransform

/**
 * Pan/zoom state for the infinite canvas.
 *
 * Kept separate from the composable so the gesture maths is unit-testable and so
 * P1's stylus listener and P2's ribbon editor can drive the same viewport.
 *
 * Only pan and zoom exist at P0. There is deliberately no `stroke`, `brush`,
 * `object` or `generation` member: adding one is the signal that a later phase
 * has leaked into the scaffold.
 */
@Stable
public class CanvasViewport(initial: CanvasTransform = CanvasTransform()) {
    public var transform: CanvasTransform by mutableStateOf(initial)
        private set

    public fun pan(dxScreen: Float, dyScreen: Float) {
        transform = transform.panBy(dxScreen, dyScreen)
    }

    public fun zoom(focusX: Float, focusY: Float, factor: Float) {
        transform = transform.zoomAbout(focusX, focusY, factor)
    }

    public fun reset() {
        transform = CanvasTransform()
    }
}

/** Remembers a [CanvasViewport] across recompositions. */
@Composable
public fun rememberCanvasViewport(): CanvasViewport = remember { CanvasViewport() }
