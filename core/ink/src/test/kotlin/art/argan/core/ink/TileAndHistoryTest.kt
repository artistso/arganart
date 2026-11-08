package art.argan.core.ink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tiles and history are where an infinite canvas either stays honest or quietly
 * leaks. Both are pure Kotlin so the budgets can be proven without a device.
 */
class TileAndHistoryTest {

    // ---- tiles ----------------------------------------------------------

    @Test
    fun `tile coordinates floor toward negative infinity`() {
        // -1 canvas unit must land in tile -1, not tile 0. Getting this wrong makes
        // the canvas break the moment the user pans up or left of the origin.
        assertEquals(0, TileGrid.tileXFor(0f))
        assertEquals(0, TileGrid.tileXFor(511.9f))
        assertEquals(1, TileGrid.tileXFor(512f))
        assertEquals(-1, TileGrid.tileXFor(-0.1f))
        assertEquals(-1, TileGrid.tileXFor(-512f))
        assertEquals(-2, TileGrid.tileXFor(-512.1f))
    }

    @Test
    fun `tile origin is the inverse of the mapping`() {
        assertEquals(0f, TileGrid.originX(0))
        assertEquals(512f, TileGrid.originX(1))
        assertEquals(-512f, TileGrid.originX(-1))
        assertEquals(1024f, TileGrid.originY(2))
    }

    @Test
    fun `tile range covers a spanning rectangle`() {
        val range = TileGrid.rangeFor(StrokeBounds(500f, 500f, 1100f, 1100f))
        assertEquals(0, range.minX) // 500 is in tile 0
        assertEquals(2, range.maxX) // 1100 is in tile 2
        assertEquals(3, range.width)
        assertEquals(3, range.height)
        assertEquals(9, range.tileCount)
    }

    @Test
    fun `tile keys are unique across the full int range including negatives`() {
        val keys = HashSet<Long>()
        for (x in listOf(-2, -1, 0, 1, 2)) {
            for (y in listOf(-2, -1, 0, 1, 2)) {
                assertTrue(keys.add(TileGrid.keyOf(x, y)), "collision at ($x,$y)")
            }
        }
        // The classic packing trap: positive and negative y must not collide.
        assertTrue(TileGrid.keyOf(0, -1) != TileGrid.keyOf(0, 1))
    }

    @Test
    fun `store evicts least recently used and reports evictions`() {
        val store = TileStore<String>(maxTiles = 3)
        store.put(0, 0, "a")
        store.put(1, 0, "b")
        store.put(2, 0, "c")
        // Touch "a" so "b" becomes the least recently used.
        assertNotNull(store.get(0, 0))
        val evicted = store.put(3, 0, "d")

        assertEquals(1, evicted.size, "exactly one tile should be evicted")
        assertEquals(TileGrid.keyOf(1, 0), evicted.single())
        assertNotNull(store.get(0, 0), "recently used tile must survive")
        assertNull(store.get(1, 0), "least recently used tile must be gone")
        assertEquals(3, store.size)
    }

    @Test
    fun `store never exceeds its budget under churn`() {
        val store = TileStore<Int>(maxTiles = 16)
        repeat(1_000) { i -> store.put(i % 100, i / 100, i) }
        assertTrue(store.size <= 16, "store grew to ${store.size}")
    }

    @Test
    fun `overwriting a tile does not evict anything`() {
        val store = TileStore<String>(maxTiles = 2)
        store.put(0, 0, "a")
        store.put(1, 0, "b")
        val evicted = store.put(0, 0, "a2")
        assertTrue(evicted.isEmpty())
        assertEquals("a2", store.get(0, 0))
    }

    @Test
    fun `store rejects a nonsensical budget`() {
        assertFailsWith<IllegalArgumentException> { TileStore<String>(maxTiles = 0) }
    }

    // ---- history --------------------------------------------------------

    private class FakeDoc {
        val tiles = HashMap<Long, ByteArray>()
        fun write(key: Long, pixels: ByteArray) {
            tiles[key] = pixels
        }
    }

    private fun strokeCommand(
        doc: FakeDoc,
        label: String,
        tileKey: Long,
        before: ByteArray,
        after: ByteArray,
    ) = StrokeCommand(
        label = label,
        affectedTiles = TileRange(0, 0, 0, 0),
        tileBefores = mapOf(tileKey to before),
        tileAfters = mapOf(tileKey to after),
        writeTile = doc::write,
    )

    @Test
    fun `history requires at least 100 undo steps`() {
        assertFailsWith<IllegalArgumentException> { History(maxSteps = 99) }
        val history = History(maxSteps = 100)
        assertEquals(0, history.undoCount)
        assertTrue(!history.canUndo)
    }

