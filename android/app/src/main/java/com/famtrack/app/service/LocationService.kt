package com.famtrack.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.famtrack.app.FamTrackApp
import com.famtrack.app.MainActivity
import com.famtrack.app.R
import com.famtrack.app.data.model.Geofence
import com.famtrack.app.data.model.RoutePoint
import com.famtrack.app.data.remote.FamilyRepository
import com.famtrack.app.data.remote.GeofenceRepository
import com.famtrack.app.data.remote.LocationRepository
import com.famtrack.app.data.remote.NotificationRepository
import com.famtrack.app.data.remote.SupabaseClient
import com.google.android.gms.location.*
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LocationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationClient: FusedLocationProviderClient
    private val locationRepository = LocationRepository()
    private val geofenceRepository = GeofenceRepository()
    private val familyRepository = FamilyRepository()
    private val notificationRepository = NotificationRepository()

    private var currentFamilyId: String? = null
    private var currentUserId: String? = null

    // Cache local de geofences (evita fetch a cada atualização)
    private var cachedGeofences: List<Geofence> = emptyList()

    // Última localização conhecida (usada pelo sensor de passos)
    private var lastKnownLocation: Location? = null

    // Sensor de passos (STEP_COUNTER)
    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var lastStepCount: Long = 0L
    private var stepCountInitialized = false

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
            val total = event.values[0].toLong()
            if (!stepCountInitialized) {
                stepCountInitialized = true
                lastStepCount = total
                return
            }
            val delta = (total - lastStepCount).coerceAtLeast(0L)
            lastStepCount = total
            if (delta == 0L) return

            // Acumula passos apenas se a última posição estiver dentro de um local conhecido
            val location = lastKnownLocation ?: return
            val insidePlace = cachedGeofences
                .filter { it.active }
                .firstOrNull { gf ->
                    val dist = FloatArray(1)
                    Location.distanceBetween(
                        location.latitude,
                        location.longitude,
                        gf.center_lat,
                        gf.center_lon,
                        dist
                    )
                    dist[0] <= gf.radius_meters
                }
            if (insidePlace != null) {
                if (currentPlaceName != insidePlace.name) {
                    currentPlaceName = insidePlace.name
                    currentPlaceSteps = 0
                }
                currentPlaceSteps += delta.toInt()
            } else {
                currentPlaceName = null
                currentPlaceSteps = 0
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        locationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentFamilyId = intent.getStringExtra(EXTRA_FAMILY_ID)
                currentUserId = intent.getStringExtra(EXTRA_USER_ID)
                start()
            }
            ACTION_STOP -> {
                stop()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun start() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        refreshGeofenceCache()
        startGeofenceCacheRefreshLoop()
        startLocationUpdates()
        registerStepSensor()
    }

    private fun stop() {
        unregisterStepSensor()
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MS
        ).apply {
            setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
            setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            setWaitForAccurateLocation(true)
        }.build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    lastKnownLocation = location
                    sendLocationToSupabase(location)
                    saveRoutePoint(location)
                    checkGeofences(location)
                }
            }
        }

        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }

    private fun sendLocationToSupabase(location: Location) {
        if (isSharingPaused()) return

        val familyId = currentFamilyId ?: return
        val userId = currentUserId ?: return

        serviceScope.launch {
            try {
                val batteryLevel = (getSystemService(BATTERY_SERVICE) as? android.os.BatteryManager)
                    ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val famLocation = com.famtrack.app.data.model.Location(
                    family_id = familyId,
                    user_id = userId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy.toDouble(),
                    speed = location.speed.toDouble(),
                    bearing = location.bearing.toDouble(),
                    batteryLevel = batteryLevel,
                    lastUpdatedAt = System.currentTimeMillis()
                )
                // Upsert: mantém apenas a localização mais recente por (family_id, user_id)
                locationRepository.upsertLocation(famLocation)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // F7: guarda única no ponto de envio — lê a preferência persistida pela tela de Privacidade
    private fun isSharingPaused(): Boolean {
        return getSharedPreferences("privacy_prefs", MODE_PRIVATE)
            .getBoolean("sharing_paused", false)
    }

    private fun saveRoutePoint(location: Location) {
        val familyId = currentFamilyId ?: return
        val userId = currentUserId ?: return

        serviceScope.launch {
            try {
                val routePoint = RoutePoint(
                    family_id = familyId,
                    user_id = userId,
                    latitude = location.latitude,
                    longitude = location.longitude
                )
                locationRepository.saveRoutePoint(routePoint)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun refreshGeofenceCache() {
        val familyId = currentFamilyId ?: return
        serviceScope.launch {
            try {
                cachedGeofences = geofenceRepository.getFamilyGeofences(familyId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startGeofenceCacheRefreshLoop() {
        serviceScope.launch {
            while (true) {
                delay(GEOFENCE_CACHE_REFRESH_MS)
                refreshGeofenceCache()
            }
        }
    }

    private fun checkGeofences(location: Location) {
        val familyId = currentFamilyId ?: return
        val userId = currentUserId ?: return

        serviceScope.launch {
            try {
                val activeGeofences = cachedGeofences.filter { it.active }

                for (geofence in activeGeofences) {
                    val distance = FloatArray(1)
                    Location.distanceBetween(
                        location.latitude,
                        location.longitude,
                        geofence.center_lat,
                        geofence.center_lon,
                        distance
                    )

                    val isInside = distance[0] <= geofence.radius_meters
                    val wasInside = isUserInsideGeofence(userId, geofence.id!!)

                    if (!wasInside && isInside) {
                        val title = geofence.name
                        val message = "Chegou em: ${geofence.name}"
                        sendGeofenceNotification(title, message, geofence.id.hashCode())
                        insertGeofenceNotification(familyId, userId, title, message)
                    } else if (wasInside && !isInside) {
                        val title = geofence.name
                        val message = "Saiu de: ${geofence.name}"
                        sendGeofenceNotification(title, message, geofence.id.hashCode() + 1)
                        insertGeofenceNotification(familyId, userId, title, message)
                    }

                    updateGeofenceState(userId, geofence.id, isInside)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun insertGeofenceNotification(
        familyId: String,
        userId: String,
        title: String,
        message: String
    ) {
        serviceScope.launch {
            try {
                // Notifica os membros da família (exceto o próprio usuário)
                notificationRepository.notifyFamily(
                    familyId = familyId,
                    senderUserId = userId,
                    title = title,
                    message = message,
                    type = "geofence"
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isUserInsideGeofence(userId: String, geofenceId: String): Boolean {
        val prefs = getSharedPreferences("geofence_state", MODE_PRIVATE)
        return prefs.getBoolean("${userId}_${geofenceId}", false)
    }

    private fun updateGeofenceState(userId: String, geofenceId: String, inside: Boolean) {
        val prefs = getSharedPreferences("geofence_state", MODE_PRIVATE)
        prefs.edit().putBoolean("${userId}_${geofenceId}", inside).apply()
    }

    private fun sendGeofenceNotification(title: String, message: String, notificationId: Int) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, FamTrackApp.GEOFENCE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(notificationId, notification)
        } catch (_: SecurityException) { }
    }

    private fun registerStepSensor() {
        try {
            // Android 10+ exige esta permissão para ler o sensor de passos
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACTIVITY_RECOGNITION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            sensorManager = getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
            stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            stepSensor?.let { sensor ->
                sensorManager?.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unregisterStepSensor() {
        try {
            if (stepSensor != null && sensorManager != null) {
                sensorManager?.unregisterListener(stepListener)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stepSensor = null
        currentPlaceName = null
        currentPlaceSteps = 0
        stepCountInitialized = false
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, FamTrackApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("FamTrack")
            .setContentText("Compartilhando localizacao...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterStepSensor()
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_FAMILY_ID = "EXTRA_FAMILY_ID"
        const val EXTRA_USER_ID = "EXTRA_USER_ID"

        // Passos acumulados no local atual (lidos pela tela do próprio usuário)
        @Volatile
        var currentPlaceName: String? = null

        @Volatile
        var currentPlaceSteps: Int = 0

        private const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MS = 3000L
        private const val MIN_DISTANCE_METERS = 5f
        private const val GEOFENCE_CACHE_REFRESH_MS = 60000L

        fun startService(
            context: android.content.Context,
            familyId: String,
            userId: String
        ) {
            val intent = Intent(context, LocationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FAMILY_ID, familyId)
                putExtra(EXTRA_USER_ID, userId)
            }
            context.startForegroundService(intent)
        }

        fun stopService(context: android.content.Context) {
            val intent = Intent(context, LocationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
