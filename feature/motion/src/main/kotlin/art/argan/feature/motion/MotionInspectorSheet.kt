package art.argan.feature.motion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import art.argan.core.motion.MotionNode
import art.argan.core.motion.SpringDynamics
import art.argan.design.ArganTheme

/**
 * Shapes the selected node of the ribbon: its feel, the speed through the next stretch,
 * and how long it hesitates.
 *
 * ## Why these three controls
 *
 * Each one maps to a gesture in the P2 brief (pinch a segment to speed it up, twist to
 * rotate, drop a node to shape its character). P2 ships the *model* for all of them and
 * the controls for the ones that are unambiguous with one finger; pinch and two-finger
 * twist need the canvas gesture arbitration that the ink surface owns, which is P9's
 * multi-gesture pass. Recording that here rather than shipping a pinch that fights the
 * stylus is the honest split.
 */
@Composable
public fun MotionInspectorSheet(
    state: MotionRibbonState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ArganTheme.colors
    val index = state.selectedNode
    val node = if (index in 0 until state.clip.nodeCount) state.clip.nodes[index] else null

    Surface(
        modifier = modifier.widthIn(max = 520.dp),
        shape = RoundedCornerShape(24.dp),
        color = colors.chromeSurface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(ArganTheme.dimensions.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.motion_ribbon),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.paperInk,
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.motion_done), color = colors.paperInkMuted)
                }
            }

            if (node == null) {
                // No selection: say what to do instead of showing disabled controls.
                Text(
                    text = stringResource(R.string.motion_node_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.paperInkMuted,
                    modifier = Modifier.padding(top = ArganTheme.dimensions.sm),
                )
                return@Column
            }

            Text(
                text = stringResource(R.string.motion_character),
                style = MaterialTheme.typography.labelMedium,
                color = colors.paperInkMuted,
                modifier = Modifier.padding(top = ArganTheme.dimensions.md),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(ArganTheme.dimensions.sm),
                modifier = Modifier.padding(top = ArganTheme.dimensions.xs),
            ) {
                CharacterChip(
                    label = stringResource(R.string.motion_character_smooth),
                    spring = SpringDynamics.SMOOTH,
                    node = node,
                    state = state,
                )
                CharacterChip(
                    label = stringResource(R.string.motion_character_snappy),
                    spring = SpringDynamics.SNAPPY,
                    node = node,
                    state = state,
                )
                CharacterChip(
                    label = stringResource(R.string.motion_character_elastic),
                    spring = SpringDynamics.ELASTIC,
                    node = node,
                    state = state,
                )
                CharacterChip(
                    label = stringResource(R.string.motion_character_reluctant),
                    spring = SpringDynamics.RELUCTANT,
                    node = node,
                    state = state,
                )
            }

            LabelledSlider(
                label = stringResource(R.string.motion_speed),
                value = node.speedMultiplier,
                range = 0.25f..3f,
                onChange = { value ->
                    state.updateSelectedNode { it.copy(speedMultiplier = value) }
                },
            )
            LabelledSlider(
                label = stringResource(R.string.motion_hold),
                value = node.holdFraction,
                range = 0f..0.8f,
                onChange = { value ->
                    state.updateSelectedNode { it.copy(holdFraction = value) }
                },
            )
        }
    }
}

@Composable
private fun CharacterChip(
    label: String,
    spring: SpringDynamics,
    node: MotionNode,
    state: MotionRibbonState,
) {
    val selected = node.spring == spring
    FilterChip(
        selected = selected,
        onClick = {
            state.updateSelectedNode { it.copy(spring = spring) }
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = ArganTheme.colors.ribbon.copy(alpha = 0.24f),
            selectedLabelColor = ArganTheme.colors.paperInk,
            labelColor = ArganTheme.colors.paperInkMuted,
        ),
    )
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(top = ArganTheme.dimensions.md).fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = ArganTheme.colors.paperInkMuted,
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}
