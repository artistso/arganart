package art.argan.core.ink

/**
 * A reversible edit to the artwork.
 *
 * Undo is a command pattern rather than a snapshot stack, and each command stores a
 * **tile diff** rather than whole-canvas pixels. A stroke that crosses four tiles
 * costs four tile images, not a 4000×4000 bitmap — which is the difference between
 * 100 undo steps fitting in memory and not.
 */
public sealed interface EditCommand {
    /** Human-readable label, useful for a history scrubber tooltip. */
    public val label: String

    /** Tile range this command can affect; used to bound redraws on undo. */
    public val affectedTiles: TileRange

    /** Applies the edit. Called on redo and on the initial apply. */
    public fun apply()

    /** Reverses the edit. */
    public fun revert()
}

/**
 * A stroke committed to a tile layer.
 *
 * @param tileBefores per-tile pixels captured *before* the stroke, keyed by tile.
 * @param tileAfters per-tile pixels *after*, so redo does not need to re-rasterise
 *   (re-rasterising could produce a different result after a brush-setting change).
 */
public class StrokeCommand(
    override val label: String,
    override val affectedTiles: TileRange,
    private val tileBefores: Map<Long, ByteArray>,
    private val tileAfters: Map<Long, ByteArray>,
    private val writeTile: (key: Long, pixels: ByteArray) -> Unit,
) : EditCommand {

    init {
        require(tileBefores.keys == tileAfters.keys) {
            "before and after tile sets must match: ${tileBefores.keys} vs ${tileAfters.keys}"
        }
    }

    override fun apply() {
        for ((key, pixels) in tileAfters) writeTile(key, pixels)
    }

    override fun revert() {
        for ((key, pixels) in tileBefores) writeTile(key, pixels)
    }

    /** Bytes this command holds, for the history's own budget. */
    public fun byteSize(): Long =
        tileBefores.values.sumOf { it.size.toLong() } + tileAfters.values.sumOf { it.size.toLong() }
}

/**
 * Undo/redo stack with a bounded memory footprint.
 *
 * Two limits, both enforced: a step count (P1 requires at least 100) and a byte
 * budget. The byte budget is the one that matters in practice — 100 steps of a
 * large brush can be hundreds of megabytes, so the oldest steps are dropped when
 * memory pressure demands it rather than letting the app die politely.
 *
 * NOT THREAD-SAFE: owned by the document/input thread.
 */
public class History(
    private val maxSteps: Int = MAX_STEPS,
    private val maxBytes: Long = MAX_BYTES,
) {
    init {
        require(maxSteps >= Stroke.MIN_UNDO_STEPS) {
            "maxSteps must be at least ${Stroke.MIN_UNDO_STEPS}, was $maxSteps"
        }
        require(maxBytes > 0) { "maxBytes must be > 0" }
    }

    private val undoStack = ArrayDeque<EditCommand>()
    private val redoStack = ArrayDeque<EditCommand>()
    private var residentBytes: Long = 0L

    /** Steps available to undo. */
    public val undoCount: Int get() = undoStack.size

    /** Steps available to redo. */
    public val redoCount: Int get() = redoStack.size

    public val canUndo: Boolean get() = undoStack.isNotEmpty()
    public val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Approximate bytes held by retained commands. */
    public val heldBytes: Long get() = residentBytes

    /** Label of the next undo, for the scrubber. */
    public fun peekUndoLabel(): String? = undoStack.lastOrNull()?.label

    /**
     * Records a command that has ALREADY been applied to the pixels.
     *
     * Invalidates the redo stack: this is the rule that makes undo/redo behave the
     * way users expect after drawing a new stroke mid-history.
     */
    public fun push(command: EditCommand) {
        // A new edit makes the future non-existent.
        redoStack.clear()
        undoStack.addLast(command)
        residentBytes += command.byteSizeOrZero()
        trimToBudget()
    }

    /** Reverts the most recent command and moves it to the redo stack. */
    public fun undo(): EditCommand? {
        val command = undoStack.removeLastOrNull() ?: return null
        command.revert()
        redoStack.addLast(command)
        return command
    }

    /** Re-applies the most recently undone command. */
    public fun redo(): EditCommand? {
        val command = redoStack.removeLastOrNull() ?: return null
        command.apply()
        undoStack.addLast(command)
        return command
    }

    public fun clear() {
        undoStack.clear()
        redoStack.clear()
        residentBytes = 0L
    }

    /** Drops the oldest undo steps until both budgets are satisfied. */
    private fun trimToBudget() {
        while (undoStack.size > maxSteps) {
            residentBytes -= undoStack.removeFirst().byteSizeOrZero()
        }
        while (residentBytes > maxBytes && undoStack.size > 1) {
            residentBytes -= undoStack.removeFirst().byteSizeOrZero()
        }
    }

    private fun EditCommand.byteSizeOrZero(): Long =
        if (this is StrokeCommand) byteSize() else 0L

    public companion object {
        /** Comfortably above P1's 100-step requirement. */
        public const val MAX_STEPS: Int = 200

        /** 96 MB: generous for strokes, small enough not to threaten a mid-range tablet. */
        public const val MAX_BYTES: Long = 96L * 1024 * 1024
    }
}
