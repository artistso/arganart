package art.argan.core.ink

/**
 * The infinite canvas's transform: scale plus translation.
 *
 * Held here (not in the UI module) because panning and zooming are geometry, and
 * because the spatial resolver (P3) and tile cache (P1) both need the same
 * conversion without depending on Compose.
 *
 * @property scale device-independent em per canvas unit; `1f` = 100% zoom.
 * @property translateX/translateY device-independent offset of canvas origin.
 */
public data class CanvasTransform(
    val scale: Float = 1f,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
) {
    init {
        require(scale in MIN_SCALE..MAX_SCALE) {
            "scale must be in $MIN_SCALE..$MAX_SCALE, was $scale"
        }
    }

    public fun canvasToScreenX(canvasX: Float): Float = canvasX * scale + translateX
    public fun canvasToScreenY(canvasY: Float): Float = canvasY * scale + translateY
    public fun screenToCanvasX(screenX: Float): Float = (screenX - translateX) / scale
    public fun screenToCanvasY(screenY: Float): Float = (screenY - translateY) / scale

    /** Pans by a screen-space delta. */
    public fun panBy(dxScreen: Float, dyScreen: Float): CanvasTransform =
        copy(translateX = translateX + dxScreen, translateY = translateY + dyScreen)

    /**
     * Zooms about a screen-space focus point, keeping the canvas point under the
     * focus stationary. This is what makes pinch-zoom feel anchored to the fingers.
     */
    public fun zoomAbout(focusX: Float, focusY: Float, factor: Float): CanvasTransform {
        val canvasX = screenToCanvasX(focusX)
        val canvasY = screenToCanvasY(focusY)
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        return copy(
            scale = newScale,
            translateX = focusX - canvasX * newScale,
            translateY = focusY - canvasY * newScale,
        )
    }

    public companion object {
        public const val MIN_SCALE: Float = 0.05f
        public const val MAX_SCALE: Float = 32f
    }
}
