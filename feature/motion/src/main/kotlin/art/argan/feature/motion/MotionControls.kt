package art.argan.feature.motion

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import art.argan.design.ArganTheme

/**
 * The motion pill: play/pause and a way into the node inspector.
 *
 * Two buttons, not a transport bar. There is no scrub slider, no loop toggle and no
 * duration field, because time on the ribbon is expressed by position and a transport
 * bar is how a timeline gets reintroduced one control at a time. The ribbon itself is
 * the scrubber.
 */
@Composable
public fun MotionControls(
    state: MotionRibbonState,
    onOpenInspector: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ArganTheme.colors
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
            TextButton(
                onClick = { state.togglePlayback() },
                modifier = Modifier.size(width = 92.dp, height = ArganTheme.dimensions.minTouchTarget),
            ) {
                Text(
                    text = stringResource(
                        if (state.isPlaying) R.string.motion_pause else R.string.motion_play,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.paperInk,
                )
            }
            TextButton(
                onClick = onOpenInspector,
                modifier = Modifier.size(width = 92.dp, height = ArganTheme.dimensions.minTouchTarget),
            ) {
                Text(
                    text = stringResource(R.string.motion_ribbon),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.paperInk,
                )
            }
        }
    }
}
