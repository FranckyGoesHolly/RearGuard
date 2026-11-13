package fr.rearguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.Closeable

/** Un véhicule détecté ; coordonnées normalisées 0..1 dans l'image. */
data class VehicleBox(val box: RectF, val label: String, val score: Float) {
    val area: Float get() = box.width() * box.height()
}

data class FrameResult(val vehicles: List<VehicleBox>)

/** Détection de voitures / camions / bus / motos avec EfficientDet-Lite0 (MediaPipe). */
class VehicleDetector(context: Context, scoreThreshold: Float = 0.40f) : Closeable {

    private val detector: ObjectDetector

    init {
        val base = BaseOptions.builder().setModelAssetPath(MODEL_FILE).build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(scoreThreshold)
            .setMaxResults(10)
            .setCategoryAllowlist(VEHICLE_LABELS)
            .build()
        detector = ObjectDetector.createFromOptions(context, options)
    }

    fun detect(bitmap: Bitmap): FrameResult {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val result = detector.detect(BitmapImageBuilder(bitmap).build())
        val boxes = result.detections().mapNotNull { d ->
            val cat = d.categories().firstOrNull() ?: return@mapNotNull null
            val b = d.boundingBox()
            VehicleBox(
                RectF(
                    (b.left / w).coerceIn(0f, 1f),
                    (b.top / h).coerceIn(0f, 1f),
                    (b.right / w).coerceIn(0f, 1f),
                    (b.bottom / h).coerceIn(0f, 1f)
                ),
                cat.categoryName(),
                cat.score()
            )
        }
        return FrameResult(boxes)
    }

    override fun close() = detector.close()

    companion object {
        const val MODEL_FILE = "vehicle_detector.tflite"
        val VEHICLE_LABELS = listOf("car", "truck", "bus", "motorcycle")
    }
}
