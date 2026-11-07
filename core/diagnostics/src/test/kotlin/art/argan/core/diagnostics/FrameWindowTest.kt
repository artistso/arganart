package art.argan.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The numbers this code produces are quoted in PRs, docs and eventually the
 * product. They must be computed by tested arithmetic, not asserted in prose.
 */
class FrameWindowTest {

    private fun stat(
        durationNanos: Long = 8_000_000,
        latencyNanos: Long? = 12_000_000,
        janky: Boolean = false,
        start: Long = 0,
    ) = FrameStat(
        frameStartNanos = start,
        frameEndNanos = start + durationNanos,
        inputToPhotonNanos = latencyNanos,
        isJanky = janky,
    )

    @Test
    fun `duration is derived and validated`() {
        val s = stat(durationNanos = 7_000_000)
        assertEquals(7_000_000L, s.durationNanos)
        assertEquals(7.0, s.durationMillis, 1e-9)
        assertFailsWith<IllegalArgumentException> {
            FrameStat(frameStartNanos = 10, frameEndNanos = 5)
        }
        assertFailsWith<IllegalArgumentException> {
            FrameStat(frameStartNanos = 0, frameEndNanos = 1, inputToPhotonNanos = -1)
        }
    }

    @Test
    fun `percentile uses nearest rank`() {
        val values = LongArray(100) { (it + 1).toLong() } // 1..100
        assertEquals(50L, percentile(values, 50.0))
        assertEquals(95L, percentile(values, 95.0))
        assertEquals(1L, percentile(values, 0.0))
        assertEquals(100L, percentile(values, 100.0))
    }

    @Test
    fun `percentile of empty is null, not zero`() {
        assertNull(percentile(LongArray(0), 50.0))
    }

    @Test
    fun `no samples reports insufficient data, never pass`() {
        val report = FrameWindow().report()
        assertEquals(0, report.sampleCount)
        assertEquals(BudgetVerdict.INSUFFICIENT_DATA, report.verdict)
        assertNull(report.jankyFraction)
        assertNull(report.medianInputToPhotonMs)
        assertEquals("no frames sampled", report.summary())
    }

    @Test
    fun `too few samples is insufficient data, not pass`() {
        val window = FrameWindow(budget = FrameBudget())
        repeat(FrameWindow.MIN_SAMPLES_FOR_VERDICT - 1) { window.record(stat()) }
        assertEquals(BudgetVerdict.INSUFFICIENT_DATA, window.report().verdict)
    }

    @Test
    fun `healthy frames pass`() {
        val window = FrameWindow()
        repeat(100) { window.record(stat(durationNanos = 6_000_000, latencyNanos = 10_000_000)) }
        val report = window.report()
        assertEquals(BudgetVerdict.PASS, report.verdict)
        assertEquals(100, report.sampleCount)
        assertEquals(0, report.jankyCount)
        assertEquals(10.0, report.medianInputToPhotonMs!!, 1e-9)
        assertEquals(6.0, report.medianFrameMs!!, 1e-9)
    }

    @Test
    fun `janky frames above one percent fails`() {
        val window = FrameWindow()
        repeat(100) { i -> window.record(stat(janky = i < 2)) } // 2% janky
        val report = window.report()
        assertEquals(2, report.jankyCount)
        assertEquals(0.02, report.jankyFraction!!, 1e-9)
        assertEquals(BudgetVerdict.FAIL, report.verdict)
    }

    @Test
    fun `exactly one percent janky still passes - the budget is a ceiling`() {
        val window = FrameWindow()
        // 200 frames, 2 janky = exactly 1.0%.
        repeat(200) { i -> window.record(stat(janky = i < 2)) }
        val report = window.report()
        assertEquals(0.01, report.jankyFraction!!, 1e-9)
        assertEquals(BudgetVerdict.PASS, report.verdict)
    }

    @Test
    fun `slow median input to photon fails even with no jank`() {
        val window = FrameWindow()
        repeat(100) { window.record(stat(latencyNanos = 20_000_000)) }
        assertEquals(BudgetVerdict.FAIL, window.report().verdict)
    }

