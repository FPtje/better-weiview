package nl.falcop.betterweiview.video

import org.junit.Assert.assertEquals
import org.junit.Test

class TiltEstimatorTest {
    private val g = 16384 // ~1 g at the camera's scale (z ≈ 17600 when lying flat)

    @Test
    fun uprightNeedsNoRotation() {
        assertEquals(0f, TiltEstimator().update(0, 0, g), 0.01f)
    }

    @Test
    fun rollIsCancelled() {
        // Gravity along +y: camera rolled 90°, so the picture turns -90°.
        assertEquals(-90f, TiltEstimator().update(0, g, 0), 0.01f)
        assertEquals(90f, TiltEstimator().update(0, -g, 0), 0.01f)
        assertEquals(180f, kotlin.math.abs(TiltEstimator().update(0, 0, -g)), 0.01f)
    }

    @Test
    fun realCaptureIsSlightlyTilted() {
        // Values observed from the camera lying on a desk.
        assertEquals(-6.1f, TiltEstimator().update(-144, 1888, 17568), 0.2f)
    }

    @Test
    fun lensPointingDownKeepsLastAngle() {
        val t = TiltEstimator(smoothing = 0f)
        t.update(0, g, 0)
        assertEquals(-90f, t.update(g, 100, 100), 0.01f)
    }

    @Test
    fun smoothingCrossesWrapAroundWithoutSpinning() {
        val t = TiltEstimator(smoothing = 0.5f)
        t.update(0, 1000, -g) // just below 180°
        val a = t.update(0, -1000, -g) // just past -180°
        assertEquals(180f, kotlin.math.abs(a), 1f)
    }
}
