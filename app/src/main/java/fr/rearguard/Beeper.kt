package fr.rearguard

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** Bips répétés : lents en ATTENTION, rapides en DANGER (comme un radar de recul). */
class Beeper {
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    private val handler = Handler(Looper.getMainLooper())
    private var level = AlertLevel.NONE
    private var running = false
    private var released = false

    private val tick = object : Runnable {
        override fun run() {
            when (level) {
                AlertLevel.NONE -> running = false
                AlertLevel.ATTENTION -> {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                    handler.postDelayed(this, 700)
                }
                AlertLevel.DANGER -> {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                    handler.postDelayed(this, 220)
                }
            }
        }
    }

    fun setLevel(newLevel: AlertLevel) {
        handler.post {
            if (released) return@post
            level = newLevel
            if (newLevel != AlertLevel.NONE && !running) {
                running = true
                handler.post(tick)
            }
        }
    }

    fun testBeep() {
        handler.post { if (!released) tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400) }
    }

    fun release() {
        handler.post {
            released = true
            level = AlertLevel.NONE
            handler.removeCallbacks(tick)
            tone.release()
        }
    }
}
