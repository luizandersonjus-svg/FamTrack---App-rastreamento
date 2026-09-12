package com.famtrack.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Handler
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
import com.famtrack.app.data.observability.Telemetry
import com.famtrack.app.data.offline.RouteAnchor
import com.famtrack.app.data.offline.RoutePointStore
import com.famtrack.app.data.offline.RouteSyncCoordinator
import com.famtrack.app.data.offline.StoredPoint
import com.famtrack.app.data.remote.FamilyRepository
import com.famtrack.app.data.remote.GeofenceRepository
import com.famtrack.app.data.remote.LocationRepository
import com.famtrack.app.data.remote.NotificationRepository
import com.famtrack.app.data.remote.RouteSyncPermanentException
import com.famtrack.app.data.remote.RouteSyncRetriableException
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.data.remote.routeSyncErrorCode
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

    // ETAPA 8C — confirmação na borda: streak de fixes consecutivos discordando
    // do estado confirmado antes de emitir transição (evita falso positivo por
    // ruído GPS). In-memory (resetado ao reiniciar o serviço).
    private val boundaryStreak = mutableMapOf<String, Int>()

    // Última localização conhecida (usada pelo sensor de passos)
    private var lastKnownLocation: Location? = null

    // Heartbeat de fix: último timestamp em que o FLP entregou um fix
    // (System.currentTimeMillis). O Handler periódico detecta silêncio do GPS
    // (Doze/One UI) e reinicia o contrato do FusedLocationProvider.
    private var lastFixTime = 0L

    // Flags de diagnóstico do heartbeat (evitam repetir log a cada 60s).
    private var fixHeartbeatAttempted = false
    private var fixSilentWarned = false
    private var fixDozeLogged = false

    // ETAPA 3 — bateria: âncora do feed ao vivo (evita enviar a cada fix)
    private var lastFeedLat: Double? = null
    private var lastFeedLng: Double? = null
    private var lastFeedTime = 0L

    // ETAPA 3 — bateria: detecção de parado (alterna FLP denso x econômico)
    private var stationarySince = 0L
    private var isStationary = false

    private val fixHeartbeatHandler by lazy { Handler(Looper.getMainLooper()) }
    private val fixHeartbeatRunnable = Runnable { checkFixHeartbeat() }

    // Amostragem do histórico de rota (Fase 1): último ponto gravado em memória
    // (espelho da âncora persistente em RoutePointStore)
    private var lastRouteLat: Double? = null
    private var lastRouteLng: Double? = null
    private var lastRouteTime: Long = 0L
    private var lastRouteBearing: Float = 0f
    private var hasLastRouteBearing = false

    // Fila offline durável + âncora persistente
    private val routeStore by lazy { RoutePointStore(this) }

    // Ao voltar de "compartilhamento pausado", o próximo fix vira novo primeiro ponto
    private var resumeFromPause = false

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
                startFg()
                return
            }
            trackingStarted = true
            // Foreground obrigatório ANTES de qualquer operação de localização/rede.
            startFg()
            // ETAPA 7 — observabilidade: batimento reforçado ao iniciar o tracking.
            Telemetry.heartbeat(applicationContext)
            // Restaura a âncora persistida para o usuário/família atuais (sobrevive
            // a restart do processo) e agenda a drenagem de pontos remanescentes.
            restoreRouteAnchor()
            enqueueRouteSync(applicationContext)
            enqueuePeriodicRouteSync(applicationContext)
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

    @Suppress("DEPRECATION")
    private fun startFg() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
        val locationRequest = buildLocationRequest()

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
                    Log.d(FAM_TRACK_ROUTE_TAG, "fix recebido")
                    // ETAPA 7 — observabilidade: batimento persistente a cada fix
                    // (prova de vida da cadeia usado pelo watchdog do HealthCheckWorker).
                    Telemetry.registerFix(applicationContext)
                    lastFixTime = System.currentTimeMillis()
                    fixHeartbeatAttempted = false
                    fixSilentWarned = false
                    fixDozeLogged = false
