package art.argan.core.motion

/**
 * The plain, backend-free snapshot of a ribbon clip.
 *
 * ## Why not just `@Serializable`
 *
 * P9 owns the file format and may well choose kotlinx.serialization. Committing to it
 * now would put an annotation-driven dependency into a module whose whole point is
 * being pure and dependency-light, and it would decide a P9 question in P2.
 *
 * What the P2 brief actually demands is stronger and simpler: *every motion state is
 * serialisable and reloads identically*. That is a property, not a format. So this type
 * is a flat, orderable, lossless description with `encode`/`decode`, plus a compact
 * text form so P9 can write it into a manifest with no extra machinery, and a test can
 * assert field-for-field equality after a round trip.
 *
 * Every float is stored, not derived: nothing is recomputed on load that could differ
 * between platforms, and nothing is dropped that would make a reload *look* right while
 * being a different clip.
 */
public data class MotionClipSnapshot(
    /** Spine control points, flattened `x, y, x, y, ...`. */
    val spineCoordinates: List<Float>,
    val nodes: List<MotionNodeSnapshot>,
    val durationMs: Int,
    val isLooping: Boolean,
    val followPath: Boolean,
) {
    init {
        require(spineCoordinates.size >= 4 && spineCoordinates.size % 2 == 0) {
            "spineCoordinates needs at least 2 (x, y) pairs, got ${spineCoordinates.size} values"
        }
        require(nodes.size >= 2) { "a clip keeps at least 2 nodes, got ${nodes.size}" }
        require(durationMs > 0) { "durationMs must be > 0, was $durationMs" }
    }

    /** A new, independent clip. Two decodes of the same snapshot are independent objects. */
    public fun decode(): MotionRibbonClip {
        val spine = MotionSpine.of(*spineCoordinates.toFloatArray())
        return MotionRibbonClip.create(
            spine = spine,
            nodes = nodes.map { it.decode() },
            durationMs = durationMs,
            isLooping = isLooping,
            followPath = followPath,
        )
    }

    /** Compact text form: one line, stable field order, floats in full precision. */
    public fun encode(): String = buildString {
        append(ENCODING_VERSION)
        append('|').append(durationMs)
        append('|').append(if (isLooping) 1 else 0)
        append('|').append(if (followPath) 1 else 0)
        append('|').append(spineCoordinates.joinToString(","))
        append('|')
        nodes.forEachIndexed { index, node ->
            if (index > 0) append(';')
            append(node.encode())
        }
    }

    public companion object {
        /** Bumped only if the field layout changes; [decode] rejects unknown versions. */
        public const val ENCODING_VERSION: String = "argan.motion/1"

        /** Captures a live clip. Allocates; not for hot paths. */
        public fun of(clip: MotionRibbonClip): MotionClipSnapshot {
            val points = clip.spine.points()
            val coordinates = ArrayList<Float>(points.size * 2)
            for (p in points) {
                coordinates.add(p.x)
                coordinates.add(p.y)
            }
            return MotionClipSnapshot(
                spineCoordinates = coordinates,
                nodes = clip.nodes.map { MotionNodeSnapshot.of(it) },
                durationMs = clip.durationMs,
                isLooping = clip.isLooping,
                followPath = clip.followPath,
            )
        }

        /**
         * Parses [encode]'s output.
         *
         * @throws IllegalArgumentException on a truncated, mis-versioned or malformed
         *   payload — loudly, because silently loading a motion as *nothing* is worse
         *   than refusing to open it.
         */
        public fun decode(encoded: String): MotionClipSnapshot {
            val parts = encoded.split('|')
            require(parts.size == 6) { "expected 6 sections, got ${parts.size}" }
            require(parts[0] == ENCODING_VERSION) {
                "unsupported motion encoding '${parts[0]}', expected '$ENCODING_VERSION'"
            }
            val durationMs = parts[1].toIntOrNull()
                ?: throw IllegalArgumentException("durationMs is not an integer: '${parts[1]}'")
            val isLooping = parseFlag(parts[2], "isLooping")
            val followPath = parseFlag(parts[3], "followPath")
            val coordinates = parts[4].split(',').map {
                it.toFloatOrNull() ?: throw IllegalArgumentException("spine coordinate is not a float: '$it'")
            }
            val nodes = parts[5].split(';').map { MotionNodeSnapshot.decode(it) }
            return MotionClipSnapshot(coordinates, nodes, durationMs, isLooping, followPath)
        }

        private fun parseFlag(raw: String, name: String): Boolean = when (raw) {
            "0" -> false
            "1" -> true
            else -> throw IllegalArgumentException("$name must be 0 or 1, was '$raw'")
        }
    }
}

/** The serialisable form of one [MotionNode]. */
public data class MotionNodeSnapshot(
    val distance: Float,
    val speedMultiplier: Float,
    val holdFraction: Float,
    val stiffness: Float,
    val damping: Float,
    val mass: Float,
    val rotationDeltaDegrees: Float,
    val scaleDelta: Float,
) {
    public fun decode(): MotionNode = MotionNode(
        distance = distance,
        speedMultiplier = speedMultiplier,
        holdFraction = holdFraction,
        spring = SpringDynamics(stiffness, damping, mass),
        rotationDeltaDegrees = rotationDeltaDegrees,
        scaleDelta = scaleDelta,
    )

    public fun encode(): String = listOf(
        distance, speedMultiplier, holdFraction,
        stiffness, damping, mass,
        rotationDeltaDegrees, scaleDelta,
    ).joinToString(",")

    public companion object {
        public fun of(node: MotionNode): MotionNodeSnapshot = MotionNodeSnapshot(
            distance = node.distance,
            speedMultiplier = node.speedMultiplier,
            holdFraction = node.holdFraction,
            stiffness = node.spring.stiffness,
            damping = node.spring.damping,
            mass = node.spring.mass,
            rotationDeltaDegrees = node.rotationDeltaDegrees,
            scaleDelta = node.scaleDelta,
        )

        public fun decode(encoded: String): MotionNodeSnapshot {
            val parts = encoded.split(',').map {
                it.toFloatOrNull() ?: throw IllegalArgumentException("node field is not a float: '$it'")
            }
            require(parts.size == 8) { "a node needs 8 fields, got ${parts.size}" }
            return MotionNodeSnapshot(
                distance = parts[0],
                speedMultiplier = parts[1],
                holdFraction = parts[2],
                stiffness = parts[3],
                damping = parts[4],
                mass = parts[5],
                rotationDeltaDegrees = parts[6],
                scaleDelta = parts[7],
            )
        }
    }
}
