package art.argan.core.diagnostics

/**
 * A single rendered frame's timing, as reported by `FrameMetrics` on Android.
 *
 * Pure data with no Android types so that every statistic the product quotes
 * ("<1% janky frames", "median input-to-photon under 16ms") is computed by code
 * that is unit-tested on the host. A number a user or an engineer trusts must be
 * arithmetic we can prove, not a log line nobody reads.
 *
 * All times are nanoseconds, matching `FrameMetrics`/JankStats sources.
 *
 * @property frameStartNanos when the frame's work began.
 * @property frameEndNanos when the frame was submitted. Must be >= start.
 * @property inputToPhotonNanos optional end-to-end latency for the input that
 *   caused this frame, when the source can supply it.
 * @property isJanky the platform's own jank verdict for this frame. Kept separate
 *   from our budget verdict because they answer different questions: the platform
 *   compares against the display's refresh interval, we compare against the
 *   product's budget.
 */
public data class FrameStat(
    val frameStartNanos: Long,
    val frameEndNanos: Long,
    val inputToPhotonNanos: Long? = null,
    val isJanky: Boolean = false,
) {
    init {
        require(frameEndNanos >= frameStartNanos) {
            "frameEndNanos ($frameEndNanos) must be >= frameStartNanos ($frameStartNanos)"
        }
        require(inputToPhotonNanos == null || inputToPhotonNanos >= 0) {
            "inputToPhotonNanos must be >= 0 when present, was $inputToPhotonNanos"
        }
    }

    /** Total frame duration. */
    public val durationNanos: Long get() = frameEndNanos - frameStartNanos

    public val durationMillis: Double get() = durationNanos / NANOS_PER_MILLI
}

/** Nanoseconds in one millisecond. */
public const val NANOS_PER_MILLI: Double = 1_000_000.0

/**
 * The frame-budget contract from `PERFORMANCE.md`, as data.
 *
 * Kept as a value rather than constants scattered through the code so a test can
 * assert the *document* and the *code* agree, and so a future device class can
 * carry a different budget without editing logic.
 */
public data class FrameBudget(
    /** 8 ms: the 120 Hz ink-path budget (ARCHITECTURE.md rule 6). */
    val inkFrameMs: Double = 8.0,
    /** Median input-to-photon ceiling. */
    val inputToPhotonMedianMs: Double = 16.0,
    /** p95 input-to-photon ceiling. */
    val inputToPhotonP95Ms: Double = 24.0,
    /** Janky frames must stay under this fraction of all frames (P1 DoD: <1%). */
    val maxJankyFraction: Double = 0.01,
) {
    init {
        require(inkFrameMs > 0.0) { "inkFrameMs must be > 0" }
        require(inputToPhotonMedianMs > 0.0) { "inputToPhotonMedianMs must be > 0" }
        require(inputToPhotonP95Ms >= inputToPhotonMedianMs) {
            "p95 budget must be >= median budget"
        }
        require(maxJankyFraction in 0.0..1.0) { "maxJankyFraction must be in 0..1" }
    }
}

/** One statistic's verdict against its budget. */
public enum class BudgetVerdict {
    /** Inside budget. */
    PASS,

    /** Outside budget. A build that reports this is not shippable at that phase. */
    FAIL,

    /** Not enough samples to say anything. Never treated as PASS. */
    INSUFFICIENT_DATA,
}

/**
 * An aggregate over a window of frames.
 *
 * Deliberately reports median *and* p95 for latency: a median alone hides a
 * janky tail, which is exactly the failure users notice.
 */
public data class FrameReport(
    val sampleCount: Int,
    val jankyCount: Int,
    val medianInputToPhotonMs: Double?,
    val p95InputToPhotonMs: Double?,
    val medianFrameMs: Double?,
    val p95FrameMs: Double?,
    val overInkBudgetCount: Int,
    val budget: FrameBudget,
    val verdict: BudgetVerdict,
) {
    /** Fraction of frames the platform called janky. `null` with no samples. */
    public val jankyFraction: Double?
        get() = if (sampleCount == 0) null else jankyCount.toDouble() / sampleCount

    /** Human-readable one-liner for the debug overlay. */
    public fun summary(): String {
        if (sampleCount == 0) return "no frames sampled"
        val jank = (jankyFraction ?: 0.0) * 100.0
        val median = medianInputToPhotonMs?.let { String.format("%.1f", it) } ?: "-"
        val p95 = p95InputToPhotonMs?.let { String.format("%.1f", it) } ?: "-"
        return "frames=$sampleCount jank=${String.format("%.2f", jank)}% " +
            "i2p med=${median}ms p95=${p95}ms [${verdict.name}]"
    }
}

