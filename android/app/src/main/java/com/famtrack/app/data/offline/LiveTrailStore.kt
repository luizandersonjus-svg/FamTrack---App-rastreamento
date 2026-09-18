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
            // Ordem cronológica: ponto mais antigo que o último aceito é eco
            // atrasado do Realtime ou reordenação da entrega; ligá-lo depois do
            // último criaria um zigue-zague atravessando o mapa.
            if (anchor != null && recordedAt < anchor.recordedAt) {
                Log.d(TAG, "ponto fora de ordem descartado: user=$userId")
                return
            }
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

    /**
     * Hidratação (TRACK-1d): reconcilia o rastro de um membro com os pontos
     * vindos do servidor (route_history do dia). O rastro de membros vinha só
     * do Realtime da tabela `locations` (acumulava apenas com o app aberto);
     * aqui o dia inteiro é recuperado do servidor, que é alimentado em
     * background pelo próprio aparelho do membro.
     *
     * Regra de segurança: só substitui quando o servidor conhece MAIS pontos
     * que a memória — assim a hidratação cobre o período com app fechado sem
     * regredir/apagar o que o Realtime já trouxe. Re-decima do zero para
     * manter a mesma pipeline de qualidade e o teto por membro.
     */
    fun seedMemberTrail(userId: String, points: List<TrailPoint>) {
        if (appContext == null) return
        synchronized(lock) {
            ensureLoadedLocked()
            if (currentDay != today()) rolloverLocked()

            val valid = points
                .filter { it.userId == userId && RouteQuality.isAccurate(it.accuracy) }
                .sortedBy { it.recordedAt }
            if (valid.isEmpty()) return

            val existing = trails[userId] ?: emptyList()
            if (valid.size <= existing.size) {
                Log.d(TAG, "backfill ignorado (memoria>=servidor): user=$userId servidor=${valid.size} memoria=${existing.size}")
                return
            }

            val rebuilt = decimate(valid)
            trails[userId] = rebuilt.toMutableList()
            lastPoint[userId] = rebuilt.last()
            Log.i(TAG, "backfill aplicado: user=$userId servidor=${valid.size} memoria=${existing.size} para=${rebuilt.size}")
            version++
            maybePersistLocked()
        }
    }

    /**
     * Cópia atual do rastro (dia atual) já em segmentos desenháveis. Os pontos
     * são ordenados por timestamp e divididos em trechos contínuos: nunca se
     * desenha uma reta entre dois pontos separados por um salto implausível
     * (velocidade impossível ou lacuna temporal longa). Usada pelo desenho
     * (TRACK-1b/1c) e pelo backfill (TRACK-1d).
     */
    fun snapshot(): Map<String, List<List<TrailPoint>>> = synchronized(lock) {
        ensureLoadedLocked()
        if (currentDay != today()) rolloverLocked()
        trails.mapValues { (_, points) -> segment(points) }
    }

    // ----------------------------------------------------------------------
    // Internos
    // ----------------------------------------------------------------------

    private fun today(): String = LocalDate.now().toString()

    /**
     * Ordena os pontos por timestamp e divide em trechos contínuos, quebrando
     * sempre que o passo entre dois consecutivos for implausível ([isTrailGap]).
     * Trechos com menos de 2 pontos não são desenháveis e ficam de fora.
     */
    private fun segment(points: List<TrailPoint>): List<List<TrailPoint>> {
        if (points.size < 2) return listOf(points)
        val sorted = points.sortedBy { it.recordedAt }
        val segments = mutableListOf<List<TrailPoint>>()
        var current = mutableListOf<TrailPoint>()
        for (p in sorted) {
            val last = current.lastOrNull()
            if (last != null && isTrailGap(last, p)) {
                if (current.size >= 2) segments += current
                current = mutableListOf()
            }
            current.add(p)
        }
        if (current.size >= 2) segments += current
        return segments
    }

    /**
     * true quando NÃO se deve ligar a -> b: timestamp fora de ordem, velocidade
     * implícita acima do teto humano/veicular (salto de GPS) ou deslocamento
     * grande durante uma lacuna temporal longa (coleta cega). A lacuna só
     * quebra quando é longa E o salto de distância também é grande — assim
     * paradas reais (gaps de minutos com deriva de poucos metros) mantêm o
     * traço contínuo, sem fragmentá-lo em dezenas de pedaços.
     */
    private fun isTrailGap(a: TrailPoint, b: TrailPoint): Boolean {
        val dt = b.recordedAt - a.recordedAt
        if (dt < 0) return true
        val dist = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, dist)
        if (dt > RouteQuality.TRAIL_GAP_MAX_DT_MILLIS &&
            dist[0] > RouteQuality.TRAIL_GAP_MAX_DIST_METERS
        ) {
            return true
        }
        if (dt < RouteQuality.TRAIL_MIN_DT_FOR_SPEED_MS) return false
        val kmh = (dist[0] / (dt / 1000.0)) * 3.6
        return kmh > RouteQuality.TRAIL_MAX_IMPLIED_KMH
    }

    /**
     * Re-decimação do zero (usada pelo backfill, TRACK-1d): mantém o primeiro
     * ponto e só aceita os seguintes a [RouteQuality.TRAIL_MIN_DISTANCE_M] ou
     * mais do último mantido, aplicando o teto da janela deslizante no fim.
     */
    private fun decimate(points: List<TrailPoint>): List<TrailPoint> {
        val out = mutableListOf<TrailPoint>()
        for (p in points) {
            val last = out.lastOrNull()
            if (last != null) {
                val dist = FloatArray(1)
                Location.distanceBetween(
                    last.latitude,
                    last.longitude,
                    p.latitude,
                    p.longitude,
                    dist
                )
                if (dist[0] < RouteQuality.TRAIL_MIN_DISTANCE_M) continue
            }
            out.add(p)
        }
        val max = RouteQuality.TRAIL_MAX_POINTS_PER_MEMBER
        return if (out.size > max) out.drop(out.size - max) else out
    }

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