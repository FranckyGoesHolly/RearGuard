package fr.rearguard

import android.graphics.Bitmap
import android.media.Image

object YuvConverter {
    private var pixels = IntArray(0)

    /** Convertit une image YUV_420_888 en Bitmap ARGB_8888 (gère les strides). */
    fun toBitmap(image: Image): Bitmap {
        val w = image.width
        val h = image.height
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val yRow = yP.rowStride; val yPix = yP.pixelStride
        val uRow = uP.rowStride; val uPix = uP.pixelStride
        val vRow = vP.rowStride; val vPix = vP.pixelStride
        if (pixels.size != w * h) pixels = IntArray(w * h)
        val out = pixels
        var idx = 0
        for (j in 0 until h) {
            val yOff = j * yRow
            val uOff = (j shr 1) * uRow
            val vOff = (j shr 1) * vRow
            for (i in 0 until w) {
                val y = yB.get(yOff + i * yPix).toInt() and 0xFF
                val u = (uB.get(uOff + (i shr 1) * uPix).toInt() and 0xFF) - 128
                val v = (vB.get(vOff + (i shr 1) * vPix).toInt() and 0xFF) - 128
                var r = y + ((359 * v) shr 8)
                var g = y - ((88 * u + 183 * v) shr 8)
                var b = y + ((454 * u) shr 8)
                r = if (r < 0) 0 else if (r > 255) 255 else r
                g = if (g < 0) 0 else if (g > 255) 255 else g
                b = if (b < 0) 0 else if (b > 255) 255 else b
                out[idx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
