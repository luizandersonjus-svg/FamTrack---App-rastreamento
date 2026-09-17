package com.famtrack.app.data.offline

import android.content.Context
import android.location.Location
import android.util.Log
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "FamTrackLiveTrail"
private const val SYNC_TAG = "FamTrackLiveTrailSync"
private const val PREFS_NAME = "live_trail_store"
private const val KEY_DAY = "day"
private const val KEY_POINTS = "points"
private const val PERSIST_THROTTLE_MS = 15_000L

/**
 * Ponto do rastro ao vivo de um membro (TRACK-1a). [recordedAt] é epoch
 * millis do momento do fix (ou do `last_updated_at` no caso do Realtime).
 */
@Serializable
internal data class TrailPoint(
    val userId: String,
    val latitude: Double,
    val longitude: Double,
    val recordedAt: Long,
    val accuracy: Float?,
    val speed: Float?
)

/**
 * Acumulador do rastro ao vivo por membro (TRACK-1a). Sem UI nesta etapa —
 * a estrutura de dados e a persistência são o alvo; o desenho vem em 1b/1c.
 *
 * Regras (escopo definido):
 * - Filtra fix por precisão ([RouteQuality.TRAIL_MAX_ACCURACY_M]) — descarta
 *   GPS ruim;
 * - Decima por distância (~[RouteQuality.TRAIL_MIN_DISTANCE_M]) — não acumula
 *   pontos redundantes parado;
 * - Teto de [RouteQuality.TRAIL_MAX_POINTS_PER_MEMBER] por membro — janela
 *   deslizante que descarta o mais antigo;
 * - Rollover automático à meia-noite — ao detectar mudança de dia local, limpa
 *   o rastro em memória e recomeça (check lazy a cada ponto/inicialização);
 * - Persistência JSON por dia em SharedPreferences — o rastro do dia atual
 *   sobrevive a fechar/reabrir o app; o arquivo é substituído a cada rollover.
 *
 * Fontes de alimentação (reutilizadas, sem provedor novo):
 * - Self: fixes do FusedLocationProvider no LocationService.
 * - Membros: canal Realtime da tabela `locations` coletado no HomeScreen.
 *
 * Escrita: em memória é sincronizada e rápida (qualquer thread); a
 * persistência roda num scope IO do próprio store, com throttle para não
 * martelar o disco a cada fix.
 */
internal object LiveTrailStore {

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var appContext: Context? = null

    // user_id -> pontos aceitos do dia atual (em ordem de chegada).
    private val trails = mutableMapOf<String, MutableList<TrailPoint>>()

    // user_id -> último ponto aceito (âncora da decimação por distância).
    private val lastPoint = mutableMapOf<String, TrailPoint>()

    private var currentDay: String? = null
    private var loaded = false
    private var lastPersistMs = 0L
    private val persistInFlight = AtomicBoolean(false)

