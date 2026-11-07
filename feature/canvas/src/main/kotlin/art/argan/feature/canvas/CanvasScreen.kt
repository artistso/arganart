package art.argan.feature.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import art.argan.design.ArganTheme

/**
 * The P0 canvas screen: empty canvas plus a phase banner.
 *
 * The banner exists so a debug build can never be mistaken for a working drawing
 * app, and so manual verification of the phase boundary takes one glance. It is
 * not product UI and is removed in P1.
 */
@Composable
public fun CanvasScreen(modifier: Modifier = Modifier) {
    val viewport = rememberCanvasViewport()
    Box(modifier = modifier.fillMaxSize()) {
        InfiniteCanvas(viewport = viewport, modifier = Modifier.fillMaxSize())
        Text(
            text = stringResource(R.string.canvas_phase_banner),
            style = MaterialTheme.typography.labelSmall,
            color = ArganTheme.colors.chromeContentMuted,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(ArganTheme.dimensions.md),
        )
    }
}
