package fr.rearguard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ouvre une caméra via Camera2 (fonctionne aussi avec les caméras "externes"
 * des autoradios Android) et fournit des images Bitmap à analyser.
 *
 * Partage avec l'appli d'origine : si la caméra est déjà utilisée (enregistreur
 * Carpuride par exemple), on attend qu'Android la signale libre, puis on la prend.
 * Si elle nous est reprise, on la rend sans insister et on attend de nouveau.
 * On ne tente jamais d'ouvrir une caméra signalée occupée, pour ne pas la voler
 * à l'appli d'origine (caméra de recul).
 */
class CameraSource(
    private val context: Context,
    private val cameraId: String,
    private val previewTexture: SurfaceTexture?,
    private val onFrame: (Bitmap) -> Unit,
    private val onError: (String) -> Unit
) {
    private val thread = HandlerThread("rearguard-cam").apply { start() }
    private val handler = Handler(thread.looper)
    private val analysis = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    @Volatile private var stopped = false

    // Accès au handler uniquement (thread caméra)
    private var opening = false
    /** null = inconnu, true/false = dernier état signalé par Android. */
    private var available: Boolean? = null
    private var previewAllowed = true
    @Volatile private var lastFrameAt = 0L
    private var lastStatus = ""

    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(id: String) {
            if (id != cameraId) return
            available = true
            if (device == null && !opening && !stopped) {
                // Petit délai : laisse l'autre appli finir de libérer la caméra
                handler.postDelayed({ tryOpen() }, 500)
            }
        }
        override fun onCameraUnavailable(id: String) {
            if (id != cameraId) return
            available = false
            if (device == null && !opening) status("En attente de la caméra $cameraId (utilisée par une autre appli)…")
        }
    }

    /** Relance périodique : certains autoradios ne signalent pas la libération de la caméra. */
    private val retry = object : Runnable {
        override fun run() {
            if (stopped) return
            if (device == null && !opening && available != false) tryOpen()
            handler.postDelayed(this, RETRY_MS)
        }
    }

    /** Si les images n'arrivent plus sans erreur signalée, on referme et on attend. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (stopped) return
            if (device != null && session != null && lastFrameAt > 0 &&
                SystemClock.elapsedRealtime() - lastFrameAt > STALL_MS) {
                Log.w(TAG, "Plus d'images depuis ${STALL_MS} ms, réouverture")
                release()
                status("Flux caméra interrompu, nouvelle tentative…")
            }
            handler.postDelayed(this, STALL_MS / 2)
        }
    }

    fun start() {
        val chars = mgr.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("Caméra $cameraId sans configuration de flux")

        val analysisSize = chooseSize(map.getOutputSizes(ImageFormat.YUV_420_888), 640)
        val r = ImageReader.newInstance(analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ ir ->
            val img = (try { ir.acquireLatestImage() } catch (e: Exception) { null }) ?: return@setOnImageAvailableListener
            lastFrameAt = SystemClock.elapsedRealtime()
            if (stopped || !busy.compareAndSet(false, true)) {
                img.close()
                return@setOnImageAvailableListener
            }
            val bmp = try {
                YuvConverter.toBitmap(img)
            } catch (e: Exception) {
                Log.e(TAG, "Conversion YUV", e)
                null
            } finally {
                img.close()
            }
            if (bmp == null) { busy.set(false); return@setOnImageAvailableListener }
            try {
                analysis.execute {
                    try { if (!stopped) onFrame(bmp) }
                    catch (e: Exception) { Log.e(TAG, "Analyse", e) }
                    finally { busy.set(false) }
                }
            } catch (e: Exception) { busy.set(false) }
        }, handler)
        reader = r

        previewTexture?.let { st ->
            val ps = chooseSize(map.getOutputSizes(SurfaceTexture::class.java), 1280)
            st.setDefaultBufferSize(ps.width, ps.height)
            previewSurface = Surface(st)
        }

        status("Recherche de la caméra $cameraId…")
        // L'enregistrement déclenche un premier appel avec l'état actuel de la caméra
        mgr.registerAvailabilityCallback(availability, handler)
        handler.postDelayed(retry, 1500)
        handler.postDelayed(watchdog, STALL_MS)
    }

    @SuppressLint("MissingPermission")
    private fun tryOpen() {
        if (stopped || device != null || opening) return
        opening = true
        try {
            mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    opening = false
                    if (stopped) { cd.close(); return }
                    device = cd
                    lastFrameAt = 0L
                    createSession(cd, withPreview = previewSurface != null && previewAllowed)
                }
                override fun onDisconnected(cd: CameraDevice) {
                    opening = false
                    if (device === cd || device == null) release() else cd.close()
                    status("Caméra reprise par une autre appli, en attente qu'elle se libère…")
                }
                override fun onError(cd: CameraDevice, error: Int) {
                    opening = false
                    if (device === cd || device == null) release() else cd.close()
                    status(
                        when (error) {
                            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
                            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
                                "Caméra occupée par une autre appli, en attente…"
                            else -> "Erreur caméra n°$error, nouvelle tentative…"
                        }
                    )
                }
            }, handler)
        } catch (e: Exception) {
            // CameraAccessException (occupée, désactivée…) : on réessaiera plus tard
            opening = false
            status("Caméra indisponible (${e.message}), en attente…")
        }
    }

    /** Referme proprement la session et la caméra sans arrêter la surveillance. */
    private fun release() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        session = null; device = null
        lastFrameAt = 0L
    }

    private fun status(msg: String) {
        if (msg == lastStatus) return
        lastStatus = msg
        Log.i(TAG, msg)
        onError(msg)
    }

    @Suppress("DEPRECATION")
    private fun createSession(cd: CameraDevice, withPreview: Boolean) {
        val readerSurface = reader?.surface ?: return
        val targets = mutableListOf(readerSurface)
        if (withPreview) previewSurface?.let { targets.add(it) }
        try {
            cd.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (stopped || device !== cd) { s.close(); return }
                    session = s
                    try {
                        val req = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        targets.forEach { req.addTarget(it) }
                        s.setRepeatingRequest(req.build(), null, handler)
                        lastFrameAt = SystemClock.elapsedRealtime()
                        status("Caméra $cameraId connectée, analyse en cours")
                    } catch (e: Exception) {
                        release()
                        status("Démarrage du flux impossible (${e.message}), nouvelle tentative…")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (device !== cd) return
                    if (withPreview) {
                        // Certains autoradios n'acceptent qu'un seul flux : on continue sans aperçu
                        previewAllowed = false
                        status("Aperçu impossible sur cette caméra, détection seule active")
                        createSession(cd, withPreview = false)
                    } else {
                        release()
                        status("Configuration de la caméra refusée, nouvelle tentative…")
                    }
                }
            }, handler)
        } catch (e: Exception) {
            release()
            status("Erreur caméra (${e.message}), nouvelle tentative…")
        }
    }

    fun stop() {
        stopped = true
        try { mgr.unregisterAvailabilityCallback(availability) } catch (_: Exception) {}
        handler.post {
            handler.removeCallbacksAndMessages(null)
            release()
        }
        analysis.shutdown()
        try { analysis.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        handler.post {
            try { reader?.close() } catch (_: Exception) {}
            previewSurface?.release()
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "RearGuard"
        private const val RETRY_MS = 3000L
        private const val STALL_MS = 5000L

        /** Plus grande taille de largeur <= maxWidth, sinon la plus petite disponible. */
        fun chooseSize(sizes: Array<Size>?, maxWidth: Int): Size {
            if (sizes.isNullOrEmpty()) return Size(640, 480)
            return sizes.filter { it.width <= maxWidth }.maxByOrNull { it.width * it.height }
                ?: sizes.minBy { it.width * it.height }
        }

        fun listCameras(context: Context): List<Pair<String, String>> {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            return try {
                mgr.cameraIdList.map { id ->
                    val facing = try {
                        when (mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                            CameraCharacteristics.LENS_FACING_BACK -> "arrière"
                            CameraCharacteristics.LENS_FACING_FRONT -> "avant"
                            CameraCharacteristics.LENS_FACING_EXTERNAL -> "externe"
                            else -> "inconnue"
                        }
                    } catch (e: Exception) { "inconnue" }
                    id to "Caméra $id ($facing)"
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
