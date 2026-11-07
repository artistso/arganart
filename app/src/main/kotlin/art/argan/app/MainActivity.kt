package art.argan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

/**
 * The app's single activity.
 *
 * At P0 it launches straight to the empty infinite canvas. Navigation exists as
 * a seam (P1 adds tools, P2 the ribbon) but there is exactly one destination, so
 * no navigation library is introduced yet.
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