    @Test
    fun `tail latency fails on p95 even when the median is fine`() {
        val window = FrameWindow()
        // 90 fast frames, 10 very slow: median fine, p95 blows the 24ms budget.
        repeat(100) { i ->
            window.record(stat(latencyNanos = if (i < 90) 8_000_000 else 40_000_000))
        }
        val report = window.report()
        assertEquals(8.0, report.medianInputToPhotonMs!!, 1e-9)
        assertTrue(report.p95InputToPhotonMs!! >= 40.0)
        assertEquals(BudgetVerdict.FAIL, report.verdict, "a janky tail must not be hidden by the median")
    }

    @Test
    fun `frames missing latency data do not corrupt the latency percentiles`() {
        val window = FrameWindow()
        repeat(100) { i ->
            window.record(stat(latencyNanos = if (i % 2 == 0) 10_000_000 else null))
        }
        val report = window.report()
        assertEquals(50, window.latencySampleCount)
        assertEquals(10.0, report.medianInputToPhotonMs!!, 1e-9)
    }

    @Test
    fun `counts frames that exceed the ink budget`() {
        val window = FrameWindow(budget = FrameBudget(inkFrameMs = 8.0))
        repeat(10) { window.record(stat(durationNanos = 9_000_000)) }
        repeat(10) { window.record(stat(durationNanos = 7_999_000)) }
        assertEquals(10, window.report().overInkBudgetCount)
    }

    @Test
    fun `ring is bounded - memory does not grow with run length`() {
        val window = FrameWindow(windowSize = 32)
        repeat(10_000) { window.record(stat()) }
        // Total samples counts everything...
        assertEquals(10_000, window.sampleCount)
        // ...but the percentile window is fixed at windowSize: median is computed
        // from the last 32 frames only. Give the tail a distinct latency so that
        // is observable rather than assumed.
        val window2 = FrameWindow(windowSize = 10)
        repeat(100) { window2.record(stat(latencyNanos = 1_000_000)) } // stale, fast
        repeat(10) { window2.record(stat(latencyNanos = 50_000_000)) } // recent, slow
        assertEquals(10, window2.latencySampleCount)
        // If the ring were unbounded, the 100 fast frames would dominate the median.
        assertEquals(50.0, window2.report().medianInputToPhotonMs!!, 1e-9)
    }

    @Test
    fun `warmup flag clears once the ring is full`() {
        val window = FrameWindow(windowSize = 10)
        repeat(9) { window.record(stat()) }
        assertTrue(window.isWarmingUp)
        window.record(stat())
        assertTrue(!window.isWarmingUp)
    }

    @Test
    fun `reset clears every accumulator`() {
        val window = FrameWindow()
        repeat(100) { window.record(stat(janky = true)) }
        window.reset()
        assertEquals(0, window.sampleCount)
        assertEquals(0, window.latencySampleCount)
        assertEquals(BudgetVerdict.INSUFFICIENT_DATA, window.report().verdict)
    }

    @Test
    fun `budget invariants are validated`() {
        assertFailsWith<IllegalArgumentException> { FrameBudget(inkFrameMs = 0.0) }
        assertFailsWith<IllegalArgumentException> {
            FrameBudget(inputToPhotonMedianMs = 20.0, inputToPhotonP95Ms = 10.0)
        }
        assertFailsWith<IllegalArgumentException> { FrameBudget(maxJankyFraction = 1.5) }
    }

    @Test
    fun `default budget matches PERFORMANCE md`() {
        // The product's documented budgets and the code's defaults must not drift.
        val b = FrameBudget()
        assertEquals(8.0, b.inkFrameMs)
        assertEquals(16.0, b.inputToPhotonMedianMs)
        assertEquals(24.0, b.inputToPhotonP95Ms)
        assertEquals(0.01, b.maxJankyFraction)
    }

    @Test
    fun `summary is a single glanceable line`() {
        val window = FrameWindow()
        repeat(100) { window.record(stat(durationNanos = 6_000_000, latencyNanos = 10_000_000)) }
        val line = window.report().summary()
        assertTrue(line.contains("jank=0.00%"), line)
        assertTrue(line.contains("i2p med=10.0ms"), line)
        assertTrue(line.contains("PASS"), line)
    }
}
