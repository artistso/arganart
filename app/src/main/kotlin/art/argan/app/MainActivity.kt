package art.argan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import art.argan.app.diagnostics.DebugFrameOverlay
import art.argan.app.diagnostics.FrameDiagnostics
import art.argan.core.diagnostics.FrameBudget
import art.argan.core.diagnostics.FrameWindow
import art.argan.design.ArganTheme
import art.argan.feature.canvas.CanvasScreen
import art.argan.feature.motion.MotionControls
import art.argan.feature.motion.MotionInspectorSheet
import art.argan.feature.motion.MotionRibbonOverlay
import art.argan.feature.motion.MotionRibbonState

/**
 * The app's single activity: the canvas, with the motion ribbon over it.
 *
 * The ribbon is composed here rather than inside `:feature:canvas` so that module keeps
 * knowing nothing about motion. The module graph points `:app` at both features and
 * nowhere else, which is what stops the canvas from growing a motion dependency later.
 */
public class MainActivity : ComponentActivity() {

    private lateinit var frameDiagnostics: FrameDiagnostics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        frameDiagnostics = FrameDiagnostics.attachTo(this)

        setContent {
            ArganTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    CanvasScreen(
                        onInputTimestamp = { eventTime ->
                            frameDiagnostics.recordInputTimestamp(eventTime)
                        },
                    )

                    val ribbonState = remember { MotionRibbonState() }
                    var inspectorOpen by remember { mutableStateOf(false) }

                    // Separate layer above the ink. A motion frame invalidates this
                    // Canvas only; the ink View behind it is not redrawn, which is the
                    // P2 requirement that ribbon editing cannot cost ink frames.
                    MotionRibbonOverlay(
                        state = ribbonState,
                        modifier = Modifier.fillMaxSize(),
                    )

                    MotionControls(
                        state = ribbonState,
                        onOpenInspector = { inspectorOpen = !inspectorOpen },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(ArganTheme.dimensions.md),
                    )

                    if (inspectorOpen) {
                        MotionInspectorSheet(
                            state = ribbonState,
                            onDismiss = { inspectorOpen = false },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(ArganTheme.dimensions.md),
                        )
                    }

                    if (BuildConfig.DEBUG) {
                        DebugOverlayHost(frameDiagnostics, Modifier.align(Alignment.BottomStart))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        frameDiagnostics.resume()
    }

    override fun onPause() {
        frameDiagnostics.pause()
        super.onPause()
    }

    override fun onDestroy() {
        frameDiagnostics.detach()
        super.onDestroy()
    }
}

/**
 * Feeds the JankStats reports into the overlay without recomposing the canvas.
 *
 * The overlay state is hoisted here (and only here) so a frame report does not
 * invalidate the canvas tree: measuring the canvas must not re-layout the canvas.
 */
@androidx.compose.runtime.Composable
private fun DebugOverlayHost(
    diagnostics: FrameDiagnostics,
    modifier: Modifier = Modifier,
) {
    var report by remember {
        mutableStateOf(
            FrameWindow(FrameBudget()).report(),
        )
    }

    DisposableEffect(diagnostics) {
        val handle = diagnostics.addListener { updated -> report = updated }
        onDispose { handle.close() }
    }

    DebugFrameOverlay(report = report, modifier = modifier)
}
