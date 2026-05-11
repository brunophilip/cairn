package com.bpi.cairn

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bpi.cairn.gpx.GpxParser
import com.bpi.cairn.gpx.GpxTrack
import com.bpi.cairn.ui.theme.ElevationProfileDialog
import java.io.File
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), SensorEventListener {

    // Views
    private lateinit var btnImportGpx: LinearLayout
    private lateinit var btnSelectTrace: LinearLayout
    private lateinit var btnElevation: LinearLayout
    private lateinit var btnCenter: LinearLayout
    private lateinit var screenOverlay: View
    private lateinit var btnFollow: LinearLayout
    private lateinit var btnFollowIcon: ImageView
    private lateinit var btnRecord: LinearLayout
    private lateinit var btnRecordIcon: ImageView
    private lateinit var btnRecordLabel: TextView
    private var isRecording = false
    private val recordedPoints = mutableListOf<android.location.Location>()

    private var isFollowing = false

    private lateinit var mapFragment: MapFragment
    private var currentTrack: GpxTrack? = null

    // Capteur de proximité
    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var isScreenOff = false

    // ─── GPX file picker ──────────────────────────────────────────────────────
    private val gpxPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { importGpxFile(it) }
    }

    // ─── Track list picker ────────────────────────────────────────────────────
    private val trackPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra("selected_file") ?: return@registerForActivityResult
            loadTrack(File(path))
        }
    }

    // ─── Permission launcher ──────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) initMap()
        else Toast.makeText(this, "Permissions GPS requises", Toast.LENGTH_LONG).show()
    }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == GpsService.ACTION_LOCATION_UPDATE) {
                val location = intent.getParcelableExtra<Location>(GpsService.EXTRA_LOCATION)
                location?.let {
                    recordedPoints.add(it)

                    // On vérifie que mapFragment est bien initialisé avant l'appel
                    if (::mapFragment.isInitialized) {
                        mapFragment.addPointToRecordedTrack(it)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // On écoute les mises à jour du service GPS
        val filter = android.content.IntentFilter(GpsService.ACTION_LOCATION_UPDATE)
        registerReceiver(locationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        // On arrête d'écouter pour économiser la batterie
        unregisterReceiver(locationReceiver)
    }

    // ──────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Adapter le padding au bas selon la barre de navigation du système
        val bottomBar = findViewById<LinearLayout>(R.id.bottomBar)
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                navBar.bottom + 12  // 12dp de marge en plus
            )
            insets
        }

        findViewById<View>(R.id.btnInfo).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("À propos de Cairn")
                .setMessage("Créateur : Bruno PHILIP\nApplication de suivi rando & VTT.\n\nContactez-moi pour toute suggestion !")
                .setPositiveButton("Envoyer un mail") { _, _ ->
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:brunophi@gmail.com")
                        putExtra(Intent.EXTRA_SUBJECT, "Feedback Cairn App")
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Fermer", null)
                .show()
        }

        // Garder l'écran allumé pendant la navigation
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Bind views
        btnImportGpx   = findViewById(R.id.btnImportGpx)
        btnSelectTrace = findViewById(R.id.btnSelectTrace)
        btnElevation   = findViewById(R.id.btnElevation)
        btnCenter      = findViewById(R.id.btnCenter)
        btnFollow      = findViewById(R.id.btnFollow)
        btnFollowIcon  = findViewById(R.id.btnFollowIcon)
        btnRecord = findViewById(R.id.btnRecord)
        btnRecordIcon = findViewById(R.id.btnRecordIcon)
        btnRecordLabel = findViewById(R.id.btnRecordLabel)
        screenOverlay  = findViewById(R.id.screenOverlay)

        setupMapFragment()
        setupButtons()
        setupProximitySensor()
        checkPermissions()
    }

    // ─── Capteur de proximité ─────────────────────────────────────────────────
    private fun setupProximitySensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (proximitySensor == null) {
            Toast.makeText(this, "Capteur de proximité non disponible", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PROXIMITY) return
        val distance = event.values[0]
        val maxRange = proximitySensor?.maximumRange ?: 5f
        if (distance < maxRange) {
            if (!isScreenOff) screenOff()
        } else {
            if (isScreenOff) screenOn()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun screenOff() {
        isScreenOff = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screenOverlay.alpha = 1f
        screenOverlay.isClickable = true
        screenOverlay.setOnClickListener { screenOn() }
    }

    private fun screenOn() {
        isScreenOff = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screenOverlay.alpha = 0f
        screenOverlay.isClickable = false
        screenOverlay.setOnClickListener(null)
    }

    // ─── Lifecycle du capteur ─────────────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    // ─── Map ──────────────────────────────────────────────────────────────────
    private fun setupMapFragment() {
        mapFragment = MapFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.mapContainer, mapFragment)
            .commitNow()
    }

    // ─── Buttons ──────────────────────────────────────────────────────────────
    private fun setupButtons() {
        btnImportGpx.setOnClickListener {
            gpxPickerLauncher.launch("*/*")
        }

        btnSelectTrace.setOnClickListener {
            val intent = Intent(this, TrackListActivity::class.java)
            trackPickerLauncher.launch(intent)
        }

        btnElevation.setOnClickListener {
            currentTrack?.let { track ->
                ElevationProfileDialog(this, track).show()
            } ?: Toast.makeText(this, "Aucune trace chargée", Toast.LENGTH_SHORT).show()
        }

        btnCenter.setOnClickListener {
            mapFragment.centerOnUser()
        }

        btnFollow.setOnClickListener {
            isFollowing = !isFollowing
            btnFollowIcon.setImageResource(if (isFollowing) R.drawable.ic_btn_stop else R.drawable.ic_btn_play)

            // On informe le fragment du changement d'état du bouton suivi
            mapFragment.updateCameraMode(isFollowing, isRecording)
        }

        btnRecord.setOnClickListener {
            isRecording = !isRecording

            // On met à jour le mode caméra immédiatement :
            // Si isRecording est passé à true, la carte se verrouille.
            mapFragment.updateCameraMode(isFollowing, isRecording)

            if (isRecording) {
                recordedPoints.clear()
                btnRecordIcon.setImageResource(R.drawable.ic_btn_stop)
                btnRecordLabel.text = "Stop"

                mapFragment.startRecording { location ->
                    recordedPoints.add(location)
                }
                Toast.makeText(this, "Enregistrement démarré", Toast.LENGTH_SHORT).show()
            } else {
                btnRecordIcon.setImageResource(R.drawable.ic_btn_record)
                btnRecordLabel.text = "Enreg."

                // Arrête le service GPS
                val intent = Intent(this, GpsService::class.java).apply {
                    action = GpsService.ACTION_STOP
                }
                startService(intent)
                mapFragment.stopRecording()

                // Demander le nom du fichier
                val input = android.widget.EditText(this).apply {
                    hint = "Ma rando du dimanche"
                }

                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Enregistrer la trace")
                    .setMessage("Donnez un nom à votre parcours :")
                    .setView(input)
                    .setCancelable(false) // Évite de fermer sans enregistrer par erreur
                    .setPositiveButton("Enregistrer") { _, _ ->
                        val name = input.text.toString().ifBlank { "Trace_${System.currentTimeMillis()}" }
                        saveRecordedTrack(name)
                    }
                    .setNegativeButton("Ignorer") { _, _ ->
                        recordedPoints.clear()
                    }
                    .show()
            }
        }
    }

    // ─── Import GPX ───────────────────────────────────────────────────────────
    private fun importGpxFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val fileName = getFileName(uri) ?: "trace_${System.currentTimeMillis()}.gpx"
            val destFile = File(getWorkingDir(), fileName)
            inputStream.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(this, "Fichier importé : $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur import : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    // ─── Load track ───────────────────────────────────────────────────────────
    private fun loadTrack(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val track = GpxParser.parse(file)

                withContext(Dispatchers.Main) {
                    if (::mapFragment.isInitialized && mapFragment.isAdded) {
                        currentTrack = track

                        // ✅ On utilise la passerelle au lieu d'accéder directement à mapView
                        mapFragment.postOnMap {
                            mapFragment.setTrack(track)
                            btnElevation.alpha = 1f
                            Toast.makeText(this@MainActivity, "Trace chargée : ${track.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Erreur : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveRecordedTrack(customName: String) {
        if (recordedPoints.isEmpty()) {
            Toast.makeText(this, "Aucun point enregistré", Toast.LENGTH_SHORT).show()
            return
        }

        // Utilisation directe du scope grâce aux imports corrigés
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cleanName = customName.replace(" ", "_").replace("/", "-")
                val fileName = "$cleanName.gpx"
                val file = File(getWorkingDir(), fileName)

                val sb = StringBuilder()
                sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
                sb.appendLine("""<gpx version="1.1" creator="Cairn" xmlns="http://www.topografix.com/GPX/1/1">""")
                sb.appendLine("""  <trk>""")
                sb.appendLine("""    <name>$customName</name>""")
                sb.appendLine("""    <trkseg>""")

                // On crée une copie de la liste pour éviter les erreurs si un point s'ajoute pendant l'écriture
                val pointsCopy = synchronized(recordedPoints) { recordedPoints.toList() }

                pointsCopy.forEach { loc ->
                    sb.appendLine("""      <trkpt lat="${loc.latitude}" lon="${loc.longitude}">""")
                    sb.appendLine("""        <ele>${loc.altitude}</ele>""")
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    sb.appendLine("""        <time>${sdf.format(java.util.Date(loc.time))}</time>""")
                    sb.appendLine("""      </trkpt>""")
                }

                sb.appendLine("""    </trkseg>""")
                sb.appendLine("""  </trk>""")
                sb.appendLine("""</gpx>""")

                file.writeText(sb.toString())

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Trace sauvegardée : $fileName", Toast.LENGTH_LONG).show()
                    recordedPoints.clear()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Erreur : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ─── Permissions ──────────────────────────────────────────────────────────
    private fun checkPermissions() {
        val needed = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) initMap()
        else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun initMap() {
        // Attendre que le fragment soit attaché avant d'activer le GPS
        supportFragmentManager.executePendingTransactions()
        mapFragment.startLocationUpdates()
    }

    // ─── Working directory ────────────────────────────────────────────────────
    fun getWorkingDir(): File {
        val dir = File(filesDir, "traces")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
