package fr.rearguard

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.TextureView
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var preview: TextureView
    private lateinit var overlay: OverlayView
    private lateinit var status: TextView
    private lateinit var spinner: Spinner
    private lateinit var btnPreview: Button
    private lateinit var btnBackground: Button
    private lateinit var sensitivityLabel: TextView

    private var cameras: List<Pair<String, String>> = emptyList()
    private var engine: DetectionEngine? = null
    private var camera: CameraSource? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        spinner = findViewById(R.id.cameraSpinner)
        btnPreview = findViewById(R.id.btnPreview)
        btnBackground = findViewById(R.id.btnBackground)
        sensitivityLabel = findViewById(R.id.sensitivityLabel)

        val seek = findViewById<SeekBar>(R.id.sensitivity)
        seek.progress = Prefs.sensitivity(this)
        updateSensitivityLabel(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                Prefs.setSensitivity(this@MainActivity, p)
                engine?.tracker?.sensitivity = p / 100f
                updateSensitivityLabel(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        val center = findViewById<SwitchCompat>(R.id.centerZone)
        center.isChecked = Prefs.centerZone(this)
        overlay.showCenterZone = center.isChecked
        center.setOnCheckedChangeListener { _, checked ->
            Prefs.setCenterZone(this, checked)
            engine?.tracker?.centerZoneOnly = checked
            overlay.showCenterZone = checked
            overlay.invalidate()
        }

        btnPreview.setOnClickListener {
            if (camera != null) stopPreview() else startPreview()
        }
        btnBackground.setOnClickListener {
            if (DetectionService.isRunning) {
                DetectionService.stop(this)
                status.text = "Surveillance arrière-plan arrêtée"
            } else {
                stopPreview()
                DetectionService.start(this)
                Toast.makeText(this, "Surveillance lancée : vous pouvez revenir à CarPlay / Android Auto", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            }
            refreshButtons()
        }
        findViewById<Button>(R.id.btnBeep).setOnClickListener {
            (engine ?: tryCreateEngine())?.let { e ->
                e.testBeep()
                if (camera == null && e !== engine) {
                    preview.postDelayed({ e.close() }, 1500)
                }
            }
        }

        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshButtons()
    }

    override fun onPause() {
        super.onPause()
        stopPreview()
    }

    private fun updateSensitivityLabel(p: Int) {
        sensitivityLabel.text = "Sensibilité : $p %  (plus haut = alerte plus tôt)"
    }

    private fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) loadCameras() else ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            loadCameras()
        } else {
            status.text = "Permission caméra refusée : l'appli ne peut pas fonctionner"
        }
    }

    private fun loadCameras() {
        cameras = CameraSource.listCameras(this)
        if (cameras.isEmpty()) {
            status.text = "Aucune caméra visible par Android sur cet appareil"
            return
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameras.map { it.second })
        val saved = Prefs.cameraId(this)
        val idx = cameras.indexOfFirst { it.first == saved }
        if (idx >= 0) spinner.setSelection(idx)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val newId = cameras[pos].first
                if (newId != Prefs.cameraId(this@MainActivity)) {
                    Prefs.setCameraId(this@MainActivity, newId)
                    if (camera != null) { stopPreview(); startPreview() }
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        if (saved == null) Prefs.setCameraId(this, cameras[0].first)
        status.text = "${cameras.size} caméra(s) trouvée(s). Choisissez celle de derrière puis « Tester »."
    }

    private fun tryCreateEngine(): DetectionEngine? = try {
        DetectionEngine(this) { result, level ->
            runOnUiThread {
                overlay.update(result, level)
                val t = engine?.tracker
                val pct = ((t?.smoothedArea ?: 0f) * 100)
                status.text = when (level) {
                    AlertLevel.NONE -> "RAS — ${result.vehicles.size} véhicule(s) vu(s), taille %.1f %%".format(pct)
                    AlertLevel.ATTENTION -> "⚠ Véhicule derrière — taille %.1f %%".format(pct)
                    AlertLevel.DANGER -> "⛔ VÉHICULE TRÈS PROCHE — taille %.1f %%".format(pct)
                }
            }
        }
    } catch (e: Exception) {
        status.text = "Erreur de chargement du modèle : ${e.message}"
        null
    }

    private fun startPreview() {
        if (DetectionService.isRunning) {
            Toast.makeText(this, "Arrêtez d'abord la surveillance en arrière-plan", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            askPermissions(); return
        }
        val id = Prefs.cameraId(this) ?: return
        if (preview.isAvailable) {
            openCamera(id, preview.surfaceTexture!!)
        } else {
            preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera(id, st) }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    private fun openCamera(id: String, st: SurfaceTexture) {
        if (camera != null) return
        val e = engine ?: tryCreateEngine() ?: return
        engine = e
        try {
            camera = CameraSource(this, id, st, e::process) { msg -> runOnUiThread { status.text = msg } }
                .also { it.start() }
        } catch (ex: Exception) {
            status.text = "Impossible d'ouvrir la caméra $id : ${ex.message}"
            camera = null
        }
        refreshButtons()
    }

    private fun stopPreview() {
        camera?.stop(); camera = null
        engine?.close(); engine = null
        overlay.update(null, AlertLevel.NONE)
        refreshButtons()
    }

    private fun refreshButtons() {
        btnPreview.text = if (camera != null) "Arrêter l'aperçu" else "Tester avec aperçu"
        btnBackground.text = if (DetectionService.isRunning) "Arrêter la surveillance" else "Lancer en arrière-plan"
    }
}
