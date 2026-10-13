package nl.falcop.betterweiview.video

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Turns the camera's per-frame accelerometer reading into a screen rotation that keeps
 * the picture upright. The x axis runs along the lens, so the roll around the lens is
 * given by gravity's y/z components; rotating by -atan2(y, z) cancels it.
 *
 * The angle is smoothed on the unit circle (no wrap-around jumps at ±180°). When the lens
 * points nearly straight up or down, roll is undefined, so the last angle is kept.
 */
class TiltEstimator(private val smoothing: Float = 0.7f) {
    private var cx = 1f
    private var cy = 0f
    private var initialised = false

    /** Returns the rotation in degrees (clockwise, as for Canvas.rotate). */
    fun update(x: Int, y: Int, z: Int): Float {
        val roll = hypot(y.toFloat(), z.toFloat())
        val total = hypot(x.toFloat(), roll)
        if (total > 0f && roll / total >= MIN_ROLL_FRACTION) {
            val target = -atan2(y.toFloat(), z.toFloat())
            val tx = cos(target)
            val ty = sin(target)
            if (initialised) {
                cx = smoothing * cx + (1 - smoothing) * tx
                cy = smoothing * cy + (1 - smoothing) * ty
            } else {
                cx = tx; cy = ty; initialised = true
            }
        }
        return Math.toDegrees(atan2(cy, cx).toDouble()).toFloat()
    }

    fun reset() {
        cx = 1f; cy = 0f; initialised = false
    }

    private companion object {
        /** Below this share of gravity across the lens axis, the roll reading is just noise. */
        const val MIN_ROLL_FRACTION = 0.25f
    }
}