    // Monotônico: incrementa a cada mudança visível do rastro (ponto aceito,
    // limpeza ou rollover). A UI consulta [trailVersion] para redesenhar.
    @Volatile
    private var version = 0

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Chamado no onCreate do Application (single ponto de inicialização). */
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
    }

    /** Versão atual do rastro em memória; muda quando há conteúdo novo. */
    val trailVersion: Int
        get() = version

    /** Apaga o rastro do dia atual (memória + persistência) e retorna. */
    fun clearToday() {
        if (appContext == null) return
        synchronized(lock) {
            ensureLoadedLocked()
            val before = trails.values.sumOf { it.size }
            trails.clear()
            lastPoint.clear()
            version++
            lastPersistMs = 0L
            Log.i(TAG, "rastro limpo (dia=${currentDay} pontos=$before)")
            maybePersistLocked()
        }
    }

    /**
     * Tenta acumular um ponto no rastro do membro. Thread-safe: pode ser
     * chamada da main (Realtime/Compose) ou de IO (LocationService).
     */
    fun addPoint(
        userId: String,
        latitude: Double,
        longitude: Double,
        recordedAt: Long,
        accuracy: Float?,
        speed: Float?
    ) {
        if (appContext == null) return
        synchronized(lock) {
            ensureLoadedLocked()
            if (currentDay != today()) rolloverLocked()

            if (!RouteQuality.isAccurate(accuracy)) {
                Log.d(TAG, "ponto descartado por precisão: user=$userId accuracy=${accuracy ?: "?"}")
                return
            }

            val anchor = lastPoint[userId]
            if (anchor != null) {
                val dist = FloatArray(1)
                Location.distanceBetween(
                    anchor.latitude,
                    anchor.longitude,
                    latitude,
                    longitude,
                    dist
                )
                if (dist[0] < RouteQuality.TRAIL_MIN_DISTANCE_M) {
                    Log.d(TAG, "ponto ignorado (parado/duplicado): user=$userId")
                    return
                }
            }

            val list = trails.getOrPut(userId) { mutableListOf() }
            val point = TrailPoint(userId, latitude, longitude, recordedAt, accuracy, speed)
            list.add(point)
            lastPoint[userId] = point
            if (list.size > RouteQuality.TRAIL_MAX_POINTS_PER_MEMBER) {
                list.removeAt(0)
            }

            Log.d(TAG, "ponto aceito: user=$userId total=${list.size} acc=${accuracy ?: "?"}")
            version++
            maybePersistLocked()
        }
    }

    /** Cópia atual do rastro (dia atual). Usada pelo desenho em TRACK-1b/1c. */
    fun snapshot(): Map<String, List<TrailPoint>> = synchronized(lock) {
        ensureLoadedLocked()
        trails.mapValues { it.value.toList() }
    }

    // ----------------------------------------------------------------------
    // Internos
    // ----------------------------------------------------------------------

    private fun today(): String = LocalDate.now().toString()

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val ctx = appContext
        if (ctx == null) return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val day = prefs.getString(KEY_DAY, null)
        val raw = prefs.getString(KEY_POINTS, null)

        if (day == today() && raw != null) {
            try {
                val points = json.decodeFromString<List<TrailPoint>>(raw)
                for (p in points) {
                    val list = trails.getOrPut(p.userId) { mutableListOf() }
                    list.add(p)
                    if (list.size > RouteQuality.TRAIL_MAX_POINTS_PER_MEMBER) {
                        list.removeAt(0)
                    }
                }
                for ((uid, list) in trails) {
                    lastPoint[uid] = list.last()
                }
                Log.i(TAG, "rastro restaurado: dia=$day pontos=${points.size} membros=${trails.size}")
            } catch (e: Exception) {
                Log.w(TAG, "rastro persistido ilegível; recomeçando vazio: ${e.javaClass.simpleName}")
            }
        } else if (day != null) {
            Log.i(TAG, "dia anterior detectado ($day): rastro antigo descartado")
        }
        currentDay = today()
        Log.i(TAG, "rastro pronto: dia=${currentDay} membros=${trails.keys.joinToString()}")
    }

    private fun rolloverLocked() {
        val old = currentDay
        val newDay = today()
        currentDay = newDay
        trails.clear()
        lastPoint.clear()
        lastPersistMs = 0L
        version++
        Log.i(TAG, "rollover de dia: $old -> $newDay (rastro limpo)")
        maybePersistLocked()
    }

    /** Persiste (throttled) uma foto do rastro do dia atual nas preferências. */
    private fun maybePersistLocked() {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (now - lastPersistMs < PERSIST_THROTTLE_MS) return
        lastPersistMs = now
        val day = currentDay ?: today()
        if (!persistInFlight.compareAndSet(false, true)) return

        persistScope.launch {
            try {
                val flat = synchronized(lock) { trails.values.flatten() }
                val snapshot = json.encodeToString(flat)
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_DAY, day)
                    .putString(KEY_POINTS, snapshot)
                    .apply()
                Log.d(SYNC_TAG, "persistido: dia=$day pontos=${flat.size} membros=${flat.distinctBy { it.userId }.size}")
            } catch (e: Exception) {
                Log.w(TAG, "persistência falhou: ${e.localizedMessage}")
            } finally {
                persistInFlight.set(false)
            }
        }
    }
}