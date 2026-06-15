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

@Suppress("DEPRECATION")
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
    private val recordedPoints = mutableListOf<Location>()

    private var isFollowing = false

    private lateinit var mapFragment: MapFragment
    private val gpxParser = GpxParser
    private var currentTrack: GpxTrack? = null

    // Capteur de proximité
    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var isScreenOff = false
    private var isDimmed = false

    private lateinit var txtSpeed: TextView
    private lateinit var txtDistance: TextView

    private var isUserTouchingScreen = false

    private lateinit var txtLargeSpeed: TextView
    private var isLargeSpeedVisible = false

    companion object {
        const val BRIGHTNESS_ECO = 0.2f
    }

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Important pour que handleIntent récupère les nouvelles données
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return

            // ✅ On arrête les modes en cours avant de charger la nouvelle trace
            forceStopFollowing()
            forceStopRecording()

            // On lance cela dans une coroutine car la lecture de fichier peut être lente
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val gpxString = inputStream.bufferedReader().use { it.readText() }
                        val track = gpxParser.parse(gpxString)

                        withContext(Dispatchers.Main) {
                            if (track != null && ::mapFragment.isInitialized) {
                                // 1. Affichage immédiat
                                mapFragment.setTrack(track)
                                currentTrack = track
                                btnElevation.alpha = 1f

                                // 2. Sauvegarde dans le dossier "traces" pour le retrouver plus tard
                                saveTrackToInternalList(track, gpxString)

                                Toast.makeText(this@MainActivity, "Importation réussie : ${track.name}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Erreur lors de l'ouverture : ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val lp = window.attributes
        lp.screenBrightness = -1.0f
        window.attributes = lp
        isDimmed = false

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
            // 1. Récupération dynamique du numéro de version depuis la configuration
            val versionName = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: Exception) {
                "Inconnue"
            }

            // 2. Affichage de la boîte de dialogue avec la version
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("À propos de Cairn")
                .setMessage("Créateur : Bruno PHILIP\nVersion : $versionName\n\nApplication de suivi rando & VTT.\n\nContactez-moi pour toute suggestion !")
                .setPositiveButton("Envoyer un mail") { _, _ ->
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:brunophi@gmail.com")
                        // Optionnel : ajouter la version dans l'objet du mail
                        putExtra(Intent.EXTRA_SUBJECT, "Feedback Cairn App (v$versionName)")
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Fermer", null)
                .show()
        }

        txtSpeed = findViewById(R.id.txtSpeed)
        txtDistance = findViewById(R.id.txtDistance)

        txtLargeSpeed = findViewById(R.id.txtLargeSpeed)

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

        handleIntent(intent)

        screenOn()
    }

    private fun saveTrackToInternalList(track: GpxTrack, gpxContent: String) {
        try {
            // 1. On nettoie le nom (remplace les espaces et caractères problématiques)
            val cleanName = track.name.replace(" ", "_").replace("/", "-")
            var fileName = "$cleanName.gpx"
            var destFile = File(getWorkingDir(), fileName)

            // 2. Sécurité : si le fichier existe déjà, on ajoute juste _1, _2, etc.
            var counter = 1
            while (destFile.exists()) {
                fileName = "${cleanName}_$counter.gpx"
                destFile = File(getWorkingDir(), fileName)
                counter++
            }

            // 3. On écrit le fichier
            destFile.writeText(gpxContent)

        } catch (e: Exception) {
            android.util.Log.e("Cairn", "Erreur sauvegarde interne : ${e.message}")
        }
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

        // ✅ CORRECTION : Ne s'active que si l'objet est collé à l'écran (< 3 cm)
        // Ignore les valeurs fantaisistes du "maxRange" du téléphone
        val isNear = distance < 3f

        if (isNear) {
            if (!isScreenOff) screenOff()
        } else {
            if (isScreenOff) screenOn()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun toggleBrightness() {
        val lp = window.attributes
        if (isDimmed) {
            lp.screenBrightness = -1f  // ← restitue le réglage système
            isDimmed = false
        } else {
            lp.screenBrightness = BRIGHTNESS_ECO
            isDimmed = true
        }
        window.attributes = lp
    }

    private var touchStartTime: Long = 0
    private var isMultiTouch = false

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        try {
            if (isScreenOff) return super.dispatchTouchEvent(ev)

            when (ev?.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isUserTouchingScreen = true
                    touchStartTime = System.currentTimeMillis()
                    isMultiTouch = false
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    isUserTouchingScreen = true
                    isMultiTouch = true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    isUserTouchingScreen = false

                    val touchDuration = System.currentTimeMillis() - touchStartTime

                    if (!isMultiTouch && touchDuration < 250) {

                        // ✅ 1. DÉTECTION DU CLIC SUR LA ZONE STATISTIQUES (Vitesse / Distance)
                        val speedRect = android.graphics.Rect()
                        txtSpeed.getGlobalVisibleRect(speedRect)
                        val distRect = android.graphics.Rect()
                        txtDistance.getGlobalVisibleRect(distRect)

                        // On fusionne les deux rectangles pour créer la zone globale
                        speedRect.union(distRect)
                        // On agrandit la zone tactile pour que le clic soit facile (même en roulant)
                        speedRect.inset(-50, -50)

                        if (speedRect.contains(ev.x.toInt(), ev.y.toInt())) {
                            // L'utilisateur a touché la zone en bas à gauche !
                            if (isFollowing || isRecording) {
                                isLargeSpeedVisible = !isLargeSpeedVisible
                                txtLargeSpeed.visibility = if (isLargeSpeedVisible) View.VISIBLE else View.GONE

                                if (isLargeSpeedVisible) {
                                    txtLargeSpeed.text = txtSpeed.text.toString().replace(" km/h", "")
                                }
                            }
                            // 🛑 TRES IMPORTANT : On s'arrête ici. Le clic est absorbé.
                            // Cela empêche le code ci-dessous (la luminosité) de s'exécuter.
                            return super.dispatchTouchEvent(ev)
                        }

                        // ✅ 2. GESTION DE LA LUMINOSITÉ (Uniquement si on n'a pas cliqué sur les stats)
                        val marginSide = (70 * resources.displayMetrics.density).toInt()
                        val marginTop = (100 * resources.displayMetrics.density).toInt()
                        val bottomBar = findViewById<View>(R.id.bottomBar)
                        val bottomLimit = bottomBar?.top ?: resources.displayMetrics.heightPixels

                        val isInCentralZone = ev.x > marginSide &&
                                ev.x < (resources.displayMetrics.widthPixels - marginSide) &&
                                ev.y > marginTop &&
                                ev.y < bottomLimit

                        if (isInCentralZone) {
                            toggleBrightness()
                        }
                    }
                }
            }
            return super.dispatchTouchEvent(ev)
        } catch (e: Exception) {
            e.printStackTrace()
            return true
        }
    }

    private fun screenOff() {
        isScreenOff = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ✅ On réaffiche la vue noire
        screenOverlay.visibility = View.VISIBLE
        screenOverlay.alpha = 1f
        screenOverlay.isClickable = true
        screenOverlay.setOnClickListener { screenOn() }
    }

    private fun screenOn() {
        isScreenOff = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ✅ LA CLÉ EST ICI : GONE fait disparaître totalement la vue, elle ne peut plus rien bloquer
        screenOverlay.visibility = View.GONE
        screenOverlay.isClickable = false
        screenOverlay.setOnClickListener(null)
    }

    override fun onResume() {
        super.onResume()

        screenOn() // ✅ Force le déblocage de l'overlay

        isDimmed = false
        val lp = window.attributes
        lp.screenBrightness = -1f
        window.attributes = lp

        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()

        sensorManager.unregisterListener(this)
    }

    fun updateStatsUI(speed: Float, distanceKm: Float) {
        runOnUiThread {
            // On formate uniquement le chiffre
            val speedValueStr = String.format(java.util.Locale.FRANCE, "%.1f", speed)

            // Le petit texte garde l'unité
            txtSpeed.text = "$speedValueStr km/h"
            txtDistance.text = String.format(java.util.Locale.FRANCE, "%.2f km", distanceKm)

            // Le texte géant ne prend que le chiffre
            if (isLargeSpeedVisible) {
                txtLargeSpeed.text = speedValueStr
            }
        }
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

            if (isFollowing) {
                mapFragment.resetStats()
                updateStatsUI(0f, 0f) // On force l'affichage à 0 immédiatement
            }

            // On informe le fragment du changement d'état du bouton suivi
            mapFragment.updateCameraMode(isFollowing, isRecording)
        }

        btnRecord.setOnClickListener {
            isRecording = !isRecording

            // On met à jour le mode caméra immédiatement :
            // Si isRecording est passé à true, la carte se verrouille.
            mapFragment.updateCameraMode(isFollowing, isRecording)

            if (isRecording) {
                mapFragment.resetStats()
                updateStatsUI(0f, 0f)

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

    fun forceAutoBrightness() {
        // ✅ LA SÉCURITÉ : On ne réveille l'écran que si c'est VOTRE doigt qui bouge la carte
        if (isDimmed && isUserTouchingScreen) {
            val lp = window.attributes
            lp.screenBrightness = -1f
            window.attributes = lp
            isDimmed = false
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

    // ─── ARRÊT AUTOMATIQUE DES MODES ─────────────────────────────────────────

    private fun forceStopFollowing() {
        if (isFollowing) {
            isFollowing = false
            btnFollowIcon.setImageResource(R.drawable.ic_btn_play)
            if (::mapFragment.isInitialized) {
                mapFragment.updateCameraMode(isFollowing, isRecording)
            }
        }

        // ✅ Disparition de la vitesse géante si tout est arrêté
        if (!isFollowing && !isRecording) {
            isLargeSpeedVisible = false
            txtLargeSpeed.visibility = View.GONE
        }

    }

    private fun forceStopRecording() {
        if (isRecording) {
            isRecording = false
            btnRecordIcon.setImageResource(R.drawable.ic_btn_record)
            btnRecordLabel.text = "Enreg."

            // Arrêt du service GPS
            val intent = Intent(this, GpsService::class.java).apply {
                action = GpsService.ACTION_STOP
            }
            startService(intent)

            if (::mapFragment.isInitialized) {
                mapFragment.stopRecording()
                mapFragment.updateCameraMode(isFollowing, isRecording)
            }

            // Demander de sauvegarder la trace avant de passer à la nouvelle
            val input = android.widget.EditText(this).apply {
                hint = "Ma rando du dimanche"
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Enregistrer la trace en cours")
                .setMessage("Une nouvelle trace a été chargée. Voulez-vous sauvegarder celle que vous étiez en train d'enregistrer ?")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Enregistrer") { _, _ ->
                    val name = input.text.toString().ifBlank { "Trace_${System.currentTimeMillis()}" }
                    saveRecordedTrack(name)
                }
                .setNegativeButton("Ignorer") { _, _ ->
                    recordedPoints.clear()
                }
                .show()
        }

        // ✅ Disparition de la vitesse géante si tout est arrêté
        if (!isFollowing && !isRecording) {
            isLargeSpeedVisible = false
            txtLargeSpeed.visibility = View.GONE
        }
    }

    // ─── Load track ───────────────────────────────────────────────────────────
    private fun loadTrack(file: File) {
        // ✅ On arrête les modes en cours avant de charger la nouvelle trace
        forceStopFollowing()
        forceStopRecording()

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
