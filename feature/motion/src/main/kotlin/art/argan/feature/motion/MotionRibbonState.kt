package art.argan.feature.motion

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import art.argan.core.motion.MotionClipSnapshot
import art.argan.core.motion.MotionNode
import art.argan.core.motion.MotionRibbonClip
import art.argan.core.motion.MotionSpine
import art.argan.core.motion.Pose
import art.argan.core.motion.SpinePoint

/**
 * The editing state of one motion ribbon: the clip, the clock, and the gestures.
 *
 * ## Why the clock lives here and not in the clip
 *
 * A [MotionRibbonClip] is a pure function of time. Something has to advance `t`, and
 * that something is also what a scrub gesture writes to. Putting both in one small
 * object means "playing" and "scrubbing" are the same operation — setting the time —
 * which is exactly the P2 brief's claim that there is no timeline to be in or out of.
 *
 * ## Editing during playback
 *
 * The clip is immutable; every edit returns a new one. A frame being drawn holds a
 * reference to the clip it started with, so an edit mid-frame cannot tear it. The next
 * frame picks up the new one. No locks, no invalidation step.
 */
@Stable
public class MotionRibbonState(
    initial: MotionRibbonClip = defaultClip(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** The clip being edited. Replaced wholesale on every edit. */
    public var clip: MotionRibbonClip by mutableStateOf(initial)
        private set

    /** Position in the clip, in milliseconds. Scrubbing writes here. */
    public var timeMs: Long by mutableStateOf(0L)
        private set

    /** Whether the clock is advancing. Editing is allowed either way. */
    public var isPlaying: Boolean by mutableStateOf(true)
        private set

    /** The node currently selected for editing, or -1. */
    public var selectedNode: Int by mutableStateOf(-1)
        private set

    private var lastTickMs: Long = 0L

    /** Caller-owned destination for sampling; never reallocated. */
    public val pose: Pose = Pose()

    /** Computes the pose at the current time into [pose]. Allocation-free. */
    public fun updatePose() {
        clip.sampleInto(timeMs, pose)
    }

    /**
     * Advances time by the elapsed wall clock, if playing. Safe to call every frame.
     *
     * The cap is on how much a single *frame* may advance, not on elapsed wall time: a
     * device that drops 200 ms of frames should show motion 200 ms later (the animation
     * is a function of time, so it stays truthful), it just should not jump in one
     * visible step. Capping the elapsed delta itself — the first version — meant a
     * 10-second background gap advanced the clip by only 64 ms, so returning to the app
     * rewound the motion instead of catching up.
     */
    public fun tick() {
        val now = clock()
        if (!isPlaying) {
            lastTickMs = now
            return
        }
        if (lastTickMs == 0L) {
            lastTickMs = now
            return
        }
        val elapsed = (now - lastTickMs).coerceAtLeast(0L)
        lastTickMs = now
        var remaining = elapsed
        while (remaining > 0L) {
            val step = remaining.coerceAtMost(MAX_STEP_MS)
            timeMs += step
            remaining -= step
            if (!clip.isLooping && timeMs >= clip.durationMs) {
                timeMs = clip.durationMs.toLong()
                return
            }
            if (clip.isLooping) timeMs %= clip.durationMs
        }
    }

    public fun togglePlayback() {
        isPlaying = !isPlaying
        lastTickMs = 0L
    }

    /**
     * Scrubs to [fraction] `0..1` of the clip.
     *
     * The brief says dragging anywhere on the ribbon scrubs time, so this is the single
     * entry point for that gesture. It also means scrubbing *is* playing from a
     * different starting point, which is what keeps the model honest: there is no
     * separate "preview" path that could disagree with playback.
     */
    public fun scrubTo(fraction: Float) {
        isPlaying = false
        lastTickMs = 0L
        timeMs = (fraction.coerceIn(0f, 1f) * clip.durationMs).toLong()
    }

    /** Scrubs to the arc length [distance] along the spine — position IS time. */
    public fun scrubToDistance(distance: Float) {
        if (clip.spine.length <= 0f) {
            scrubTo(0f)
            return
        }
        scrubTo(distance / clip.spine.length)
    }

    public fun selectNode(index: Int) {
        selectedNode = if (index in 0 until clip.nodeCount) index else -1
    }

    /** Replaces the selected node's character. */
    public fun updateSelectedNode(transform: (MotionNode) -> MotionNode) {
        val index = selectedNode
        if (index < 0 || index >= clip.nodeCount) return
        clip = clip.withNode(index, transform(clip.nodes[index]))
    }

    /**
     * Bends the spine by moving control point [index] to a canvas position.
     *
     * Node distances are held in absolute arc length, so a bend changes what a node's
     * distance MEANS: the same 200 units is now further along in normalised terms. The
     * clip re-clamps node distances to the new spine, which is why a bend never leaves a
     * node stranded past the end.
     */
    public fun moveSpinePoint(index: Int, x: Float, y: Float) {
        val current = clip.spine.points()
        if (index !in current.indices) return
        val updated = current.toMutableList()
        updated[index] = SpinePoint(x, y)
        if (updated.size < 2) return
        clip = clip.withSpine(MotionSpine.of(updated))
    }

    /** Adds a node at [distance] along the spine (long-press gesture). */
    public fun addNodeAt(distance: Float) {
        clip = clip.withNodeAdded(MotionNode(distance = distance, spring = clip.nodes.first().spring))
    }

    /** Sets the whole clip, e.g. from a backend or a reload. */
    public fun replaceClip(next: MotionRibbonClip) {
        clip = next
        timeMs = timeMs.coerceIn(0L, next.durationMs.toLong())
    }

    /** The serialisable form, for P9's project manifest. */
    public fun snapshot(): MotionClipSnapshot = MotionClipSnapshot.of(clip)

    public fun restore(snapshot: MotionClipSnapshot) {
        replaceClip(snapshot.decode())
    }

    public companion object {
        /**
         * Largest advance applied in one step.
         *
         * Still bounded so that a pathological clock jump cannot spin the catch-up loop
         * forever, but the loop consumes the WHOLE elapsed time in steps of this size, so
         * the clip stays truthful about how much real time passed.
         */
        private const val MAX_STEP_MS = 64L

        /** A ribbon across the middle of a landscape canvas: the shape the app opens with. */
        public fun defaultClip(): MotionRibbonClip {
            // Overload ambiguity: `of(Float...)` and `of(List<SpinePoint>)` both look
            // plausible at the call site, so the list form is named explicitly.
            val spine = MotionSpine.of(
                listOf(
                    SpinePoint(160f, 460f),
                    SpinePoint(560f, 260f),
                    SpinePoint(980f, 420f),
                ),
            )
            return MotionRibbonClip.create(spine = spine, durationMs = 3_000)
        }
    }
}
