package art.argan.ai.backend

/**
 * The complete, explicit description of a generation.
 *
 * There is no text prompt anywhere in this type, and there never will be
 * (ARCHITECTURE.md rule 2). Everything a model needs is expressed as spatial
 * layout, style reference, assist level and a mutation seed — all of which the
 * user sets by arranging objects on the canvas ([art.argan.core.spatial]).
 *
 * This DTO is intentionally serialisable by construction: only primitives,
 * enums, immutable data classes and lists. P9 writes it verbatim into a
 * project manifest, so changing a field's meaning is a format break.
 *
 * @property layout the resolved spatial graph (P3 produces it).
 * @property styleFingerprintRef opaque reference to a [StyleFingerprint];
 *   never the fingerprint contents themselves, so requests can be logged and
 *   stored without leaking private style data.
 * @property assistLevel 0f = pure human input, 1f = maximum machine suggestion.
 *   Clamped by the runtime, validated here.
 * @property mutationSeed seed for the anti-generic mutation (P5). Same seed +
 *   same request = same result on the same device and model version.
 * @property cancellationToken cooperative cancellation identity. The runtime
 *   must observe it at least once per emitted [GenerationEvent].
 */
public data class GenerationRequest(
    val layout: SpatialLayoutGraph,
    val styleFingerprintRef: StyleFingerprintRef?,
    val assistLevel: Float,
    val mutationSeed: Long,
    val cancellationToken: CancellationToken,
) {
    init {
        require(assistLevel.isFinite()) { "assistLevel must be finite, was $assistLevel" }
        require(assistLevel in 0f..1f) { "assistLevel must be in 0f..1f, was $assistLevel" }
    }

    /**
     * Identity of this request for caching and determinism checks.
     *
     * Excludes [cancellationToken] on purpose: cancellation is transport, not
     * content, so two requests that describe the same art share a cache key.
     */
    public val cacheKey: GenerationCacheKey
        get() = GenerationCacheKey(
            layoutFingerprint = layout.stableFingerprint(),
            styleFingerprintHash = styleFingerprintRef?.value?.hashCode() ?: 0,
            assistLevel = assistLevel,
            mutationSeed = mutationSeed,
        )
}

/** Stable identity of a generation request (see [GenerationRequest.cacheKey]). */
public data class GenerationCacheKey(
    val layoutFingerprint: Long,
    val styleFingerprintHash: Int,
    val assistLevel: Float,
    val mutationSeed: Long,
)

/**
 * The spatial prompt: a graph of canvas objects and their relationships.
 *
 * P0 defines only the shape, because [ModelBackend] must be expressible now.
 * The resolver that *builds* this graph is P3 and lives in `:core:spatial`;
 * its richer, role-annotated form arrives then and extends this type.
 *
 * @property nodes every participating canvas object.
 * @property edges weighted relationships between nodes.
 * @property frame output bounds and aspect ratio, in canvas units.
 * @property focusPoint the point the composition is read around.
 */
public data class SpatialLayoutGraph(
    val nodes: List<SpatialNode>,
    val edges: List<SpatialEdge>,
    val frame: FrameBounds,
    val focusPoint: Point2,
) {
    init {
        require(nodes.map { it.id }.toSet().size == nodes.size) { "node ids must be unique" }
        val ids = nodes.mapTo(HashSet()) { it.id }
        edges.forEach { edge ->
            require(edge.from in ids) { "edge.from ${edge.from} is not a node" }
            require(edge.to in ids) { "edge.to ${edge.to} is not a node" }
            require(edge.weight in 0f..1f) { "edge weight must be in 0f..1f, was ${edge.weight}" }
        }
    }

    /** Order-independent, allocation-light fingerprint used for caching. */
    public fun stableFingerprint(): Long {
        var hash = frame.hashCode().toLong()
        hash = hash * 31 + nodes.fold(0L) { acc, node -> acc * 31 + node.hashCode() }
        hash = hash * 31 + edges.fold(0L) { acc, edge -> acc * 31 + edge.hashCode() }
        return hash
    }
}

/** A canvas object participating in generation. */
public data class SpatialNode(
    val id: String,
    val kind: SpatialNodeKind,
    val bounds: FrameBounds,
)

/** What a participating object *is*, spatially. */
public enum class SpatialNodeKind {
    /** User strokes: content to be developed, not replaced. */
    SKETCH,

    /** Draggable colour chip: style modifier. */
    COLOR_SWATCH,

    /** Draggable texture/image reference: texture modifier. */
    TEXTURE_CARD,

    /** Pinned image used for style only, never content. */
    REFERENCE_CARD,

    /** Direction + speed indicator: motion modifier. */
    MOTION_ARROW,
}

/** A weighted spatial relationship. */
public data class SpatialEdge(
    val from: String,
    val to: String,
    val weight: Float,
)

/** Axis-aligned rectangle in canvas units. */
public data class FrameBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(right >= left) { "right ($right) must be >= left ($left)" }
        require(bottom >= top) { "bottom ($bottom) must be >= top ($top)" }
    }

    public val width: Float get() = right - left
    public val height: Float get() = bottom - top
    public val aspectRatio: Float get() = if (height == 0f) 0f else width / height
    public val center: Point2 get() = Point2((left + right) * 0.5f, (top + bottom) * 0.5f)

    /** True when [other] lies entirely inside this rectangle. */
    public fun contains(other: FrameBounds): Boolean =
        other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom
}

/** A point in canvas units. */
public data class Point2(val x: Float, val y: Float)

/**
 * Opaque, non-reversible handle to a [StyleFingerprint].
 *
 * Deliberately not the fingerprint: a [GenerationRequest] is persisted in project
 * manifests and shown in debug overlays, and a user's style DNA must not travel
 * with it (P6).
 */
@JvmInline
public value class StyleFingerprintRef(public val value: String) {
    init {
        require(value.isNotBlank()) { "StyleFingerprintRef must not be blank" }
    }
}

/**
 * Cooperative cancellation handle (rule 5: every long-running operation is
 * cancellable).
 *
 * Deliberately independent of coroutines so the DTO layer has no dependency on
 * the canceller's execution model, and so a request survives serialisation.
 */
public class CancellationToken {
    @Volatile
    private var cancelled: Boolean = false

    public fun cancel(): Unit {
        cancelled = true
    }

    public val isCancelled: Boolean get() = cancelled

    public fun throwIfCancelled(): Unit {
        if (cancelled) throw GenerationCancelledException()
    }
}

/** Thrown cooperatively when a request is cancelled; never an error to report. */
public class GenerationCancelledException : Exception("Generation was cancelled")