    @Test
    fun `undo restores before pixels and redo restores after`() {
        val doc = FakeDoc()
        val key = TileGrid.keyOf(0, 0)
        doc.write(key, byteArrayOf(0))
        val history = History()

        val command = strokeCommand(doc, "stroke", key, byteArrayOf(0), byteArrayOf(1))
        command.apply()
        history.push(command)
        assertTrue(doc.tiles[key]!!.contentEquals(byteArrayOf(1)))

        history.undo()
        assertTrue(doc.tiles[key]!!.contentEquals(byteArrayOf(0)), "undo must restore 'before'")

        history.redo()
        assertTrue(doc.tiles[key]!!.contentEquals(byteArrayOf(1)), "redo must restore 'after'")
    }

    @Test
    fun `new edit invalidates the redo stack`() {
        val doc = FakeDoc()
        val key = TileGrid.keyOf(0, 0)
        val history = History()

        history.push(strokeCommand(doc, "a", key, byteArrayOf(0), byteArrayOf(1)))
        history.undo()
        assertEquals(1, history.redoCount)

        history.push(strokeCommand(doc, "b", key, byteArrayOf(1), byteArrayOf(2)))
        assertEquals(0, history.redoCount, "drawing after undo must destroy the future")
        assertTrue(!history.canRedo)
    }

    @Test
    fun `undo and redo are no-ops at the ends rather than throwing`() {
        val history = History()
        assertNull(history.undo())
        assertNull(history.redo())
        assertEquals(0, history.undoCount)
    }

    @Test
    fun `100 stroke round trips leave identical pixels`() {
        // P1 DoD: "100-stroke undo/redo round-trips with no visual artifacts."
        val doc = FakeDoc()
        val key = TileGrid.keyOf(2, 3)
        val history = History(maxSteps = 200)

        val before = ByteArray(64) { 0 }
        for (i in 1..100) {
            val after = ByteArray(64) { i.toByte() }
            val command = strokeCommand(doc, "stroke $i", key, before, after)
            command.apply()
            history.push(command)
        }
        assertEquals(100, history.undoCount)
        assertEquals(100.toByte(), doc.tiles[key]!![0])

        repeat(100) { history.undo() }
        assertTrue(doc.tiles[key]!!.contentEquals(before), "full undo must restore the original")
        assertEquals(0, history.undoCount)
        assertEquals(100, history.redoCount)

        repeat(100) { history.redo() }
        assertEquals(100.toByte(), doc.tiles[key]!![0], "full redo must restore the final state")
        assertEquals(0, history.redoCount)
    }

    @Test
    fun `step budget drops the oldest steps first`() {
        val doc = FakeDoc()
        val key = TileGrid.keyOf(0, 0)
        val history = History(maxSteps = 100)
        repeat(150) { i ->
            history.push(
                strokeCommand(doc, "s$i", key, byteArrayOf(i.toByte()), byteArrayOf((i + 1).toByte())),
            )
        }
        assertEquals(100, history.undoCount, "must cap at maxSteps")
        assertEquals("s149", history.peekUndoLabel())
    }

    @Test
    fun `byte budget trims even when step count is fine`() {
        val doc = FakeDoc()
        val key = TileGrid.keyOf(0, 0)
        // 1 MB per command, 3 MB budget, 200 step allowance.
        val history = History(maxSteps = 200, maxBytes = 3L * 1024 * 1024)
        repeat(10) { i ->
            history.push(
                strokeCommand(
                    doc,
                    "s$i",
                    key,
                    ByteArray(512 * 1024),
                    ByteArray(512 * 1024),
                ),
            )
        }
        assertTrue(history.heldBytes <= 3L * 1024 * 1024, "held ${history.heldBytes} bytes")
        assertTrue(history.undoCount < 10, "byte budget should have trimmed steps")
        // The most recent steps must survive: it is the old work that is expendable.
        assertEquals("s9", history.peekUndoLabel())
    }

    @Test
    fun `mismatched before and after tile sets are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StrokeCommand(
                label = "bad",
                affectedTiles = TileRange(0, 0, 0, 0),
                tileBefores = mapOf(1L to ByteArray(1)),
                tileAfters = mapOf(2L to ByteArray(1)),
                writeTile = { _, _ -> },
            )
        }
    }

    @Test
    fun `clear empties both stacks and the byte count`() {
        val doc = FakeDoc()
        val history = History()
        history.push(strokeCommand(doc, "a", 1L, ByteArray(8), ByteArray(8)))
        history.clear()
        assertEquals(0, history.undoCount)
        assertEquals(0, history.redoCount)
        assertEquals(0L, history.heldBytes)
    }
}
