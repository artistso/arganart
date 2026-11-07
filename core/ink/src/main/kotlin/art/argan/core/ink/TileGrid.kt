package art.argan.core.ink

import kotlin.math.floor

/**
 * The infinite canvas's tile grid: coordinate maths and an LRU-indexed store.
 *
 * WHY TILES. A committed-artwork layer cannot be one giant bitmap (a 32× zoom over a
 * tablet canvas would need tens of gigabytes). Instead the layer is a sparse grid of
 * fixed-size tiles, materialised only where the user has drawn. This is also what
 * makes tile-diff undo cheap: an undo step stores the pixels of the tiles a stroke
 * touched, not the whole canvas.
 *
 * WHY THE STORE CHECKS ITS OWN BUDGET. A tile cache that grows without bound is a
 * memory leak wearing a cache costume. [TileStore] evicts least-recently-used tiles
 * when it exceeds its budget, and reports what it evicted so the caller can spill to
 * disk above the threshold (P1 leaves the spill hook; P9 owns the disk format).
 *
 * All coordinates are in canvas units. `:core:ink` knows nothing about pixels,
 * screens or Android.
 */
public object TileGrid {
    /** Tile edge in canvas units. 512 keeps a full tablet screen inside 4-6 tiles. */
    public const val TILE_SIZE: Int = 512

    /** Tile coordinates for a canvas point. */
    public fun tileXFor(canvasX: Float): Int = floor(canvasX / TILE_SIZE).toInt()

    public fun tileYFor(canvasY: Float): Int = floor(canvasY / TILE_SIZE).toInt()

    /** Canvas-space origin of a tile. */
    public fun originX(tileX: Int): Float = tileX.toFloat() * TILE_SIZE

    public fun originY(tileY: Int): Float = tileY.toFloat() * TILE_SIZE

    /** The inclusive tile range covering a canvas-space rectangle. */
    public fun rangeFor(bounds: StrokeBounds): TileRange = TileRange(
        minX = tileXFor(bounds.left),
        minY = tileYFor(bounds.top),
        maxX = tileXFor(bounds.right),
        maxY = tileYFor(bounds.bottom),
    )

    /** A stable integer key for a tile, safe for hashing and for disk filenames. */
    public fun keyOf(tileX: Int, tileY: Int): Long =
        (tileX.toLong() shl 32) or (tileY.toLong() and 0xFFFFFFFFL)
}

/** Inclusive rectangle of tile coordinates. */
public data class TileRange(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
) {
    init {
        require(maxX >= minX) { "maxX ($maxX) must be >= minX ($minX)" }
        require(maxY >= minY) { "maxY ($maxY) must be >= minY ($minY)" }
    }

    public val width: Int get() = maxX - minX + 1
    public val height: Int get() = maxY - minY + 1
    public val tileCount: Int get() = width * height

    /** Guards against a runaway loop if a stroke's bounds are absurd. */
    public fun isWithinLimit(maxTiles: Int): Boolean = tileCount <= maxTiles

    public fun contains(tileX: Int, tileY: Int): Boolean =
        tileX in minX..maxX && tileY in minY..maxY
}

/**
 * Bounded LRU store of tile payloads.
 *
 * Generic over the payload so the P1 implementation can hold an opaque GPU/texture
 * handle while tests hold a plain byte array. The store never inspects payloads.
 *
 * NOT THREAD-SAFE by design: it is owned by the render/input thread. Wrapping it in
 * a mutex would put a lock in the stroke path, which rule 6 forbids.
 *
 * @param maxTiles hard ceiling; exceeding it evicts least-recently-used tiles.
 */
public class TileStore<T>(
    private val maxTiles: Int = DEFAULT_MAX_TILES,
) {
    init {
        require(maxTiles > 0) { "maxTiles must be > 0, was $maxTiles" }
    }

    private val tiles = LinkedHashMap<Long, Entry<T>>(64, 0.75f, /* accessOrder = */ true)

    /** A stored tile plus the usage stamp the LRU relies on. */
    public class Entry<T>(public val payload: T, public var lastUsedTick: Long)

    /** Number of resident tiles. */
    public val size: Int get() = tiles.size

    private var tick: Long = 0

    /** Total bytes the store was told about, for the disk-spill threshold. */
    public var residentBytes: Long = 0L
        private set

    /**
     * Inserts or replaces a tile.
     *
     * @param byteSize payload size, used only for the memory budget.
     * @return tiles evicted to stay inside the budget.
     */
    public fun put(tileX: Int, tileY: Int, payload: T, byteSize: Long = 0L): List<Long> {
        val key = TileGrid.keyOf(tileX, tileY)
        tiles[key]?.let { residentBytes -= 0 } // replaced entries keep their budget slot
        tiles[key] = Entry(payload, ++tick)
        residentBytes += byteSize
        return evictIfNeeded()
    }

    public fun get(tileX: Int, tileY: Int): T? {
        val entry = tiles[TileGrid.keyOf(tileX, tileY)] ?: return null
        entry.lastUsedTick = ++tick
        return entry.payload
    }

    public fun contains(tileX: Int, tileY: Int): Boolean =
        tiles.containsKey(TileGrid.keyOf(tileX, tileY))

    public fun remove(tileX: Int, tileY: Int): T? =
        tiles.remove(TileGrid.keyOf(tileX, tileY))?.payload

    /**
     * Evicts until inside budget, oldest usage first.
     *
     * Returns the evicted keys so the caller can persist them; a cache that silently
     * drops artwork is worse than one that is slow.
     */
    public fun evictIfNeeded(): List<Long> {
        if (tiles.size <= maxTiles) return emptyList()
        val evicted = ArrayList<Long>()
        val iterator = tiles.entries.iterator()
        while (tiles.size > maxTiles && iterator.hasNext()) {
            val entry = iterator.next()
            evicted += entry.key
            iterator.remove()
        }
        return evicted
    }

    public fun clear() {
        tiles.clear()
        residentBytes = 0L
    }

    /** Keys currently resident, for diagnostics and tests. */
    public fun keys(): Set<Long> = tiles.keys.toSet()

    public companion object {
        /**
         * 256 tiles: at 512 canvas units each, that is roughly an A0 sheet at 100%
         * zoom — far beyond what one screen needs, while staying well inside a
         * mid-range tablet's bitmap budget.
         */
        public const val DEFAULT_MAX_TILES: Int = 256
    }
}
