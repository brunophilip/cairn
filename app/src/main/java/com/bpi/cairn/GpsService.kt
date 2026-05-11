package com.bpi.cairn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationRequest

class GpsService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    companion object {
        private const val CHANNEL_ID = "gps_tracking_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "ACTION_START_TRACKING"
        const val ACTION_STOP = "ACTION_STOP_TRACKING"

        // Clé pour diffuser les nouvelles positions
        const val ACTION_LOCATION_UPDATE = "ACTION_LOCATION_UPDATE"
        const val EXTRA_LOCATION = "EXTRA_LOCATION"
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Que faire quand on reçoit un nouveau point GPS
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    Log.d("GpsService", "Nouveau point: ${location.latitude}, ${location.longitude}")

                    // On diffuse le point pour que MainActivity/MapFragment puisse l'afficher
                    val intent = Intent(ACTION_LOCATION_UPDATE)
                    intent.putExtra(EXTRA_LOCATION, location)
                    sendBroadcast(intent)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
        }
        return START_STICKY // Redémarre le service si Android le tue pour libérer de la RAM
    }

    private fun startTracking() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        requestLocationUpdates()
    }

    private fun requestLocationUpdates() {
        // Configuration du GPS (Haute précision, mise à jour toutes les 3 secondes)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateDistanceMeters(2f) // Optionnel: mise à jour seulement si on bouge de 2 mètres
            .build()

        // Vérification de sécurité pour les permissions (obligatoire pour compiler)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        // Démarrage de l'écoute du GPS
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cairn")
            .setContentText("Enregistrement de la trace en cours...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation) // Change avec ton icône R.drawable.ic_btn_record
            .setOngoing(true) // Empêche l'utilisateur de swiper la notification
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Enregistrement GPS",
                NotificationManager.IMPORTANCE_LOW // LOW = pas de sonnerie/vibration à chaque fois
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}