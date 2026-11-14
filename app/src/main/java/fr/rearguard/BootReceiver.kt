package fr.rearguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Démarrage automatique quand l'écran s'allume (démarrage complet ou réveil "rapide"
 * des autoradios, qui souvent ne redémarrent pas vraiment mais sortent de veille).
 * Priorité maximale pour passer avant les autres applis (dashcam d'origine).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("RearGuard", "Démarrage : ${intent.action}")
        if (!Prefs.autoStart(context) || DetectionService.isRunning) return
        launch(context)
    }

    companion object {
        fun launch(context: Context) {
            val canShowActivity = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                Settings.canDrawOverlays(context)
            if (canShowActivity) {
                // Passer par l'écran de l'appli : Android donne alors l'accès caméra au service
                val i = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(MainActivity.EXTRA_AUTOSTART, true)
                try { context.startActivity(i); return } catch (e: Exception) { Log.e("RearGuard", "Lancement écran", e) }
            }
            // Sinon on tente le service directement (peut être refusé par Android)
            try { DetectionService.start(context) } catch (e: Exception) { Log.e("RearGuard", "Lancement service", e) }
        }
    }
}
