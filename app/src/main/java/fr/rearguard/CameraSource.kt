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
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ouvre une caméra via Camera2 (fonctionne aussi avec les caméras "externes"
 * des autoradios Android) et fournit des images Bitmap à analyser.
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
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    @Volatile private var stopped = false

    @SuppressLint("MissingPermission")
    fun start() {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = mgr.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("Caméra $cameraId sans configuration de flux")

        val analysisSize = chooseSize(map.getOutputSizes(ImageFormat.YUV_420_888), 640)
        val r = ImageReader.newInstance(analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ ir ->
            val img = (try { ir.acquireLatestImage() } catch (e: Exception) { null }) ?: return@setOnImageAvailableListener
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

        mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(cd: CameraDevice) {
                if (stopped) { cd.close(); return }
                device = cd
                createSession(cd, withPreview = previewSurface != null)
            }
            override fun onDisconnected(cd: CameraDevice) {
                cd.close(); device = null
                onError("Caméra déconnectée (utilisée par une autre appli ?)")
            }
            override fun onError(cd: CameraDevice, error: Int) {
                cd.close(); device = null
                onError("Erreur caméra n°$error (caméra occupée ou non alimentée ?)")
            }
        }, handler)
    }

    @Suppress("DEPRECATION")
    private fun createSession(cd: CameraDevice, withPreview: Boolean) {
        val readerSurface = reader?.surface ?: return
        val targets = mutableListOf(readerSurface)
        if (withPreview) previewSurface?.let { targets.add(it) }
        try {
            cd.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (stopped) { s.close(); return }
                    session = s
                    try {
                        val req = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        targets.forEach { req.addTarget(it) }
                        s.setRepeatingRequest(req.build(), null, handler)
                    } catch (e: Exception) {
                        onError("Démarrage du flux impossible : ${e.message}")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (withPreview) {
                        // Certains autoradios n'acceptent qu'un seul flux : on continue sans aperçu
                        onError("Aperçu impossible sur cette caméra, détection seule active")
                        createSession(cd, withPreview = false)
                    } else {
                        onError("Configuration de la caméra refusée")
                    }
                }
            }, handler)
        } catch (e: Exception) {
            onError("Erreur caméra : ${e.message}")
        }
    }

    fun stop() {
        stopped = true
        handler.post {
            try { session?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            session = null; device = null
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
