package fr.rearguard

import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable

/** Relie détecteur + suivi de proximité + bips. process() est appelé sur le thread d'analyse. */
class DetectionEngine(
    context: Context,
    private val listener: ((FrameResult, AlertLevel) -> Unit)? = null
) : Closeable {

    private val detector = VehicleDetector(context.applicationContext)
    val tracker = ProximityTracker()
    private val beeper = Beeper()
    @Volatile private var closed = false
    private var lastLevel = AlertLevel.NONE

    init {
        tracker.sensitivity = Prefs.sensitivity(context) / 100f
        tracker.centerZoneOnly = Prefs.centerZone(context)
    }

    fun process(bitmap: Bitmap) {
        if (closed) return
        val result = detector.detect(bitmap)
        val level = tracker.update(result)
        if (level != lastLevel) {
            lastLevel = level
            beeper.setLevel(level)
        }
        listener?.invoke(result, level)
    }

    fun testBeep() = beeper.testBeep()

    /** À appeler APRÈS avoir arrêté la caméra (CameraSource.stop). */
    override fun close() {
        closed = true
        beeper.release()
        detector.close()
    }
}
