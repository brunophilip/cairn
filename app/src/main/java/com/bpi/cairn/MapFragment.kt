package com.bpi.cairn

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bpi.cairn.gpx.GpxTrack
import com.bpi.cairn.gpx.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MapFragment : Fragment() {

    private lateinit var mapView: MapView
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var gpsProvider: GpsMyLocationProvider

    private var currentTrack: GpxTrack? = null
    private var remainingPolyline: Polyline? = null
    private var completedPolyline: Polyline? = null
    private val directionArrows = mutableListOf<Marker>()

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private val recordedTrackPolyline = Polyline()
    private var lastLocation: Location? = null
    private var trackProgress = 0

    var currentTrackProgressIndex = 0 // ✅ Accessible depuis MainActivity
    var isUserOnTrack = false         // ✅ Accessible depuis MainActivity
    private var isFirstFix = true

    // États de contrôle
    private var shouldLockCamera = false // Est mis à true pendant l'enregistrement

    // Variables pour l'odomètre journalier
    private var dailyDistanceMeters = 0f
    private var lastDistanceDate = ""
    private var isDistanceInitialized = false
    private var lastLocationForDistance: Location? = null

    // Gestion du recentrage
    var isAutoCenterActive = true
    var onMapInteracted: (() -> Unit)? = null // Pour prévenir l'activité qu'on a touché la carte

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        // 1. Récupérer l'instance de configuration
        val conf = Configuration.getInstance()

        // 2. Charger les préférences existantes
        conf.load(
            requireContext(),
            requireContext().getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )

        // 3. Appliquer vos réglages sur l'objet 'conf'
        conf.userAgentValue = "com.bpi.cairn"

        // Note : cacheMapTileCount est un Short, pas un Long
        conf.cacheMapTileCount = 500.toShort()

        // Augmenter le cache sur le disque (1 Go)
        conf.tileFileSystemCacheMaxBytes = 1000L * 1024 * 1024
        mapView = MapView(requireContext()).apply {
            setTileSource(TileSourceFactory.OpenTopo)
            setMultiTouchControls(true)
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(46.603354, 1.888334))
        }

        // ✅ 1. ACTIVER LA ROTATION MANUELLE DE LA CARTE
        val rotationGestureOverlay = org.osmdroid.views.overlay.gestures.RotationGestureOverlay(mapView)
        rotationGestureOverlay.isEnabled = true
        mapView.overlays.add(rotationGestureOverlay)

        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                (activity as? MainActivity)?.forceAutoBrightness()
                // ✅ On informe l'activité qu'un déplacement a eu lieu
                (activity as? MainActivity)?.handleMapInteraction()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                (activity as? MainActivity)?.forceAutoBrightness()
                // ✅ On informe l'activité qu'un zoom a eu lieu
                (activity as? MainActivity)?.handleMapInteraction()
                return false
            }
        })

        setupLocationOverlay()
        setupCompassOverlay()
        setupRecordingPolyline()

        return mapView
    }

    private fun downloadTrackCache(track: GpxTrack) {
        val cacheManager = CacheManager(mapView)

        val points = track.points.map { pt ->
            GeoPoint(pt.lat.toDouble(), pt.lon.toDouble())
        }

        val minZoom = 14
        val maxZoom = 16

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                cacheManager.downloadAreaAsync(
                    requireContext(),
                    ArrayList(points),
                    minZoom,
                    maxZoom,
                    object : CacheManager.CacheManagerCallback {

                        override fun setPossibleTilesInArea(total: Int) {
                            android.util.Log.d("Cairn", "Nombre total de tuiles à analyser : $total")
                        }

                        override fun downloadStarted() {
                            activity?.runOnUiThread {
                                android.widget.Toast.makeText(requireContext(), "Début de la mise en cache...", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }

                        override fun onTaskComplete() {
                            activity?.runOnUiThread {
                                android.widget.Toast.makeText(requireContext(), "Carte mise en cache (Z14-16)", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }

                        override fun onTaskFailed(errors: Int) {
                            android.util.Log.e("Cairn", "Échec du cache : $errors erreurs")
                        }

                        override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {}
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadAndCheckDailyDistance() {
        if (isDistanceInitialized) return
        val prefs = requireContext().getSharedPreferences("CairnPrefs", Context.MODE_PRIVATE)
        lastDistanceDate = prefs.getString("last_distance_date", "") ?: ""
        dailyDistanceMeters = prefs.getFloat("daily_distance_meters", 0f)

        val sdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.FRANCE)
        val todayDate = sdf.format(java.util.Date())

        // Si la date sauvegardée n'est pas celle d'aujourd'hui, on réinitialise !
        if (lastDistanceDate != todayDate) {
            dailyDistanceMeters = 0f
            lastDistanceDate = todayDate
            prefs.edit()
                .putString("last_distance_date", lastDistanceDate)
                .putFloat("daily_distance_meters", 0f)
                .apply()
        }
        isDistanceInitialized = true
    }

    // ─── LOGIQUE DE CAMÉRA (CENTRALISÉE) ────────────────────────────────────

    fun updateCameraMode(recording: Boolean) {
        this.shouldLockCamera = recording

        // Si on active l'un des deux modes, on recadre immédiatement
        if (isAutoCenterActive || shouldLockCamera) {
            lastLocation?.let { updateMapPosition(it) }
        } else {
            // Si on coupe tout, on remet le Nord en haut
            mapView.mapOrientation = 0f
        }
    }

    private fun updateMapPosition(location: Location) {
        // La ligne magique qui empêche le GPS de forcer le recentrage
        if (!isAutoCenterActive) return

        // Sécurité : On utilise explicitement latitude et longitude
        val geoPoint = GeoPoint(location.latitude, location.longitude)
        val speed = location.speed
        (activity as? MainActivity)?.updateRealTimeSpeed(speed)

        if (speed >= 1.0f && location.hasBearing()) {
            // 🚵‍♂️ EN MOUVEMENT : On suit le cap GPS
            val targetOrientation = 360f - location.bearing
            mapView.controller.animateTo(geoPoint, mapView.zoomLevelDouble, 500L, targetOrientation)
        } else {
            // 🛑 À L'ARRÊT : On glisse juste, la boussole fera pivoter la carte
            mapView.controller.animateTo(geoPoint)
        }
    }

    fun centerOnUser() {
        lastLocation?.let {
            mapView.controller.animateTo(GeoPoint(it.latitude, it.longitude))
        }
    }

    fun resetStats() {

        lastLocationForDistance = null
    }

    // ─── ENREGISTREMENT ──────────────────────────────────────────────────────

    fun addPointToRecordedTrack(location: Location) {
        if (!isAdded || view == null) return
        val geoPoint = GeoPoint(location.latitude, location.longitude)
        recordedTrackPolyline.addPoint(geoPoint)
        mapView.invalidate()
    }

    private var recordingCallback: ((Location) -> Unit)? = null

    fun startRecording(callback: (Location) -> Unit) {
        recordingCallback = callback
    }

    fun stopRecording() {
        recordingCallback = null
    }

    fun clearRecordedTrack() {
        recordedTrackPolyline.setPoints(emptyList())
        mapView.invalidate()
    }

    // ─── MISE À JOUR DE LA PROGRESSION SUR LA TRACE ──────────────────────────
    private fun updateTrackProgress(location: Location) {
        val track = currentTrack ?: return
        val points = track.points
        val userGeo = GeoPoint(location.latitude, location.longitude)

        var closest = trackProgress
        var minDist = Double.MAX_VALUE

        for (i in trackProgress until points.size) {
            val pt = GeoPoint(points[i].lat.toDouble(), points[i].lon.toDouble())
            val dist = userGeo.distanceToAsDouble(pt)
            if (dist < minDist) {
                minDist = dist
                closest = i
            }
            if (dist > minDist + 500) break
        }

        // ✅ On détermine si l'utilisateur est "sur" la trace (tolérance de 100 mètres)
        isUserOnTrack = (minDist < 100)

        // Si on est sur la trace, on met à jour l'index public pour le profil altimétrique
        if (isUserOnTrack) {
            currentTrackProgressIndex = closest

            // Et si on a avancé, on met à jour le dessin des lignes Rouge/Bleue
            if (closest > trackProgress) {
                trackProgress = closest

                val done = points.subList(0, trackProgress + 1).map { GeoPoint(it.lat.toDouble(), it.lon.toDouble()) }
                completedPolyline?.setPoints(done)

                val left = points.subList(trackProgress, points.size).map { GeoPoint(it.lat.toDouble(), it.lon.toDouble()) }
                remainingPolyline?.setPoints(left)

                mapView.invalidate()
            }
        }
    }

    // ─── OVERLAYS & GPS ──────────────────────────────────────────────────────

    private fun setupLocationOverlay() {
        gpsProvider = GpsMyLocationProvider(requireContext()).apply {
            locationUpdateMinDistance = 0.0f
            locationUpdateMinTime = 2000
        }

        // Création de l'unique overlay (Flèche + Logique)
        locationOverlay = object : MyLocationNewOverlay(gpsProvider, mapView) {
            override fun onLocationChanged(location: Location?, source: IMyLocationProvider?) {
                location ?: return
                if (location.hasAccuracy() && location.accuracy > 25f) return

                if (!location.hasBearing() && lastLocation != null) {
                    if (lastLocation!!.distanceTo(location) > 1f) {
                        location.bearing = lastLocation!!.bearingTo(location)
                    } else {
                        location.bearing = lastLocation!!.bearing
                    }
                }

                super.onLocationChanged(location, source)
                lastLocation = location

                recordingCallback?.invoke(location)

                // ✅ 1. Mise à jour de la vitesse EN TEMPS RÉEL (même sans enregistrer)
                if (location.hasSpeed()) {
                    (activity as? MainActivity)?.updateRealTimeSpeed(location.speed)
                } else {
                    (activity as? MainActivity)?.updateRealTimeSpeed(0f)
                }

                activity?.runOnUiThread {
                    if (isFirstFix) {
                        isFirstFix = false
                        if (isAutoCenterActive) {
                            mapView.controller.setZoom(16.0)
                            mapView.controller.setCenter(GeoPoint(location.latitude, location.longitude))
                        }
                    }

                    updateTrackProgress(location)

                    // Cadrage automatique
                    if (isAutoCenterActive) {
                        updateMapPosition(location)
                    }

                    // ✅ KILOMÉTRAGE JOURNALIER (Toujours actif, même sans enregistrer !)
                    loadAndCheckDailyDistance() // Charge la distance au premier lancement

                    var speedKmH = location.speed * 3.6f
                    if (speedKmH < 1.5f) speedKmH = 0f

                    if (speedKmH > 0f) {
                        lastLocationForDistance?.let { lastLoc ->
                            // 1. Calcul de la distance
                            val distanceAdded = lastLoc.distanceTo(location)

                            // 2. Vérification (au cas où on passe minuit en roulant)
                            val sdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.FRANCE)
                            val todayDate = sdf.format(java.util.Date())
                            if (todayDate != lastDistanceDate) {
                                dailyDistanceMeters = 0f
                                lastDistanceDate = todayDate
                            }

                            // 3. Ajout et Sauvegarde
                            dailyDistanceMeters += distanceAdded
                            requireContext().getSharedPreferences("CairnPrefs", Context.MODE_PRIVATE)
                                .edit()
                                .putFloat("daily_distance_meters", dailyDistanceMeters)
                                .putString("last_distance_date", lastDistanceDate)
                                .apply()
                        }
                    }
                    lastLocationForDistance = location // On mémorise le point pour le prochain calcul

                    // 4. Mise à jour de l'affichage (converti en km)
                    (activity as? MainActivity)?.updateDistanceUI(dailyDistanceMeters / 1000f)
                }
            }
        }

        // Ajout de la flèche personnalisée
        locationOverlay.setDirectionArrow(createUserArrowBitmap(), createUserArrowBitmap())

        locationOverlay.enableMyLocation()
        mapView.overlays.add(locationOverlay)
    }

    // ─── BOUSSOLE MATÉRIELLE ─────────────────────────────────────────────────

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {

            if (!isAutoCenterActive || event == null) return

            val speed = lastLocation?.speed ?: 0f
            if (speed < 1.0f && event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientationAngles = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)

                var azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                if (azimuth < 0) azimuth += 360f

                val targetOrientation = 360f - azimuth

                if (Math.abs(mapView.mapOrientation - targetOrientation) > 2.0f) {
                    mapView.mapOrientation = targetOrientation
                    locationOverlay.lastFix?.bearing = azimuth
                    mapView.invalidate()
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ─── TRACE GPX & DESSIN (MÉTHODES UTILITAIRES) ──────────────────────────

    private fun setupRecordingPolyline() {
        recordedTrackPolyline.outlinePaint.apply {
            color = Color.RED
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        mapView.overlays.add(recordedTrackPolyline)
    }

    // ─── DESSIN DES FLÈCHES DE DIRECTION ─────────────────────────────────────
    private fun addDirectionArrows(points: List<TrackPoint>) {
        directionArrows.forEach { mapView.overlays.remove(it) }
        directionArrows.clear()

        if (points.size < 2) return

        val maxArrows = 30
        val step = maxOf(1, points.size / maxArrows)

        for (i in step until points.size step step) {
            val from = points[i - 1]
            val to   = points[i]
            val bearing = bearingBetween(from, to)

            val arrow = Marker(mapView).apply {
                position = GeoPoint((from.lat + to.lat) / 2.0, (from.lon + to.lon) / 2.0)

                // ✅ 1. On redonne l'angle pour orienter le dessin
                icon = createArrowDrawable(bearing)

                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)

                // ✅ 2. On plaque la flèche au sol pour qu'elle tourne AVEC la carte
                isFlat = true

                title = null
                infoWindow = null
            }
            directionArrows.add(arrow)
            mapView.overlays.add(arrow)
        }
    }

    private fun createArrowDrawable(bearingDeg: Double): android.graphics.drawable.Drawable {
        val size = 50
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // ✅ 1. On fait pivoter le dessin en fonction du cap de la trace
        canvas.rotate(bearingDeg.toFloat(), size / 2f, size / 2f)

        val path = Path().apply {
            moveTo(size / 2f, 4f)
            lineTo(size - 4f, size - 4f)
            lineTo(size / 2f, size - 12f)
            lineTo(4f, size - 4f)
            close()
        }

        // ✅ 2. Le joli remplissage bleu clair
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00BFFF") // Bleu clair très lisible
            style = Paint.Style.FILL
        }
        canvas.drawPath(path, fillPaint)

        // ✅ 3. Le contour blanc bien net
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, strokePaint)

        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    private fun bearingBetween(from: TrackPoint, to: TrackPoint): Double {
        val lat1 = Math.toRadians(from.lat.toDouble())
        val lat2 = Math.toRadians(to.lat.toDouble())
        val dLon = Math.toRadians(to.lon.toDouble() - from.lon.toDouble())
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun createStartIcon(): android.graphics.drawable.Drawable {
        val size = 40
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2E7D32")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 3f, paint)

        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    fun setTrack(track: GpxTrack) {
        currentTrack = track
        trackProgress = 0
        isUserOnTrack = false
        currentTrackProgressIndex = 0

        mapView.overlays.removeAll { it is Polyline && it != recordedTrackPolyline }
        directionArrows.forEach { mapView.overlays.remove(it) }
        directionArrows.clear()

        if (track.points.isEmpty()) return
        val points = track.points.map { GeoPoint(it.lat.toDouble(), it.lon.toDouble()) }

        val startPoint = points.first()
        val startMarker = Marker(mapView).apply {
            position = startPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Départ : ${track.name}"
            icon = createStartIcon()
            icon?.setTint(Color.parseColor("#2E7D32"))
        }
        mapView.overlays.add(startMarker)

        remainingPolyline = Polyline(mapView).apply {
            outlinePaint.color = Color.parseColor("#0000FF")
            outlinePaint.strokeWidth = 10f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            setPoints(points)
            infoWindow = null
        }

        completedPolyline = Polyline(mapView).apply {
            outlinePaint.color = Color.RED
            outlinePaint.strokeWidth = 10f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            setPoints(emptyList())
            infoWindow = null
        }

        mapView.overlays.add(completedPolyline)
        mapView.overlays.add(remainingPolyline)

        addDirectionArrows(track.points)

        mapView.invalidate()

        downloadTrackCache(track)
    }

    fun postOnMap(action: () -> Unit) {
        if (::mapView.isInitialized) {
            mapView.post { action() }
        }
    }

    private fun createUserArrowBitmap(): Bitmap {
        val size = 110
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val fillColor = Color.parseColor("#FFDE21")
        val strokeColor = Color.parseColor("#0000FF")

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }

        val path = Path().apply {
            moveTo(size / 2f, 6f)
            lineTo(size.toFloat() - 6f, size.toFloat() - 6f)
            lineTo(size / 2f, size.toFloat() - 28f)
            lineTo(6f, size.toFloat() - 6f)
            close()
        }

        canvas.drawPath(path, paint)

        paint.apply {
            color = strokeColor
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeJoin = Paint.Join.ROUND
        }

        canvas.drawPath(path, paint)

        return bmp
    }

    private fun setupCompassOverlay() {
        val compass = CompassOverlay(requireContext(), mapView)
        compass.enableCompass()
        mapView.overlays.add(compass)
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (::locationOverlay.isInitialized) locationOverlay.enableMyLocation()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rotationSensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(sensorListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        locationOverlay.disableMyLocation()
    }

    fun zoomToTrack(track: com.bpi.cairn.gpx.GpxTrack) {
        if (track.points.isEmpty()) return

        isFirstFix = false

        val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(
            track.points.map { GeoPoint(it.lat.toDouble(), it.lon.toDouble()) }
        )
        mapView.zoomToBoundingBox(boundingBox.increaseByScale(1.2f), true)
    }

    // 1. Le vrai SCAN 25 (Cartes topographiques classiques avec sentiers roses)
    private val IgnCartesTileSource = object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
        "IGN_Scan25_Vrai",
        0,
        16,
        256,
        ".jpeg",
        arrayOf("https://data.geopf.fr/private/wmts?")
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
            val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)

            return "https://data.geopf.fr/private/wmts?apikey=ign_scan_ws&SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=GEOGRAPHICALGRIDSYSTEMS.MAPS&STYLE=normal&FORMAT=image/jpeg&TILEMATRIXSET=PM&TILEMATRIX=$zoom&TILEROW=$y&TILECOL=$x"
        }
    }

    // 2. Photos Aériennes IGN (Vue satellite haute résolution)
    private val IgnPhotoTileSource = object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
        "IGN_Photo",
        0, 19, 256, ".jpeg",
        arrayOf("https://data.geopf.fr/wmts?")
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
            val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
            return "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=ORTHOIMAGERY.ORTHOPHOTOS&STYLE=normal&FORMAT=image/jpeg&TILEMATRIXSET=PM&TILEMATRIX=$zoom&TILEROW=$y&TILECOL=$x"
        }
    }

    // ─── CHANGEMENT DU FOND DE CARTE ─────────────────────────────────────────
    fun setMapStyle(styleName: String) {
        when (styleName) {
            "IGN_Cartes" -> mapView.setTileSource(IgnCartesTileSource)
            "IGN_Photo" -> mapView.setTileSource(IgnPhotoTileSource)
            "OpenTopo" -> mapView.setTileSource(TileSourceFactory.OpenTopo)
            "OSM_Standard" -> mapView.setTileSource(TileSourceFactory.MAPNIK)
        }
        mapView.invalidate()
    }
}