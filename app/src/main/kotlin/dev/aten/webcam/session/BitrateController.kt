package dev.aten.webcam.session

/**
 * Backs the video bitrate off quickly when a viewer's link cannot keep up and creeps back up once
 * frames flow freely again. Driven purely by frame events, so it needs no timer of its own.
 */
class BitrateController(private val clock: () -> Long) {
    var scale = 1f
        private set
    private var lastDecreaseAt = Long.MIN_VALUE / 2
    private var lastChangeAt = Long.MIN_VALUE / 2

    /** Returns the new scale when it changed, otherwise null. */
    fun onCongestion(): Float? {
        val now = clock()
        if (now - lastDecreaseAt < DECREASE_INTERVAL_MS || scale <= MIN_SCALE) return null
        scale = (scale * DECREASE_FACTOR).coerceAtLeast(MIN_SCALE)
        lastDecreaseAt = now
        lastChangeAt = now
        return scale
    }

    fun onFrameDelivered(): Float? {
        if (scale >= 1f) return null
        val now = clock()
        if (now - lastChangeAt < INCREASE_INTERVAL_MS) return null
        scale = (scale * INCREASE_FACTOR).coerceAtMost(1f)
        lastChangeAt = now
        return scale
    }

    fun reset() {
        scale = 1f
        lastDecreaseAt = Long.MIN_VALUE / 2
        lastChangeAt = Long.MIN_VALUE / 2
    }

    companion object {
        const val MIN_SCALE = 0.25f
        const val DECREASE_FACTOR = 0.7f
        const val INCREASE_FACTOR = 1.15f
        const val DECREASE_INTERVAL_MS = 2_000L
        const val INCREASE_INTERVAL_MS = 10_000L
    }
}
