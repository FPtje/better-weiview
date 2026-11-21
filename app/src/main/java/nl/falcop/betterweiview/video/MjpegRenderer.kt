package nl.falcop.betterweiview.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.SurfaceHolder
import androidx.core.graphics.withSave

/**
 * Decodes MJPEG frames and draws them aspect-fit onto a SurfaceView.
 * [render] runs on a background thread; the surface callbacks run on the main thread,
 * so both sides synchronise on [lock].
 */
class MjpegRenderer : SurfaceHolder.Callback {
    private val lock = Any()
    private var holder: SurfaceHolder? = null
    private var reusable: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val circle = Path()

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(lock) { this.holder = holder }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(lock) { this.holder = null }
    }

    /**
     * Draws [jpeg] aspect-fit. With a [rotation] (degrees clockwise), the picture is turned
     * about its centre and clipped to its inscribed circle, so the corners don't swing in
     * and out of view. Returns false if the JPEG could not be decoded.
     */
    fun render(jpeg: ByteArray, rotation: Float? = null): Boolean {
        val bitmap = decode(jpeg) ?: return false
        synchronized(lock) {
            val h = holder ?: return true
            if (!h.surface.isValid) return true
            val canvas = h.lockHardwareCanvas() ?: return true
            try {
                canvas.drawColor(Color.BLACK)
                val scale = minOf(canvas.width.toFloat() / bitmap.width, canvas.height.toFloat() / bitmap.height)
                val w = bitmap.width * scale
                val hgt = bitmap.height * scale
                dst.set((canvas.width - w) / 2, (canvas.height - hgt) / 2, (canvas.width + w) / 2, (canvas.height + hgt) / 2)
                canvas.withSave {
                    if (rotation != null) {
                        circle.rewind()
                        circle.addCircle(dst.centerX(), dst.centerY(), minOf(w, hgt) / 2, Path.Direction.CW)
                        clipPath(circle)
                        rotate(rotation, dst.centerX(), dst.centerY())
                    }
                    drawBitmap(bitmap, null, dst, paint)
                }
            } finally {
                h.unlockCanvasAndPost(canvas)
            }
        }
        return true
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inBitmap = reusable
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        } catch (_: IllegalArgumentException) {
            // Frame size changed, so the old bitmap can't be reused.
            opts.inBitmap = null
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        }
        if (bitmap != null) reusable = bitmap
        return bitmap
    }
}
