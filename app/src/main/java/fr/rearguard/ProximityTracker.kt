package fr.rearguard

import android.os.SystemClock

enum class AlertLevel { NONE, ATTENTION, DANGER }

/**
 * Transforme les détections en niveau d'alerte.
 * Idée : plus un véhicule est proche, plus son cadre occupe de place dans l'image.
 * On alerte aussi si le cadre grossit vite (véhicule qui se rapproche rapidement).
 */
class ProximityTracker {
    /** 0 = peu sensible (véhicule très proche seulement), 1 = très sensible. */
    @Volatile var sensitivity = 0.5f
    /** Ne considérer que les véhicules au centre de l'image (votre voie). */
    @Volatile var centerZoneOnly = true

    var level = AlertLevel.NONE
        private set
    var smoothedArea = 0f
        private set

    private val history = ArrayDeque<Pair<Long, Float>>()
    private var candidate = AlertLevel.NONE
    private var candidateCount = 0

    fun dangerArea() = 0.30f - 0.24f * sensitivity.coerceIn(0f, 1f)
    fun attentionArea() = dangerArea() * 0.45f

    fun update(result: FrameResult, now: Long = SystemClock.elapsedRealtime()): AlertLevel {
        val relevant = result.vehicles.filter { v ->
            !centerZoneOnly || v.box.centerX() in 0.2f..0.8f
        }
        val area = relevant.maxOfOrNull { it.area } ?: 0f
        smoothedArea = if (area == 0f) smoothedArea * 0.6f else smoothedArea * 0.5f + area * 0.5f

        history.addLast(now to smoothedArea)
        while (history.isNotEmpty() && now - history.first().first > 1500) history.removeFirst()

        val danger = dangerArea()
        val attention = attentionArea()
        val oldest = history.firstOrNull()?.second ?: smoothedArea
        val approachingFast = oldest > 0.005f && smoothedArea > attention * 0.6f && smoothedArea / oldest > 1.6f

        val raw = when {
            smoothedArea >= danger -> AlertLevel.DANGER
            approachingFast && smoothedArea >= attention -> AlertLevel.DANGER
            smoothedArea >= attention || approachingFast -> AlertLevel.ATTENTION
            else -> AlertLevel.NONE
        }

        // Hystérésis : on monte vite (2 images), on redescend lentement (6 images)
        if (raw == level) {
            candidateCount = 0
        } else {
            if (raw == candidate) candidateCount++ else { candidate = raw; candidateCount = 1 }
            val needed = if (raw.ordinal > level.ordinal) 2 else 6
            if (candidateCount >= needed) {
                level = raw
                candidateCount = 0
            }
        }
        return level
    }
}