result.lastLocation?.let { location ->
                        lastKnownLocation = location
                        // ETAPA 3 — bateria: alterna cadência GPS (parado x em movimento)
                        updateAdaptiveMode(location)
                        // Política de privacidade: pausado, nada é enviado ao backend.
                        if (isSharingPaused()) {
                            resumeFromPause = true
                            Log.d(FAM_TRACK_ROUTE_TAG, "ponto ignorado: compartilhamento pausado")
                        } else {
                            // Ao sair da pausa, o próximo fix vira novo primeiro ponto
                            // (âncora antiga invalidadas para não "puxar" o novo trajeto).
                            if (resumeFromPause) {
                                resumeFromPause = false
                                lastRouteLat = null
                                lastRouteLng = null
                                lastRouteTime = 0L
                                hasLastRouteBearing = false
                                routeStore.invalidateAnchor()
                            }
                            // Caminhos INDEPENDENTES: falha no feed ao vivo (locations) não
                            // impede a gravação em route_history e vice-versa — cada um tem
                            // try/catch próprio em sua própria coroutine.
                            sendLocationToSupabase(location)
                            saveRoutePoint(location)
                            checkGeofences(location)
                        }
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
            lastFixTime = System.currentTimeMillis()
            fixHeartbeatAttempted = false
            fixSilentWarned = false
            fixDozeLogged = false
            startFixHeartbeat()
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
        stopFixHeartbeat()
    }

    /**
     * Contrato FLP do modo atual (ETAPA 3 — bateria). Parado: econômico
     * (BALANCED, 30s, 30m, sem esperar fix preciso); em movimento: denso
     * (HIGH_ACCURACY, 3s, 5m, esperando fix preciso). Reutilizado na
     * (re)registração normal e no heartbeat de fix.
     */
    private fun buildLocationRequest(): LocationRequest {
        if (isStationary) {
            return LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                STILL_UPDATE_INTERVAL_MS
            ).apply {
                setMinUpdateDistanceMeters(STILL_MIN_DISTANCE_METERS)
                setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                setWaitForAccurateLocation(false)
            }.build()
        }
        return LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MS
        ).apply {
            setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
            setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            setWaitForAccurateLocation(true)
        }.build()
    }

    /** Re-registra o FLP com os parâmetros do modo atual (parado/em movimento). */
    private fun applyAdaptiveRequest() {
        val callback = locationCallback ?: return
        try {
            locationClient.removeLocationUpdates(callback)
            locationClient.requestLocationUpdates(
                buildLocationRequest(),
                callback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Sem permissão ao alternar cadência FLP", e)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao alternar cadência FLP", e)
        }
    }

    /** O fix atual indica movimento (velocidade reportada ou deslocamento real). */
    private fun isMoving(location: Location): Boolean {
        if (location.hasSpeed() && location.speed > ADAPTIVE_STILL_SPEED_MS) return true
        val lat = lastFeedLat ?: return false
        val lng = lastFeedLng ?: return false
        val dist = FloatArray(1)
        Location.distanceBetween(lat, lng, location.latitude, location.longitude, dist)
        return dist[0] > ADAPTIVE_STILL_RADIUS_M
    }

    /**
     * Alterna entre cadência densa e econômica: entra em modo parado após
     * [ADAPTIVE_STILL_WINDOW_MS] sem movimento e volta ao modo denso no
     * primeiro fix com movimento. O FLP é (re)registrado apenas nas transições.
     */
    private fun updateAdaptiveMode(location: Location) {
        if (isMoving(location)) {
            if (isStationary) {
                isStationary = false
                stationarySince = 0L
                applyAdaptiveRequest()
            }
            return
        }
        val now = location.time
        if (stationarySince == 0L) {
            stationarySince = now
            return
        }
        if (!isStationary && now - stationarySince >= ADAPTIVE_STILL_WINDOW_MS) {
            isStationary = true
            applyAdaptiveRequest()
        }
    }

    /**
     * Liga o Heartbeat de fix. A cada [HEARTBEAT_CHECK_MS] (60s) verifica se o
     * FLP voltou a entregar fixes:
     * - >120s sem fix: re-registra o FLP (remove + request com os mesmos
     *   parâmetros) para reviver o contrato do FusedLocationProvider após o
     *   Doze/One UI ter suspendido as entregas em background;
     * - >180s sem fix: registra o GPS silenciado no log (serviço permanece
     *   ativo, apenas informativo);
     * - >240s sem fix: sinaliza possível Doze. NUNCA para o serviço, NUNCA
     *   remove a notificação/FGS (type location) nem o callback.
     */
    private fun checkFixHeartbeat() {
        if (!trackingStarted) return
        val now = System.currentTimeMillis()
        val since = now - lastFixTime
        val callback = locationCallback ?: return

        // (A) >120s sem fix: re-registra o FLP uma única vez por ciclo de
        // silêncio (os flags são resetados quando um novo fix chega).
        if (since >= HEARTBEAT_NUDGE_MS && !fixHeartbeatAttempted) {
            fixHeartbeatAttempted = true
            Log.w(FAM_TRACK_ROUTE_TAG, "sem fix por ${since / 1000}s; re-registrando FLP")
            try {
                locationClient.removeLocationUpdates(callback)
                locationClient.requestLocationUpdates(
                    buildLocationRequest(),
                    callback,
                    Looper.getMainLooper()
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "Sem permissão ao re-registrar FLP no heartbeat", e)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao re-registrar FLP no heartbeat", e)
            }
        }

        // (C) >180s sem fix: log informativo único.
        if (since >= 180_000L && !fixSilentWarned) {
            fixSilentWarned = true
            Log.w(FAM_TRACK_ROUTE_TAG, "GPS silenciado por Doze — serviço ainda ativo")
        }

        // (A) >240s sem fix: sinaliza possível Doze (log único).
        if (since >= HEARTBEAT_SILENT_MS && !fixDozeLogged) {
            fixDozeLogged = true
            Log.w(FAM_TRACK_ROUTE_TAG, "sem fix após 240s, possível Doze")
        }

        fixHeartbeatHandler.postDelayed(fixHeartbeatRunnable, HEARTBEAT_CHECK_MS)
    }

    private fun startFixHeartbeat() {
        fixHeartbeatHandler.removeCallbacks(fixHeartbeatRunnable)
        fixHeartbeatHandler.postDelayed(fixHeartbeatRunnable, HEARTBEAT_CHECK_MS)
    }

    private fun stopFixHeartbeat() {
        fixHeartbeatHandler.removeCallbacks(fixHeartbeatRunnable)
    }

    private fun sendLocationToSupabase(location: Location) {
        if (isSharingPaused()) return

        val familyId = currentFamilyId ?: return
        val userId = currentUserId ?: return

        // ETAPA 3 — bateria: nem todo fix vira envio ao feed ao vivo. Envia quando
        // houve deslocamento >= FEED_MIN_DISTANCE_M desde o último envio ou quando
        // FEED_HEARTBEAT_MS decorreu (mantém lastUpdatedAt/bateria atualizados).
        if (!shouldSendToFeed(location)) return

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
                // Só conta como enviado após sucesso: falha de rede é reavaliada no
                // próximo fix sem descartar deslocamentos.
                markFeedSent(location)
                // ETAPA 7 — observabilidade: upsert de locations bem-sucedido
                // (atualiza também o último upsert, métrica da cadeia).
                Telemetry.registerUpsert(applicationContext, true)
            } catch (e: Exception) {
                // Falha de rede/Supabase: NÃO para o serviço; tenta de novo no próximo fix.
                Log.e(TAG, "Falha ao enviar localização ao Supabase", e)
                Telemetry.registerUpsert(applicationContext, false)
            }
        }
    }

    /** Decide se o fix atual merece ir ao feed ao vivo (distância ou tempo). */
    private fun shouldSendToFeed(location: Location): Boolean {
        val lastLat = lastFeedLat
        val lastLng = lastFeedLng
        if (lastLat == null || lastLng == null) return true
        val elapsed = location.time - lastFeedTime
        if (elapsed >= FEED_HEARTBEAT_MS) return true
        val dist = FloatArray(1)
        Location.distanceBetween(lastLat, lastLng, location.latitude, location.longitude, dist)
        return dist[0] >= FEED_MIN_DISTANCE_M
    }

    private fun markFeedSent(location: Location) {
        lastFeedLat = location.latitude
        lastFeedLng = location.longitude
        lastFeedTime = location.time
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
     * D) permanência: nada gravado há >= 60s (garante ao menos 1 ponto por
     *    minuto com fix válido, mesmo parado/movimento lento — fonte ininterrupta
     *    para o histórico);
     * E) fixes com accuracy > 150m só valem para D (nunca para forma da rota).
     */
    private fun shouldRecordRoutePoint(location: Location): Boolean {
        val lastLat = lastRouteLat
        val lastLng = lastRouteLng
        if (lastLat == null || lastLng == null) return true

        val elapsed = location.time - lastRouteTime
        val accuracy = location.accuracy
        // E) ignora jitter de GPS parado com precisão ruim (regras B/C); D sempre vale.
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
     * Grava um ponto no histórico de rota quando a amostragem decide. O ponto é
     * aceito na FILA LOCAL (persistente) — sucesso independe de internet. Em
     * seguida tenta sincronizar imediatamente e agenda o Worker se sobrar fila.
     * Falhas nunca param o serviço nem afetam o feed ao vivo.
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
        if (!shouldRecord) {
            Log.d(FAM_TRACK_ROUTE_TAG, "ponto ignorado por amostragem")
            return
        }

        serviceScope.launch {
            try {
                val rawBattery = (getSystemService(BATTERY_SERVICE) as? android.os.BatteryManager)
                    ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val batteryLevel = rawBattery?.takeIf { it in 0..100 }
                val stored = StoredPoint(
                    id = java.util.UUID.randomUUID().toString(),
                    familyId = familyId,
                    userId = userId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    recordedAt = java.time.Instant.ofEpochMilli(location.time).toString(),
                    accuracy = location.accuracy.takeIf { it > 0f },
                    speed = if (location.hasSpeed()) location.speed else null,
                    bearing = if (location.hasBearing()) location.bearing else null,
                    batteryLevel = batteryLevel,
                    provider = location.provider
                )
                routeStore.enqueue(stored)
                advanceRouteAnchor(familyId, userId, location)
                Log.d(FAM_TRACK_ROUTE_TAG, "ponto aceito na fila")
                try {
                    RouteSyncCoordinator.trySync(routeStore, locationRepository)
                    Telemetry.registerSync(true)
                } catch (e: RouteSyncRetriableException) {
                    Log.d(FAM_TRACK_ROUTE_TAG, "sincronização adiada: rede indisponível")
                    Telemetry.registerSync(false)
                    enqueueRouteSync(applicationContext)
                } catch (e: RouteSyncPermanentException) {
                    Log.w(FAM_TRACK_ROUTE_TAG, "falha permanente: ${routeSyncErrorCode(e)}")
                    Telemetry.registerSync(false)
                    enqueueRouteSync(applicationContext)
                }
                val pending = routeStore.pendingCountFor(userId)
                if (pending > 0) {
                    Log.d(FAM_TRACK_ROUTE_TAG, "fila pendente: quantidade=$pending")
                    enqueueRouteSync(applicationContext)
                }
            } catch (e: Exception) {
                Log.w(
                    FAM_TRACK_ROUTE_TAG,
                    "ponto não aceito na fila: ${shortMessage(e)}"
                )
            }
        }
    }

    /** Recarrega a âncora persistida do usuário/família atuais (sobrevive ao restart). */
    private fun restoreRouteAnchor() {
        val familyId = currentFamilyId ?: return
        val userId = currentUserId ?: return
        val anchor = routeStore.loadAnchor(familyId, userId)
        if (anchor != null) {
            lastRouteLat = anchor.latitude
            lastRouteLng = anchor.longitude
            lastRouteTime = anchor.timeMillis
            lastRouteBearing = anchor.bearing
            hasLastRouteBearing = anchor.hasBearing
            Log.d(FAM_TRACK_ROUTE_TAG, "âncora restaurada")
        } else {
            lastRouteLat = null
            lastRouteLng = null
            lastRouteTime = 0L
            hasLastRouteBearing = false
        }
    }

    /** Avança a âncora (memória + persistida) após o ponto ser aceito na fila. */
    private fun advanceRouteAnchor(familyId: String, userId: String, location: Location) {
        val firstPoint = lastRouteLat == null || lastRouteLng == null
        lastRouteLat = location.latitude
        lastRouteLng = location.longitude
        lastRouteTime = location.time
        lastRouteBearing = location.bearing
        hasLastRouteBearing = location.hasBearing()
        routeStore.saveAnchor(
            RouteAnchor(
                familyId = familyId,
                userId = userId,
                latitude = location.latitude,
                longitude = location.longitude,
                timeMillis = location.time,
                bearing = location.bearing,
                hasBearing = location.hasBearing()
            )
        )
        Log.d(
            FAM_TRACK_ROUTE_TAG,
            if (firstPoint) "ponto aceito na fila (início da rota)" else "ponto aceito na fila"
        )
    }

    /** Mensagem curta e sem dados sensíveis para logs de rota. */
    private fun shortMessage(e: Exception): String =
        e.localizedMessage
            ?.substringBefore('\n')
            ?.trim()
            ?.take(120)
            ?.takeIf { it.isNotBlank() }
            ?: "erro desconhecido"

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
                var transitions = 0

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

                    // ETAPA 8C — só transiciona após GEOFENCE_CONFIRM_FIXES fixes
                    // consecutivos discordando do estado confirmado (dwell curto).
                    val key = "${userId}_$geofenceId"
                    val streak = if (isInside == wasInside) {
                        boundaryStreak.remove(key)
                        0
                    } else {
                        val next = (boundaryStreak[key] ?: 0) + 1
                        boundaryStreak[key] = next
                        next
                    }
                    if (streak >= GEOFENCE_CONFIRM_FIXES) {
                        boundaryStreak.remove(key)
                        transitions += 1
                        val title = geofence.name
                        val message = if (isInside) "Chegou em: ${geofence.name}" else "Saiu de: ${geofence.name}"
                        sendGeofenceNotification(title, message, geofenceId.hashCode() + if (isInside) 0 else 1)
                        insertGeofenceNotification(familyId, userId, title, message)
                        updateGeofenceState(userId, geofenceId, isInside)
                    }
                }

                // ETAPA 7 — observabilidade: transições de geofence avaliadas.
                Telemetry.registerGeofence(transitions)
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
        fixHeartbeatHandler.removeCallbacks(fixHeartbeatRunnable)
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
        private const val FAM_TRACK_ROUTE_TAG = "FamTrackRoute"
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

        // ETAPA 8C — dwell do próprio usuário: número de fixes consecutivos
        // discordando do estado confirmado antes de emitir a transição.
        private const val GEOFENCE_CONFIRM_FIXES = 2

        // ETAPA 3 — bateria: cadência adaptativa do GPS (parado x em movimento)
        // Velocidade > este valor (m/s) já conta como movimento imediato.
        private const val ADAPTIVE_STILL_SPEED_MS = 1.0
        // Tempo parado (sem movimento acima de ADAPTIVE_STILL_RADIUS_M) para
        // entrar no modo econômico.
        private const val ADAPTIVE_STILL_WINDOW_MS = 60_000L
        // Deslocamento desde o último envio que destrava o modo denso.
        private const val ADAPTIVE_STILL_RADIUS_M = 20.0
        // Contrato FLP do modo parado: intervalo e distância mínima maiores,
        // prioridade BALANCED, sem espera de fix preciso (radar desligado).
        private const val STILL_UPDATE_INTERVAL_MS = 30_000L
        private const val STILL_MIN_DISTANCE_METERS = 30f

        // ETAPA 3 — bateria: envio ao feed ao vivo não é a cada fix.
        private const val FEED_MIN_DISTANCE_M = 15.0
        private const val FEED_HEARTBEAT_MS = 45_000L

        // Heartbeat de fix (Doze/One UI): após 2min sem fix re-registra o FLP;
        // após 4min sinaliza no log que o GPS está silenciado. O serviço nunca é
        // parado. A notificação (FGS) permanece, preservando o type location.
        private const val HEARTBEAT_CHECK_MS = 60_000L
        private const val HEARTBEAT_NUDGE_MS = 120_000L
        private const val HEARTBEAT_SILENT_MS = 240_000L

        // Amostragem do histórico de rota (Fase 1)
        private const val ROUTE_MIN_DISTANCE_FLOOR_M = 20.0
        // Accuracy máxima para valer nas regras de forma da rota; acima disso o
        // ponto só é gravado pela regra de permanência (heartbeat em 60s).
        private const val ROUTE_MAX_ACCURACY_M = 150.0
        private const val ROUTE_MIN_TIME_MS = 20_000L
        private const val ROUTE_CURVE_TURN_DEG = 25.0
        private const val ROUTE_CURVE_MIN_DISTANCE_M = 5.0
        private const val ROUTE_CURVE_MIN_TIME_MS = 5_000L
        private const val ROUTE_CURVE_MIN_SPEED_MS = 1.0
        // Garante ao menos 1 ponto por minuto com fix válido (também parado/lento).
        private const val ROUTE_HEARTBEAT_MS = 60_000L

        // Limiares densos quando em movimento (velocidade > 1 m/s): >= 12s e >= 10m
        // para deslocamento; curva > 25° com >= 5m para registrar viradas.
        private const val ROUTE_MOVING_SPEED_MS = 1.0
        private const val FAST_ROUTE_MIN_DISTANCE_M = 10.0
        private const val FAST_ROUTE_MIN_TIME_MS = 12_000L
        private const val FAST_CURVE_TURN_DEG = 25.0
        private const val FAST_CURVE_MIN_DISTANCE_M = 5.0

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

        /** Últimas ids persistidas (sobrevivem a reboot/matança do processo). */
        fun lastPersistedIds(context: Context): Pair<String, String>? {
            val prefs = context.getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE)
            val fid = prefs.getString(KEY_FAMILY_ID, null) ?: return null
            val uid = prefs.getString(KEY_USER_ID, null) ?: return null
            return fid to uid
        }

        fun locationPermissionGranted(context: Context): Boolean {
            val ctx = context.applicationContext
            return ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
