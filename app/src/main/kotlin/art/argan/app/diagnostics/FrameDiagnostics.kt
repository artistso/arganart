package art.argan.app.diagnostics

import android.app.Activity
import android.os.Build
import androidx.metrics.performance.FrameData
import androidx.metrics.performance.JankStats
import art.argan.core.diagnostics.FrameReport
import art.argan.core.diagnostics.FrameStat
import art.argan.core.diagnostics.FrameWindow
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Attaches JankStats to the app window and turns its frame data into the report
 * that `PERFORMANCE.md` and P1's Definition of Done are judged against.
 *
 * Two responsibilities, deliberately separated:
 * - **Collection** is Android-specific (`JankStats`, `FrameData`, `Build.VERSION`).
 * - **Statistics** live in `:core:diagnostics`, pure and unit-tested.
 *
 * This class only bridges the two; it contains no arithmetic that matters.
 *
 * JankStats is debug-and-release safe but the overlay is debug-only, so this
 * collector is created from `MainActivity` in all build types only when
 * [ENABLED] is true (see `BuildConfig.DEBUG` at the call site in P1, where the
 * ink path starts caring). At P0 it is wired and running so that the first real
 * measurement in P1 has a warm pipeline.
 */
public class FrameDiagnostics private constructor(
    private val window: FrameWindow,
) {
    private val listeners = CopyOnWriteArraySet<(FrameReport) -> Unit>()
    private var jankStats: JankStats? = null

    /** Reports every frame to [window] and, at most once a second, to listeners. */
    public fun attach(activity: Activity) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "JankStats requires API 29+; minSdk is 29 so this cannot be hit in practice"
        }
        if (jankStats != null) return // idempotent: a re-created activity must not double-count.

        val stats = JankStats.createAndTrack(activity.window) { frameData ->
            onFrame(frameData)
        }
        jankStats = stats
    }

    /** Pauses collection (backgrounded). Frames are not counted while paused. */
    public fun pause() {
        jankStats?.isTrackingEnabled = false
    }

    /** Resumes collection after [pause]. */
    public fun resume() {
        jankStats?.isTrackingEnabled = true
    }

    public fun detach() {
        jankStats?.isTrackingEnabled = false
        jankStats = null
    }

    /** Registers a report listener; returns a handle to unregister it. */
    public fun addListener(listener: (FrameReport) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    public fun snapshot(): FrameReport = window.report()

    public fun reset() {
        window.reset()
        lastEmitNanos = 0L
    }

    private var lastEmitNanos: Long = 0L

    /**
     * The newest stylus event timestamp, or 0 when no stylus input has arrived.
     *
     * Set from the ink surface on every stylus event. This is the only source of a real
     * input-to-photon number, and it is deliberately consumed by exactly ONE frame (see
     * [consumedInputTimestampNanos]) -- see the note in [onFrame] for why that matters.
     */
    private var pendingInputTimestampNanos: Long = 0L

    /** Records the timestamp of a stylus event that is about to be drawn. */
    public fun recordInputTimestamp(eventTimeMillis: Long) {
        // MotionEvent event times are in the uptimeMillis base, nanoseconds derived here.
        pendingInputTimestampNanos = eventTimeMillis * 1_000_000L
    }

    private fun onFrame(frameData: FrameData) {
        // Verified against androidx/metrics-performance FrameData.kt (2026-11):
        //   FrameData(frameStartNanos: Long, frameDurationUiNanos: Long,
        //             isJank: Boolean, states: List<StateInfo>)
        // Both properties used here are public with private setters.
        // Input-to-photon estimate.
        //
        // JankStats does not report this, so it is derived: the frame that DRAWS a given
        // stylus event started at frameStartNanos, and the event was stamped at
        // pendingInputTimestampNanos. Their difference is the input-to-photon latency for
        // that event, to first order.
        //
        // Consumed by exactly one frame, then cleared. Without the clear, every
        // subsequent idle frame would re-report the same stale event and the latency
        // distribution would fill up with junk from frames that drew no input at all.
        //
        // KNOWN UNDER-REPORT, stated rather than hidden: `frameStartNanos` is when the
        // frame BEGAN, so the touch that caused it is already in the past by the time the
        // pixels land. The true figure is larger by roughly one vsync plus the render
        // path, and P1's review lists measuring it on hardware as an open item. This
        // number is therefore a floor for the budget, not a certification of it.
        val inputNanos = pendingInputTimestampNanos
        val latencyNanos = if (inputNanos > 0L) {
            (frameData.frameStartNanos - inputNanos).coerceAtLeast(0L)
        } else {
            null
        }
        pendingInputTimestampNanos = 0L

        val stat = FrameStat(
            frameStartNanos = frameData.frameStartNanos,
            frameEndNanos = frameData.frameStartNanos + frameData.frameDurationUiNanos,
            inputToPhotonNanos = latencyNanos,
            isJanky = frameData.isJank,
        )
        window.record(stat)

        val now = System.nanoTime()
        // Throttle: recomputing percentiles per frame would itself cause jank.
        if (now - lastEmitNanos >= REPORT_INTERVAL_NANOS) {
            lastEmitNanos = now
            val report = window.report()
            listeners.forEach { it(report) }
        }
    }

    public companion object {
        /**
         * Whether frame collection is active. Kept true even in release builds at
         * P0 because it is cheap and because P1's budgets depend on continuous
         * data; the *overlay* is what is debug-only.
         */
        public const val ENABLED: Boolean = true

        private const val REPORT_INTERVAL_NANOS: Long = 1_000_000_000L

        public fun create(): FrameDiagnostics = FrameDiagnostics(FrameWindow())

        /** Creates a collector and attaches it to [activity]'s window. */
        public fun attachTo(activity: Activity): FrameDiagnostics {
            val diagnostics = create()
            diagnostics.attach(activity)
            return diagnostics
        }
    }
}
