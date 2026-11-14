package fr.rearguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Surveillance en arrière-plan (fonctionne pendant que CarPlay / Android Auto est affiché). */
class DetectionService : Service() {

    private var engine: DetectionEngine? = null
    private var camera: CameraSource? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var notifiedLevel: AlertLevel? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (camera != null) return START_STICKY

        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Recherche de la caméra…"), type)
        isRunning = true

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rearguard:detect").apply { acquire() }

        try {
            val id = Prefs.cameraId(this) ?: CameraSource.listCameras(this).firstOrNull()?.first
                ?: throw IllegalStateException("Aucune caméra trouvée")
            val eng = DetectionEngine(this) { _, level ->
                if (level != notifiedLevel) {
                    notifiedLevel = level
                    notify(
                        when (level) {
                            AlertLevel.NONE -> "Surveillance active"
                            AlertLevel.ATTENTION -> "Véhicule derrière"
                            AlertLevel.DANGER -> "VÉHICULE TRÈS PROCHE"
                        }
                    )
                }
            }
            engine = eng
            camera = CameraSource(this, id, null, eng::process) { msg -> notify(msg) }.also { it.start() }
        } catch (e: Exception) {
            notify("Erreur : ${e.message}")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        camera?.stop(); camera = null
        engine?.close(); engine = null
        wakeLock?.let { if (it.isHeld) it.release() }
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Surveillance arrière", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DetectionService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("RearGuard")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
            .build()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_STOP = "fr.rearguard.STOP"
        private const val CHANNEL = "rearguard"
        private const val NOTIF_ID = 42
        @Volatile var isRunning = false
            private set

        fun start(c: Context) = ContextCompat.startForegroundService(c, Intent(c, DetectionService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, DetectionService::class.java))
    }
}