/**
 * Accumulates [FrameStat]s into a [FrameReport].
 *
 * Design constraints:
 * - **Bounded memory.** Keeps a fixed-size ring of latencies, never a growing
 *   list. A debug overlay that leaks is worse than no overlay: it would change
 *   the behaviour it exists to measure.
 * - **No allocation per frame.** [record] performs no boxing, no list growth and
 *   no sorting; percentile work happens once, in [report].
 * - **Not thread-safe by design.** It is owned by the frame callback thread; a
 *   `synchronized` block in a frame callback would be a self-inflicted stall.
 *
 * @param windowSize frames retained for percentile maths. 240 covers ~2s at 120Hz.
 */
public class FrameWindow(
    private val budget: FrameBudget = FrameBudget(),
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
) {
    init {
        require(windowSize > 0) { "windowSize must be > 0, was $windowSize" }
    }

    private val latencies = LongArray(windowSize) { -1L }
    private val frameDurations = LongArray(windowSize) { -1L }
    private var cursor: Int = 0
    private var filled: Int = 0

    private var totalSamples: Int = 0
    private var jankySamples: Int = 0
    private var overBudgetSamples: Int = 0

    /** Records one frame. Allocation-free. */
    public fun record(stat: FrameStat) {
        latencies[cursor] = stat.inputToPhotonNanos ?: -1L
        frameDurations[cursor] = stat.durationNanos
        cursor = (cursor + 1) % windowSize
        if (filled < windowSize) filled++

        totalSamples++
        if (stat.isJanky) jankySamples++
        if (stat.durationMillis > budget.inkFrameMs) overBudgetSamples++
    }

    /** Number of frames recorded since construction or the last [reset]. */
    public val sampleCount: Int get() = totalSamples

    /** Frames with input-to-photon data in the current ring. */
    public val latencySampleCount: Int get() = latencies.count { it >= 0 }

    /** True while the window holds too few frames for a trustworthy percentile. */
    public val isWarmingUp: Boolean get() = filled < windowSize

    public fun reset() {
        latencies.fill(-1L)
        frameDurations.fill(-1L)
        cursor = 0
        filled = 0
        totalSamples = 0
        jankySamples = 0
        overBudgetSamples = 0
    }

    /**
     * Computes the current report. Allocates (sorting scratch arrays), so call it
     * from a UI tick at most a few times a second, never per frame.
     */
    public fun report(): FrameReport {
        val latencySnapshot = latencies.copyOf(filled).filter { it >= 0 }.toLongArray()
        val durationSnapshot = frameDurations.copyOf(filled)

        val medianLatency = percentile(latencySnapshot, 50.0)
        val p95Latency = percentile(latencySnapshot, 95.0)
        val medianFrame = percentile(durationSnapshot, 50.0)
        val p95Frame = percentile(durationSnapshot, 95.0)

        return FrameReport(
            sampleCount = totalSamples,
            jankyCount = jankySamples,
            medianInputToPhotonMs = medianLatency?.div(NANOS_PER_MILLI),
            p95InputToPhotonMs = p95Latency?.div(NANOS_PER_MILLI),
            medianFrameMs = medianFrame?.div(NANOS_PER_MILLI),
            p95FrameMs = p95Frame?.div(NANOS_PER_MILLI),
            overInkBudgetCount = overBudgetSamples,
            budget = budget,
            verdict = verdictFor(totalSamples, jankySamples, medianLatency, p95Latency),
        )
    }

    private fun verdictFor(
        samples: Int,
        janky: Int,
        medianLatencyNanos: Long?,
        p95LatencyNanos: Long?,
    ): BudgetVerdict {
        if (samples < MIN_SAMPLES_FOR_VERDICT) return BudgetVerdict.INSUFFICIENT_DATA
        if (janky.toDouble() / samples > budget.maxJankyFraction) return BudgetVerdict.FAIL
        if (medianLatencyNanos != null &&
            medianLatencyNanos / NANOS_PER_MILLI > budget.inputToPhotonMedianMs
        ) {
            return BudgetVerdict.FAIL
        }
        if (p95LatencyNanos != null &&
            p95LatencyNanos / NANOS_PER_MILLI > budget.inputToPhotonP95Ms
        ) {
            return BudgetVerdict.FAIL
        }
        return BudgetVerdict.PASS
    }

    public companion object {
        public const val DEFAULT_WINDOW_SIZE: Int = 240

        /**
         * Minimum frames before a verdict is offered.
         *
         * Low enough that a 5-second test produces a verdict, high enough that one
         * startup hitch cannot condemn a build.
         */
        public const val MIN_SAMPLES_FOR_VERDICT: Int = 60
    }
}

/**
 * Nearest-rank percentile over an unsorted array. Returns `null` for an empty
 * input rather than 0, so "no data" can never masquerade as a perfect score.
 */
public fun percentile(values: LongArray, percentile: Double): Long? {
    if (values.isEmpty()) return null
    require(percentile in 0.0..100.0) { "percentile must be in 0..100, was $percentile" }
    val sorted = values.copyOf()
    sorted.sort()
    // Nearest-rank: smallest value whose rank covers the requested percentile.
    val rank = kotlin.math.ceil(percentile / 100.0 * sorted.size).toInt()
    val index = (rank - 1).coerceIn(0, sorted.size - 1)
    return sorted[index]
}
