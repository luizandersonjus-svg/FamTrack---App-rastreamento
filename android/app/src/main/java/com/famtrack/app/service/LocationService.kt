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
import android.util.Log
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LocationService : Service() {

    // Rede de segurança global: qualquer exceção não capturada dentro das
    // coroutines do serviço é registrada no Logcat em vez de derrubar o processo.
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Exceção não capturada em coroutine do serviço: ${throwable.localizedMessage}", throwable)
        }
    )
    private lateinit var locationClient: FusedLocationProviderClient
    private val locationRepository = LocationRepository()
    private val geofenceRepository = GeofenceRepository()
    private val familyRepository = FamilyRepository()
    private val notificationRepository = NotificationRepository()

    private var currentFamilyId: String? = null
    private var currentUserId: String? = null

    // Evita registrar o FusedLocationProvider duas vezes (múltiplos ACTION_START).
    private var trackingStarted = false
    private var locationCallback: LocationCallback? = null
    private val servicePrefs by lazy {
        getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
    }

    // Cache local de geofences (evita fetch a cada atualização)
    private var cachedGeofences: List<Geofence> = emptyList()

    // Última localização conhecida (usada pelo sensor de passos)
    private var lastKnownLocation: Location? = null

    // Amostragem do histórico de rota (Fase 1): último ponto gravado em memória
    private var lastRouteLat: Double? = null
    private var lastRouteLng: Double? = null
    private var lastRouteTime: Long = 0L
    private var lastRouteBearing: Float = 0f
    private var hasLastRouteBearing = false

    // Sensor de passos (STEP_COUNTER)
    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var lastStepCount: Long = 0L
    private var stepCountInitialized = false

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            try {
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
            } catch (e: Exception) {
                // Sensor/coordenadas fora do comum: nunca derruba o serviço.
                Log.e(TAG, "Erro protegido no sensor de passos: ${e.localizedMessage}")
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
                persistIds(currentFamilyId, currentUserId)
                if (currentFamilyId != null && currentUserId != null) {
                    start()
                } else {
                    Log.e(TAG, "ACTION_START sem ids de família/usuário")
                    stop()
                }
            }
            ACTION_STOP -> stop()
            else -> {
                // Android recriou o processo (START_STICKY) sem intent:
                // restaura o tracking a partir das preferências persistidas.
                if (currentFamilyId == null || currentUserId == null) {
                    restoreIds()?.let { (fid, uid) ->
                        currentFamilyId = fid
                        currentUserId = uid
                    }
                }
                if (currentFamilyId != null && currentUserId != null) {
                    Log.i(TAG, "Serviço recriado pelo sistema; retomando tracking")
                    start()
                } else {
                    Log.e(TAG, "Serviço recriado sem ids válidos; encerrando de forma controlada")
                    shutdown()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun start() {
        try {
            if (trackingStarted) {
                // Já em tracking: apenas reafirma o foreground (evita registrar updates 2x).
                startForeground(NOTIFICATION_ID, createNotification())
                return
            }
            trackingStarted = true
            val notification = createNotification()
            // Foreground obrigatório ANTES de qualquer operação de localização/rede.
            startForeground(NOTIFICATION_ID, notification)
            refreshGeofenceCache()
            startGeofenceCacheRefreshLoop()
            startLocationUpdates()
            registerStepSensor()
        } catch (e: Exception) {
            // Hibernação/retomada: nunca deixe a inicialização derrubar o serviço.
            Log.e(TAG, "Erro protegido ao iniciar tracking: ${e.localizedMessage}")
        }
    }

    private fun stop() {
        if (trackingStarted) {
            trackingStarted = false
            stopLocationUpdates()
            unregisterStepSensor()
        }
        removeForeground()
        // Para nesta sessão: impede START_STICKY de retomar tracking contra a vontade do usuário.
        clearPersistedIds()
        stopSelf()
    }

    private fun shutdown() {
        if (trackingStarted) {
            trackingStarted = false
            stopLocationUpdates()
            unregisterStepSensor()
        }
        removeForeground()
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun removeForeground() {
        try {
            stopForeground(true)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao remover foreground", e)
        }
    }

    private fun persistIds(familyId: String?, userId: String?) {
        servicePrefs.edit()
            .putString(KEY_FAMILY_ID, familyId)
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    private fun restoreIds(): Pair<String, String>? {
        val fid = servicePrefs.getString(KEY_FAMILY_ID, null) ?: return null
        val uid = servicePrefs.getString(KEY_USER_ID, null) ?: return null
        return fid to uid
    }

    private fun clearPersistedIds() {
        servicePrefs.edit()
            .remove(KEY_FAMILY_ID)
            .remove(KEY_USER_ID)
            .apply()
    }

    private fun startLocationUpdates() {
        if (locationCallback != null) {
            // Já registrado (ex.: múltiplos ACTION_START); evita updates duplicados.
            return
        }
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MS
        ).apply {
            setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
            setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            setWaitForAccurateLocation(true)
        }.build()

        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Permissão de localização ausente; updates não registrados")
            return
        }

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                try {
                    result.lastLocation?.let { location ->
                        lastKnownLocation = location
                        // Política de privacidade: pausado, nada é enviado ao backend.
                        if (!isSharingPaused()) {
                            sendLocationToSupabase(location)
                            saveRoutePoint(location)
                        }
                        checkGeofences(location)
                    }
                } catch (e: Exception) {
                    // Main thread (callback): qualquer inesperado aqui NÃO pode derrubar o app.
                    Log.e(TAG, "Erro protegido em onLocationResult: ${e.localizedMessage}")
                }
            }
        }
        this.locationCallback = locationCallback

        try {
            locationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Sem permissão para registrar updates", e)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao registrar location updates", e)
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            locationCallback = null
            try {
                locationClient.removeLocationUpdates(callback)
            } catch (e: SecurityException) {
                Log.e(TAG, "Sem permissão ao remover updates", e)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao remover location updates", e)
            }
        }
    }

    private fun sendLocationToSupabase(location: Location) {
        if (isSharingPaused()) return

        val familyId = currentFamilyId ?: return
        val userId = currentUserId ?: return

        serviceScope.launch {
            try {
                val rawBattery = (getSystemService(BATTERY_SERVICE) as? android.os.BatteryManager)
                    ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val batteryLevel = rawBattery?.takeIf { it in 0..100 }
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
                // Falha de rede/Supabase: NÃO para o serviço; tenta de novo no próximo fix.
                Log.e(TAG, "Falha ao enviar localização ao Supabase", e)
            }
        }
    }

    // F7: guarda única no ponto de envio — lê a preferência persistida pela tela de Privacidade
    private fun isSharingPaused(): Boolean {
        return getSharedPreferences("privacy_prefs", MODE_PRIVATE)
            .getBoolean("sharing_paused", false)
    }

    /**
     * Decisão de amostragem: grava apenas pontos relevantes para a forma da rota.
     * A) primeiro ponto sempre grava;
     * B) deslocamento real >= max(10m, accuracy) e tempo >= 8s quando em movimento
     *    (velocidade > 1 m/s — caminhada/veículo); parado (< 1 m/s)
     *    mantém os limiares anteriores (20m / 20s);
     * C) curva: mudança de rumo > 15° (30° parado), velocidade > 1 m/s,
     *    deslocou >= 8m, tempo >= 5s;
     * D) permanência: nada gravado há >= 3min (mantém paradas visíveis no resumo);
     * E) fixes com accuracy > 500m só valem para D.
     */
    private fun shouldRecordRoutePoint(location: Location): Boolean {
        val lastLat = lastRouteLat
        val lastLng = lastRouteLng
        if (lastLat == null || lastLng == null) return true

        val elapsed = location.time - lastRouteTime
        val accuracy = location.accuracy
        // E) ignora jitter de GPS parado com precisão ruim (regras B/C)
        val accurate = accuracy <= 0f || accuracy <= ROUTE_MAX_ACCURACY_M

        // Em movimento a amostragem fica mais densa (captura curvas); parado,
        // mantém as regras da Fase 1 para não acumular pontos inúteis.
        val moving = location.hasSpeed() && location.speed > ROUTE_MOVING_SPEED_MS
        val minDist = if (moving) FAST_ROUTE_MIN_DISTANCE_M else ROUTE_MIN_DISTANCE_FLOOR_M
        val minTime = if (moving) FAST_ROUTE_MIN_TIME_MS else ROUTE_MIN_TIME_MS
        val turnDeg = if (moving) FAST_CURVE_TURN_DEG else ROUTE_CURVE_TURN_DEG
        val curveDist = if (moving) FAST_CURVE_MIN_DISTANCE_M else ROUTE_CURVE_MIN_DISTANCE_M

        val distance = FloatArray(1)
        Location.distanceBetween(
            lastLat, lastLng,
            location.latitude, location.longitude,
            distance
        )
        val dist = distance[0].toDouble()

        // B) deslocamento real
        if (accurate && elapsed >= minTime
            && dist >= maxOf(minDist, accuracy.toDouble())
        ) return true

        // C) curva
        if (accurate && location.hasBearing() && location.hasSpeed()
            && location.speed > ROUTE_CURVE_MIN_SPEED_MS
            && elapsed >= ROUTE_CURVE_MIN_TIME_MS
            && dist >= curveDist
            && (!hasLastRouteBearing ||
                kotlin.math.abs(bearingDelta(location.bearing, lastRouteBearing)) > turnDeg)
        ) return true

        // D) permanência
        return elapsed >= ROUTE_HEARTBEAT_MS
    }

    /** Menor diferença angular entre dois rumos, em graus (-180..180). */
    private fun bearingDelta(a: Float, b: Float): Float {
        var d = (a - b) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /**
     * Grava um ponto no histórico de rota quando a amostragem decide. Falha de
     * rede/servidor: Log.w, NÃO atualiza o lastSavedPoint (o próximo fix tenta
     * de novo) e NUNCA para o serviço nem afeta o feed ao vivo.
     */
    private fun saveRoutePoint(location: Location) {
        val familyId = currentFamilyId ?: return
        val userId = currentUserId ?: return

        // Avaliação de amostragem roda na main thread: qualquer falha (ex.:
        // coordenada inválida em Location.distanceBetween) apenas descarta o ponto.
        val shouldRecord = try {
            shouldRecordRoutePoint(location)
        } catch (e: Exception) {
            Log.e(TAG, "Erro protegido ao avaliar amostragem; ponto descartado: ${e.localizedMessage}")
            false
        }
        if (!shouldRecord) return

        serviceScope.launch {
            try {
                val rawBattery = (getSystemService(BATTERY_SERVICE) as? android.os.BatteryManager)
                    ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val batteryLevel = rawBattery?.takeIf { it in 0..100 }
                val routePoint = RoutePoint(
                    family_id = familyId,
                    user_id = userId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    recorded_at = java.time.Instant.ofEpochMilli(location.time).toString(),
                    accuracy = location.accuracy.takeIf { it > 0f },
                    speed = if (location.hasSpeed()) location.speed else null,
                    bearing = if (location.hasBearing()) location.bearing else null,
                    batteryLevel = batteryLevel,
                    provider = location.provider
                )
                val saved = locationRepository.saveRoutePoint(routePoint)
                // Sucesso (insert completo ou fallback legado): atualiza o ponto de
                // referência da amostragem para o próximo fix continuar a rota.
                if (saved) {
                    lastRouteLat = location.latitude
                    lastRouteLng = location.longitude
                    lastRouteTime = location.time
                    lastRouteBearing = location.bearing
                    hasLastRouteBearing = location.hasBearing()
                } else {
                    Log.w(
                        ROUTE_HISTORY_TAG,
                        "Ponto de rota descartado: insert completo e fallback legado falharam"
                    )
                }
            } catch (e: Exception) {
                Log.w(
                    ROUTE_HISTORY_TAG,
                    "Falha ao gravar ponto de rota (ignorada); próxima amostra tentará de novo",
                    e
                )
            }
        }
    }

    private fun refreshGeofenceCache() {
        val familyId = currentFamilyId ?: return
        serviceScope.launch {
            try {
                cachedGeofences = geofenceRepository.getFamilyGeofences(familyId)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao atualizar cache de geofences", e)
            }
        }
    }

    private fun startGeofenceCacheRefreshLoop() {
        serviceScope.launch {
            while (true) {
                try {
                    delay(GEOFENCE_CACHE_REFRESH_MS)
                    refreshGeofenceCache()
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    // Cancelamento coordenado do serviço: propaga em vez de travar o loop.
                    throw ce
                } catch (e: Exception) {
                    // Falha pontual no ciclo (rede/banco): loga e segue para a próxima rodada.
                    Log.e(TAG, "Falha protegida no refresh de geofences (continuando): ${e.localizedMessage}")
                }
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
                    // Defensivo: geofence sem id não pode ser avaliada nem transicionada.
                    val geofenceId = geofence.id ?: continue

                    val distance = FloatArray(1)
                    Location.distanceBetween(
                        location.latitude,
                        location.longitude,
                        geofence.center_lat,
                        geofence.center_lon,
                        distance
                    )

                    val isInside = distance[0] <= geofence.radius_meters
                    val wasInside = isUserInsideGeofence(userId, geofenceId)

                    if (!wasInside && isInside) {
                        val title = geofence.name
                        val message = "Chegou em: ${geofence.name}"
                        sendGeofenceNotification(title, message, geofenceId.hashCode())
                        insertGeofenceNotification(familyId, userId, title, message)
                    } else if (wasInside && !isInside) {
                        val title = geofence.name
                        val message = "Saiu de: ${geofence.name}"
                        sendGeofenceNotification(title, message, geofenceId.hashCode() + 1)
                        insertGeofenceNotification(familyId, userId, title, message)
                    }

                    updateGeofenceState(userId, geofenceId, isInside)
                }
            } catch (e: Exception) {
                // Nunca crasha por falha de rede/banco/estado na avaliação de geofences.
                Log.e(TAG, "Falha protegida na avaliação de geofences: ${e.localizedMessage}")
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
                Log.e(TAG, "Falha ao enviar notificação de geofence", e)
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
            .setContentTitle(getString(R.string.location_service_notification_title))
            .setContentText(getString(R.string.location_service_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        unregisterStepSensor()
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_FAMILY_ID = "EXTRA_FAMILY_ID"
        const val EXTRA_USER_ID = "EXTRA_USER_ID"

        private const val TAG = "LocationService"
        private const val ROUTE_HISTORY_TAG = "FamTrackRouteHistory"
        private const val SERVICE_PREFS = "location_service_prefs"
        private const val KEY_FAMILY_ID = "service_family_id"
        private const val KEY_USER_ID = "service_user_id"

        // Passos acumulados no local atual (lidos pela tela do próprio usuário)
        @Volatile
        var currentPlaceName: String? = null

        @Volatile
        var currentPlaceSteps: Int = 0

        private const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MS = 3000L
        private const val MIN_DISTANCE_METERS = 5f
        private const val GEOFENCE_CACHE_REFRESH_MS = 60000L

        // Amostragem do histórico de rota (Fase 1)
        private const val ROUTE_MIN_DISTANCE_FLOOR_M = 20.0
        private const val ROUTE_MAX_ACCURACY_M = 500.0
        private const val ROUTE_MIN_TIME_MS = 20_000L
        private const val ROUTE_CURVE_TURN_DEG = 30.0
        private const val ROUTE_CURVE_MIN_DISTANCE_M = 10.0
        private const val ROUTE_CURVE_MIN_TIME_MS = 5_000L
        private const val ROUTE_CURVE_MIN_SPEED_MS = 1.0
        private const val ROUTE_HEARTBEAT_MS = 3 * 60_000L

        // Limiares densos quando em movimento (velocidade > 1 m/s): mais pontos
        // em deslocamento para desenhar curvas sem o efeito "linha reta". Caminhada
        // normal (≈1,4 m/s) é tratada como movimento para o percurso curto não ser
        // descartado integralmente pelos limiares de parado.
        private const val ROUTE_MOVING_SPEED_MS = 1.0
        private const val FAST_ROUTE_MIN_DISTANCE_M = 10.0
        private const val FAST_ROUTE_MIN_TIME_MS = 8_000L
        private const val FAST_CURVE_TURN_DEG = 15.0
        private const val FAST_CURVE_MIN_DISTANCE_M = 8.0

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
