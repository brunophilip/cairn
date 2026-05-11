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
import androidx.fragment.app.Fragment
import com.bpi.cairn.gpx.GpxTrack
import com.bpi.cairn.gpx.TrackPoint
import org.osmdroid.config.Configuration
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
    private var isFirstFix = true

    // États de contrôle
    private var isFollowing = false
    private var shouldLockCamera = false // Est mis à true pendant l'enregistrement

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        Configuration.getInstance().load(
            requireContext(),
            requireContext().getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = "com.bpi.cairn"

        mapView = MapView(requireContext()).apply {
            setTileSource(TileSourceFactory.OpenTopo)
            setMultiTouchControls(true)
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(46.603354, 1.888334))
        }

        setupLocationOverlay()
        setupCompassOverlay()
        setupRecordingPolyline()

        return mapView
    }

    // ─── LOGIQUE DE CAMÉRA (CENTRALISÉE) ────────────────────────────────────

    fun updateCameraMode(following: Boolean, recording: Boolean) {
        this.isFollowing = following
        this.shouldLockCamera = recording

        // Si on active l'un des deux modes, on recadre immédiatement
        if (isFollowing || shouldLockCamera) {
            lastLocation?.let { updateMapPosition(it) }
        } else {
            // Si on coupe tout, on remet le Nord en haut
            mapView.mapOrientation = 0f
        }
    }

    private fun updateMapPosition(location: Location) {
        val geoPoint = GeoPoint(location.latitude, location.longitude)
        val speed = location.speed

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

    // ─── MISE À JOUR DE LA PROGRESSION SUR LA TRACE ──────────────────────────
    private fun updateTrackProgress(location: Location) {
        val track = currentTrack ?: return
        val points = track.points

        val userGeo = GeoPoint(location.latitude, location.longitude)
        var closest = trackProgress
        var minDist = Double.MAX_VALUE

        // On cherche le point de la trace le plus proche de la position GPS actuelle
        // On commence la recherche à partir du dernier index connu (trackProgress)
        for (i in trackProgress until points.size) {
            val pt = GeoPoint(points[i].lat, points[i].lon)
            val dist = userGeo.distanceToAsDouble(pt)
            if (dist < minDist) {
                minDist = dist
                closest = i
            }
            // Si on s'éloigne trop (plus de 500m), on arrête de chercher pour économiser le processeur
            if (dist > minDist + 500) break
        }

        // Si on a avancé sur la trace
        if (closest > trackProgress) {
            trackProgress = closest

            // On met à jour la ligne verte (partie terminée)
            val done = points.subList(0, trackProgress + 1).map { GeoPoint(it.lat, it.lon) }
            completedPolyline?.setPoints(done)

            // On met à jour la ligne rouge (partie restante)
            val left = points.subList(trackProgress, points.size).map { GeoPoint(it.lat, it.lon) }
            remainingPolyline?.setPoints(left)

            mapView.invalidate() // Rafraîchir l'affichage
        }
    }
    // ─── OVERLAYS & GPS ──────────────────────────────────────────────────────

    private fun setupLocationOverlay() {
        gpsProvider = GpsMyLocationProvider(requireContext()).apply {
            locationUpdateMinDistance = 2.0f
            locationUpdateMinTime = 2000
        }

        locationOverlay = object : MyLocationNewOverlay(gpsProvider, mapView) {
            override fun onLocationChanged(location: Location?, source: IMyLocationProvider?) {
                location ?: return

                // Ignorer si trop imprécis
                if (location.hasAccuracy() && location.accuracy > 25f) return

                // Calcul du cap manuel si le GPS ne le fournit pas (vitesse lente)
                if (!location.hasBearing() && lastLocation != null) {
                    if (lastLocation!!.distanceTo(location) > 1f) {
                        location.bearing = lastLocation!!.bearingTo(location)
                    } else {
                        location.bearing = lastLocation!!.bearing
                    }
                }

                super.onLocationChanged(location, source)
                lastLocation = location

                // Notifier MainActivity (via callback) pour le stockage GPX
                recordingCallback?.invoke(location)

                activity?.runOnUiThread {
                    if (isFirstFix) {
                        mapView.controller.setZoom(16.0)
                        mapView.controller.setCenter(GeoPoint(location.latitude, location.longitude))
                        isFirstFix = false
                    }

                    updateTrackProgress(location)

                    // 🎯 RÈGLE DE CAMÉRA : Suivi OU Enregistrement
                    if (isFollowing || shouldLockCamera) {
                        updateMapPosition(location)
                    }
                }
            }
        }

        val userArrow = createUserArrowBitmap()
        locationOverlay.setDirectionArrow(userArrow, userArrow)
        locationOverlay.setPersonIcon(userArrow)
        locationOverlay.setPersonAnchor(0.5f, 0.5f)
        locationOverlay.setDirectionAnchor(0.5f, 0.5f)

        locationOverlay.enableMyLocation()
        mapView.overlays.add(locationOverlay)
    }

    // ─── BOUSSOLE MATÉRIELLE ─────────────────────────────────────────────────

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            // On ne pivote que si on est en mode "verrouillé"
            if (!(isFollowing || shouldLockCamera) || event == null) return

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
                    // Optionnel : synchroniser l'icône de l'utilisateur
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
        // On place une flèche environ tous les 20 points pour ne pas surcharger la carte
        val step = maxOf(1, points.size / 20)
        for (i in step until points.size step step) {
            val from = points[i - 1]
            val to   = points[i]
            val bearing = bearingBetween(from, to)

            val arrow = Marker(mapView).apply {
                position = GeoPoint((from.lat + to.lat) / 2, (from.lon + to.lon) / 2)
                icon = createArrowDrawable(bearing)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = null
                infoWindow = null
            }
            directionArrows.add(arrow)
            mapView.overlays.add(arrow)
        }
    }

    private fun createArrowDrawable(bearingDeg: Double): android.graphics.drawable.Drawable {
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            alpha = 200
            style = Paint.Style.FILL
        }
        val path = Path().apply {
            moveTo(size / 2f, 4f)
            lineTo(size - 4f, size - 4f)
            lineTo(size / 2f, size - 12f)
            lineTo(4f, size - 4f)
            close()
        }
        canvas.rotate(bearingDeg.toFloat(), size / 2f, size / 2f)
        canvas.drawPath(path, paint)
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    private fun bearingBetween(from: TrackPoint, to: TrackPoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun setTrack(track: GpxTrack) {
        currentTrack = track
        trackProgress = 0

        // 1. Nettoyage des anciennes traces (on garde uniquement la position et l'enregistrement)
        mapView.overlays.removeAll { it is Polyline && it != recordedTrackPolyline }
        directionArrows.forEach { mapView.overlays.remove(it) }
        directionArrows.clear()

        val points = track.points.map { GeoPoint(it.lat, it.lon) }

        // 2. Création de la ligne "Restante" (Rouge/Orange)
        remainingPolyline = Polyline(mapView).apply {
            outlinePaint.color = Color.RED
            outlinePaint.strokeWidth = 8f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            setPoints(points)
            infoWindow = null
        }

        // 3. Création de la ligne "Parcourue" (Verte)
        completedPolyline = Polyline(mapView).apply {
            outlinePaint.color = Color.GREEN
            outlinePaint.strokeWidth = 8f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            setPoints(emptyList())
            infoWindow = null
        }

        // 4. Ajout des overlays à la carte
        mapView.overlays.add(completedPolyline)
        mapView.overlays.add(remainingPolyline)

        // 5. Ajout des flèches de direction
        addDirectionArrows(track.points)

        // 6. Cadrage automatique de la carte sur la trace
        if (points.isNotEmpty()) {
            val minLat = track.points.minOf { it.lat }
            val maxLat = track.points.maxOf { it.lat }
            val minLon = track.points.minOf { it.lon }
            val maxLon = track.points.maxOf { it.lon }

            val boundingBox = BoundingBox(maxLat, maxLon, minLat, minLon)

            mapView.post {
                mapView.zoomToBoundingBox(boundingBox, true, 150)
            }
        }

        mapView.invalidate() // Rafraîchissement final
    }

    fun postOnMap(action: () -> Unit) {
        if (::mapView.isInitialized) {
            mapView.post { action() }
        }
    }

    private fun createUserArrowBitmap(): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1565C0") }
        val path = Path().apply {
            moveTo(size / 2f, 4f)
            lineTo(size - 6f, size - 4f)
            lineTo(size / 2f, size - 16f)
            lineTo(6f, size - 4f)
            close()
        }
        canvas.drawPath(path, paint)
        paint.apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
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
}