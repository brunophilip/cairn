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
    private lateinit var btnRecord: LinearLayout
    private lateinit var btnRecordIcon: ImageView
    private lateinit var btnRecordLabel: TextView
    private var isRecording = false
    private val recordedPoints = mutableListOf<Location>()

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

    // Gestion du compte à rebours de 30 secondes pour le recentrage
    private var autoCenterResetJob: kotlinx.coroutines.Job? = null

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
                    if (::mapFragment.isInitialized) {
                        mapFragment.addPointToRecordedTrack(it)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter(GpsService.ACTION_LOCATION_UPDATE)
        registerReceiver(locationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(locationReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return

            // On arrête seulement l'enregistrement, la notion de "following" n'existe plus
            forceStopRecording()

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val gpxString = inputStream.bufferedReader().use { it.readText() }
                        val track = gpxParser.parse(gpxString)

                        withContext(Dispatchers.Main) {
                            if (track != null && ::mapFragment.isInitialized) {

                                // ✅ LE SECRET EST ICI : On s'assure que la carte est prête avant de dessiner !
                                mapFragment.postOnMap {
                                    mapFragment.setTrack(track)
                                    mapFragment.zoomToTrack(track) // On cadre la trace

                                    currentTrack = track
                                    btnElevation.alpha = 1f
                                    saveTrackToInternalList(track, gpxString)

                                    // On coupe l'auto-centrage et on lance la minuterie de 30s
                                    mapFragment.isAutoCenterActive = false
                                    startBlinkingCenterButton()
                                    startAutoCenterResetTimer()

                                    Toast.makeText(this@MainActivity, "Trace ouverte : ${track.name}", Toast.LENGTH_SHORT).show()
                                }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val lp = window.attributes
        lp.screenBrightness = -1.0f
        window.attributes = lp
        isDimmed = false

        setContentView(R.layout.activity_main)

        val bottomBar = findViewById<LinearLayout>(R.id.bottomBar)
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, navBar.bottom + 12)
            insets
        }

        findViewById<View>(R.id.btnInfo).setOnClickListener {
            val versionName = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: Exception) {
                "Inconnue"
            }

            // 1. Rédaction du mode d'emploi (mis à jour avec Cartes IGN et icône Play)
            val docContent = """
                <b>🚵‍♂️ 1. Navigation intelligente</b><br>
                • <b>Trace :</b> Chargez un parcours GPX depuis votre appareil.<br>
                • <b>Suivi auto :</b> La carte est centrée sur votre position par défaut et s'oriente dans le sens de la marche de manière automatique.<br>
                • <b>Exploration :</b> Touchez ou glissez la carte pour observer les environs. Le recentrage automatique se coupe et le bouton <i>Centrer</i> se met à clignoter.<br>
                • <b>Retour auto :</b> Sans action de votre part, le recentrage automatique se réactive tout seul après 30 secondes d'inactivité, ou d'un simple appui sur le bouton clignotant !<br><br>
                
                <b>🔴 2. Enregistrement GPS</b><br>
                • Appuyez sur <b>Enreg.</b> pour enregistrer votre trace en direct (dessinée en rouge sur l'écran).<br>
                • <b>Mode Poche :</b> Grâce au service d'arrière-plan, l'enregistrement continue sans coupure, même si vous éteignez l'écran ou glissez le téléphone dans votre poche.<br><br>
                
                <b>🗺️ 3. Fonds de carte IGN & Relief</b><br>
                • Le bouton <b>Carte</b> permet de basculer instantanément entre les Cartes IGN Topo (SCAN 25), les photos Satellites IGN, OpenTopoMap (relief) et OSM Standard.<br>
                • <b>Hors-ligne :</b> Les zones affichées à l'écran sont automatiquement mémorisées dans le cache pour rester accessibles sans réseau en pleine nature.<br><br>
                
                <b>⚡ 4. Ergonomie & Batterie</b><br>
                • <b>Vitesse géante :</b> Appuyez sur le bloc vitesse/distance en bas à gauche pour afficher (ou masquer) votre vitesse en grand format.<br>
                • <b>Luminosité Éco :</b> Touchez la zone centrale de la carte pour basculer rapidement entre la luminosité normale et le mode économie d'énergie.<br>
                • <b>Capteur de proximité :</b> L'écran s'éteint automatiquement si le capteur supérieur est recouvert (téléphone retourné ou dans une poche).<br><br>
                <small><i>Application conçue par Bruno PHILIP — v$versionName</i></small>
            """.trimIndent()

            // 2. Création d'un conteneur déroulant (ScrollView + TextView)
            val textView = TextView(this).apply {
                text = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    android.text.Html.fromHtml(docContent, android.text.Html.FROM_HTML_MODE_LEGACY)
                } else {
                    @Suppress("DEPRECATION")
                    android.text.Html.fromHtml(docContent)
                }
                setPadding(60, 40, 60, 20)
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#E0E0E0")) // Texte clair
            }

            val scrollView = android.widget.ScrollView(this).apply {
                addView(textView)
            }

            // 3. Affichage de la boîte de dialogue
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("📖 Guide d'utilisation Cairn")
                .setView(scrollView)
                .setPositiveButton("Fermer", null)
                .setNeutralButton("✉️ Contact / Suggestion") { _, _ ->
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:brunophi@gmail.com")
                        putExtra(Intent.EXTRA_SUBJECT, "Feedback Cairn App (v$versionName)")
                    }
                    startActivity(intent)
                }
                .show()
        }

        txtSpeed = findViewById(R.id.txtSpeed)
        txtDistance = findViewById(R.id.txtDistance)
        txtLargeSpeed = findViewById(R.id.txtLargeSpeed)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        btnImportGpx   = findViewById(R.id.btnImportGpx)
        btnSelectTrace = findViewById(R.id.btnSelectTrace)
        btnElevation   = findViewById(R.id.btnElevation)
        btnCenter      = findViewById(R.id.btnCenter)
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
            val cleanName = track.name.replace(" ", "_").replace("/", "-")
            var fileName = "$cleanName.gpx"
            var destFile = File(getWorkingDir(), fileName)

            var counter = 1
            while (destFile.exists()) {
                fileName = "${cleanName}_$counter.gpx"
                destFile = File(getWorkingDir(), fileName)
                counter++
            }
            destFile.writeText(gpxContent)
        } catch (e: Exception) {
            android.util.Log.e("Cairn", "Erreur sauvegarde interne : ${e.message}")
        }
    }

    private fun setupProximitySensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PROXIMITY) return
        val distance = event.values[0]
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
            lp.screenBrightness = -1f
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
                        val speedRect = android.graphics.Rect()
                        txtSpeed.getGlobalVisibleRect(speedRect)
                        val distRect = android.graphics.Rect()
                        txtDistance.getGlobalVisibleRect(distRect)

                        speedRect.union(distRect)
                        speedRect.inset(-50, -50)

                        if (speedRect.contains(ev.x.toInt(), ev.y.toInt())) {

                            isLargeSpeedVisible = !isLargeSpeedVisible
                            txtLargeSpeed.visibility = if (isLargeSpeedVisible) View.VISIBLE else View.GONE
                            if (isLargeSpeedVisible) {
                                txtLargeSpeed.text = txtSpeed.text.toString().replace(" km/h", "")
                            }

                            return super.dispatchTouchEvent(ev)
                        }

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
        screenOverlay.visibility = View.VISIBLE
        screenOverlay.alpha = 1f
        screenOverlay.isClickable = true
        screenOverlay.setOnClickListener { screenOn() }
    }

    private fun screenOn() {
        isScreenOff = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screenOverlay.visibility = View.GONE
        screenOverlay.isClickable = false
        screenOverlay.setOnClickListener(null)
    }

    override fun onResume() {
        super.onResume()
        screenOn()
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

    // ✅ 1. La vitesse matérielle en temps réel (mise à jour en continu)
    fun updateRealTimeSpeed(speedMs: Float) {
        runOnUiThread {
            // Conversion des m/s en km/h
            val speedKmH = speedMs * 3.6f
            val speedValueStr = String.format(java.util.Locale.FRANCE, "%.1f", speedKmH)

            txtSpeed.text = "$speedValueStr km/h"
            if (isLargeSpeedVisible) {
                txtLargeSpeed.text = speedValueStr
            }
        }
    }

    // ✅ 2. La distance (mise à jour uniquement pendant un enregistrement)
    fun updateDistanceUI(distanceKm: Float) {
        runOnUiThread {
            txtDistance.text = String.format(java.util.Locale.FRANCE, "%.2f km", distanceKm)
        }
    }

    private fun setupMapFragment() {
        mapFragment = MapFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.mapContainer, mapFragment)
            .commitNow()
    }

    private fun setupButtons() {
        btnImportGpx.setOnClickListener { gpxPickerLauncher.launch("*/*") }

        btnSelectTrace.setOnClickListener {
            val intent = Intent(this, TrackListActivity::class.java)
            trackPickerLauncher.launch(intent)
        }

        btnElevation.setOnClickListener {
            currentTrack?.let { track ->
                // On vérifie si on est actuellement sur la trace
                val indexOnTrack = if (::mapFragment.isInitialized && mapFragment.isUserOnTrack) {
                    mapFragment.currentTrackProgressIndex
                } else {
                    null // null signifie "Ne pas dessiner le point rouge"
                }

                // On envoie l'index à la boîte de dialogue
                ElevationProfileDialog(this, track, indexOnTrack).show()

            } ?: Toast.makeText(this, "Aucune trace chargée", Toast.LENGTH_SHORT).show()
        }

        btnCenter.setOnClickListener {
            stopBlinkingCenterButton()
            if (::mapFragment.isInitialized) {
                mapFragment.isAutoCenterActive = true
                mapFragment.centerOnUser()
            }
        }

        val btnLayers = findViewById<LinearLayout>(R.id.btnLayers)
        btnLayers.setOnClickListener {
            // ✅ Les 4 options proposées à l'utilisateur, avec l'IGN en tête !
            val mapStyles = arrayOf(
                "Cartes IGN Topo (SCAN 25 classique)",
                "IGN Photos Aériennes (Vue Satellite)",
                "OpenTopoMap (Relief & Courbes de niveau)",
                "OpenStreetMap (Standard & Rues)"
            )

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Choisir le fond de carte")
                .setItems(mapStyles) { _, which ->
                    when (which) {
                        0 -> {
                            mapFragment.setMapStyle("IGN_Cartes")
                            Toast.makeText(this, "Cartes IGN Topo activées", Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            mapFragment.setMapStyle("IGN_Photo")
                            Toast.makeText(this, "Fond IGN Satellite activé", Toast.LENGTH_SHORT).show()
                        }
                        2 -> {
                            mapFragment.setMapStyle("OpenTopo")
                            Toast.makeText(this, "Mode Relief activé", Toast.LENGTH_SHORT).show()
                        }
                        3 -> {
                            mapFragment.setMapStyle("OSM_Standard")
                            Toast.makeText(this, "Mode Standard activé", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        btnRecord.setOnClickListener {
            isRecording = !isRecording
            mapFragment.updateCameraMode(isRecording)

            if (isRecording) {
                mapFragment.resetStats()

                recordedPoints.clear()
                btnRecordIcon.setImageResource(R.drawable.ic_btn_stop)
                btnRecordLabel.text = "Stop"

                val intent = Intent(this, GpsService::class.java).apply {
                    action = GpsService.ACTION_START
                }
                startService(intent)

                mapFragment.startRecording { location ->
                    recordedPoints.add(location)
                    mapFragment.addPointToRecordedTrack(location)
                }
                Toast.makeText(this, "Enregistrement démarré", Toast.LENGTH_SHORT).show()
            } else {
                btnRecordIcon.setImageResource(R.drawable.ic_btn_record)
                btnRecordLabel.text = "Enreg."

                val intent = Intent(this, GpsService::class.java).apply { action = GpsService.ACTION_STOP }
                startService(intent)
                mapFragment.stopRecording()

                stopBlinkingCenterButton()
                if (::mapFragment.isInitialized) {
                    mapFragment.isAutoCenterActive = true
                }

                // ✅ L'EditText EST CRÉÉ ICI, À L'INTÉRIEUR DU CLIC "STOP" !
                val sdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.FRANCE)
                val defaultName = "Rando-${sdf.format(java.util.Date())}"

                val input = android.widget.EditText(this).apply {
                    setText(defaultName)
                    setSelection(defaultName.length)
                }

                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Enregistrer la trace")
                    .setMessage("Donnez un nom à votre parcours :")
                    .setView(input)
                    .setCancelable(false)
                    .setPositiveButton("Enregistrer") { _, _ ->
                        val name = input.text.toString().ifBlank { defaultName }
                        saveRecordedTrack(name)
                    }
                    .setNegativeButton("Ignorer") { _, _ -> recordedPoints.clear() }
                    .show()
            }

            isLargeSpeedVisible = false
            txtLargeSpeed.visibility = View.GONE
        }
    }

    // ✅ FONCTIONS D'ANIMATION (Un seul exemplaire !)
    private fun startBlinkingCenterButton() {
        val blinkAnimation = android.view.animation.AlphaAnimation(1f, 0.2f).apply {
            duration = 500
            interpolator = android.view.animation.LinearInterpolator()
            repeatCount = android.view.animation.Animation.INFINITE
            repeatMode = android.view.animation.Animation.REVERSE
        }
        btnCenter.startAnimation(blinkAnimation)
    }

    // ✅ DÉMARRE OU RELANCE LE COMPTE À REBOURS DE 30 SECONDES
    private fun startAutoCenterResetTimer() {
        // 1. On annule le minuteur précédent s'il était déjà en cours
        autoCenterResetJob?.cancel()

        // 2. On lance un nouveau décompte de 30 secondes
        autoCenterResetJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(30_000L) // 30 000 millisecondes = 30 secondes

            // 3. Si les 30 secondes se sont écoulées sans être annulées :
            stopBlinkingCenterButton()
            if (::mapFragment.isInitialized) {
                mapFragment.isAutoCenterActive = true
                mapFragment.centerOnUser() // On ramène la caméra sur le VTT !
            }
            Toast.makeText(this@MainActivity, "Recentrage automatique réactivé", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopBlinkingCenterButton() {
        btnCenter.clearAnimation()
        // ✅ SÉCURITÉ : Dès qu'on arrête de clignoter (clic manuel, arrêt du suivi, etc.),
        // on coupe immédiatement le minuteur de 30 secondes !
        autoCenterResetJob?.cancel()
        autoCenterResetJob = null
    }

    // ✅ GESTION DE L'INTERACTION CARTE (Avec minuterie de 30s)
    fun handleMapInteraction() {
        if (isUserTouchingScreen) {
            if (::mapFragment.isInitialized) {
                // Si le mode auto était encore actif, on le coupe et on fait clignoter
                if (mapFragment.isAutoCenterActive) {
                    mapFragment.isAutoCenterActive = false
                    runOnUiThread { startBlinkingCenterButton() }
                }

                // ⏱️ DANS TOUS LES CAS : On lance ou on relance le chrono de 30 secondes
                // à chaque fois que le doigt touche ou déplace la carte !
                startAutoCenterResetTimer()
            }
        }
    }

    fun forceAutoBrightness() {
        if (isDimmed && isUserTouchingScreen) {
            val lp = window.attributes
            lp.screenBrightness = -1f
            window.attributes = lp
            isDimmed = false
        }
    }

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

    private fun forceStopRecording() {
        if (isRecording) {
            isRecording = false
            btnRecordIcon.setImageResource(R.drawable.ic_btn_record)
            btnRecordLabel.text = "Enreg."

            val intent = Intent(this, GpsService::class.java).apply { action = GpsService.ACTION_STOP }
            startService(intent)

            if (::mapFragment.isInitialized) {
                mapFragment.stopRecording()
                mapFragment.clearRecordedTrack()
                stopBlinkingCenterButton()
                mapFragment.isAutoCenterActive = true
                mapFragment.updateCameraMode(isRecording)
            }

            // ✅ CRÉATION D'UN NOUVEL EDITTEXT POUR L'ARRÊT FORCÉ
            val sdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.FRANCE)
            val defaultName = "Rando-${sdf.format(java.util.Date())}"

            val input = android.widget.EditText(this).apply {
                setText(defaultName)
                setSelection(defaultName.length)
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Enregistrer la trace en cours")
                .setMessage("Une nouvelle trace a été chargée. Voulez-vous sauvegarder celle que vous étiez en train d'enregistrer ?")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Enregistrer") { _, _ ->
                    val name = input.text.toString().ifBlank { defaultName }
                    saveRecordedTrack(name)
                }
                .setNegativeButton("Ignorer") { _, _ -> recordedPoints.clear() }
                .show()
        }

        isLargeSpeedVisible = false
        txtLargeSpeed.visibility = View.GONE
        stopBlinkingCenterButton()
        if (::mapFragment.isInitialized) mapFragment.isAutoCenterActive = true
    }

    private fun loadTrack(file: File) {

        forceStopRecording()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val track = GpxParser.parse(file)
                withContext(Dispatchers.Main) {
                    if (::mapFragment.isInitialized && mapFragment.isAdded) {
                        currentTrack = track
                        mapFragment.postOnMap {
                            mapFragment.setTrack(track)
                            mapFragment.zoomToTrack(track)

                            mapFragment.isAutoCenterActive = false
                            runOnUiThread { startBlinkingCenterButton() }
                            startAutoCenterResetTimer()

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

    private fun checkPermissions() {
        val needed = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) initMap()
        else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun initMap() {
        supportFragmentManager.executePendingTransactions()
        mapFragment.startLocationUpdates()
    }

    fun getWorkingDir(): File {
        val dir = File(filesDir, "traces")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}