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

    /** Estado de movimento do membro (máquina de estados por usuário). */
    private enum class MotionState { STATIONARY, MOVING }

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var appContext: Context? = null

    // user_id -> pontos aceitos do dia atual (em ordem de chegada).
    private val trails = mutableMapOf<String, MutableList<TrailPoint>>()

    // user_id -> último ponto aceito (âncora da decimação por distância).
    private val lastPoint = mutableMapOf<String, TrailPoint>()

    // Máquina de estados de movimento (CORR-X): user_id -> estado atual.
    private val motionStates = mutableMapOf<String, MotionState>()

    // user_id -> ponto-âncora (último local estável; de onde partiu o trecho
    // atual). Fica congelado enquanto STATIONARY e NÃO segue o tremor de GPS.
    private val anchors = mutableMapOf<String, TrailPoint>()

    // user_id -> instante (wall clock) em que o membro foi CONFIRMADO parado
    // (2 min dentro do raio). Base do auto-hide de 5 min.
    private val stationarySinceWall = mutableMapOf<String, Long>()

    // user_id -> ponto que iniciou a janela de confirmação MOVING -> STATIONARY.
    private val stationaryCandidatePoint = mutableMapOf<String, TrailPoint>()

    // user_id -> instante (wall clock) de início da janela de confirmação.
    private val stationaryCandidateStart = mutableMapOf<String, Long>()

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
            resetMotionLocked()
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

            // Ocultação por tempo de parede: corre a cada fix (não só no
            // snapshot, que a UI só consulta quando a versão muda). Sem um fix
            // novo o cronômetro sozinho não dispararia o redesenho.
            autoHideSweepLocked()

            if (!RouteQuality.isAccurate(accuracy)) {
                Log.d(TAG, "ponto descartado por precisão: user=$userId accuracy=${accuracy ?: "?"}")
                return
            }

            val last = lastPoint[userId]
            // Ordem cronológica: ponto mais antigo que o último aceito é eco
            // atrasado do Realtime ou reordenação da entrega; ligá-lo depois do
            // último criaria um zigue-zague atravessando o mapa.
            if (last != null && recordedAt < last.recordedAt) {
                Log.d(TAG, "ponto fora de ordem descartado: user=$userId")
                return
            }

            val now = System.currentTimeMillis()

            // ------------------------------------------------------------------
            // Máquina de estados (CORR-X): enquanto STATIONARY o rastro não
            // acumula NADA — o tremor de GPS de quem está parado é descartado.
            // Só ao cruzar [TRAIL_MOVEMENT_START_THRESHOLD_M] de deslocamento em
            // relação ao ponto-âncora o membro é considerado em movimento e o
            // trecho começa a ser desenhado a partir da própria âncora.
            // ------------------------------------------------------------------
            val state = motionStates[userId] ?: MotionState.STATIONARY
            if (state == MotionState.STATIONARY) {
                val anchor = anchors[userId] ?: last
                if (anchor == null) {
                    // Primeiro contato do membro: o fix vira o ponto-âncora, mas
                    // não entra no rastro (pode ser o tremor do local de parada).
                    val first = TrailPoint(userId, latitude, longitude, recordedAt, accuracy, speed)
                    anchors[userId] = first
                    lastPoint[userId] = first
                    Log.i(TAG, "âncora inicial (aguardando movimento): user=$userId")
                    return
                }
                val dist = distanceMetersLocked(anchor.latitude, anchor.longitude, latitude, longitude)
                if (dist < RouteQuality.TRAIL_MOVEMENT_START_THRESHOLD_M) {
                    Log.d(TAG, "parado sem movimento (tremor): user=$userId dist=${"%.1f".format(dist)}m")
                    return
                }
                // Começou a se mover: transiciona e acumula partindo da âncora
                // (o novo trecho COMEÇA no local estável — sem buraco visual).
                motionStates[userId] = MotionState.MOVING
                // Cancela o cronômetro do auto-hide anterior: a contagem de
                // "5 min parado" só vale para o STATIONARY recém-confirmado.
                stationarySinceWall.remove(userId)
                stationaryCandidatePoint.remove(userId)
                stationaryCandidateStart.remove(userId)
                if (trails[userId].isNullOrEmpty()) {
                    // Trecho novo do zero: a âncora é o primeiro ponto.
                    appendPointRawLocked(anchor)
                }
                appendPointRawLocked(
                    TrailPoint(userId, latitude, longitude, recordedAt, accuracy, speed)
                )
                Log.i(TAG, "movimento iniciado: user=$userId")
                version++
                maybePersistLocked()
                return
            }

            // ------------------------------------------------------------------
            // MOVING: pelo o  pipeline de qualidade original + confirmação de
            // parada (ficou dentro do raio por tempo suficiente -> STATIONARY).
            // ------------------------------------------------------------------
            if (last != null) {
                val dist = distanceMetersLocked(last.latitude, last.longitude, latitude, longitude)
                if (dist < RouteQuality.TRAIL_MIN_DISTANCE_M) {
                    Log.d(TAG, "ponto ignorado (parado/duplicado): user=$userId")
                    return
                }
            }

            val point = TrailPoint(userId, latitude, longitude, recordedAt, accuracy, speed)
            appendPointRawLocked(point)
            confirmStopLocked(userId, point, now)

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

            // O histórico do servidor (dia inteiro) passa PELA MESMA máquina de
            // estados de movimento do Realtime: tremor de quem ficou parado não
            // vira trecho, e trechos concluídos (5 min parado) ficam ocultos.
            val gate = applyMovementGateLocked(valid)
            val rebuilt = decimate(gate.kept)
            trails[userId] = rebuilt.toMutableList()
            if (rebuilt.isNotEmpty()) {
                lastPoint[userId] = rebuilt.last()
            }
            // Reflete o estado final do replay na máquina em tempo real.
            val anchorFinal = (if (gate.state == MotionState.MOVING) rebuilt.lastOrNull() else gate.anchor)
                ?: rebuilt.lastOrNull()
                ?: lastPoint[userId]
            if (gate.state == MotionState.MOVING) {
                motionStates[userId] = MotionState.MOVING
                stationaryCandidatePoint.remove(userId)
                stationaryCandidateStart.remove(userId)
                anchorFinal?.let { anchors[userId] = it }
            } else {
                motionStates[userId] = MotionState.STATIONARY
                anchorFinal?.let { anchors[userId] = it }
                // Daqui em diante vale o auto-hide real (5 min por parede):
                // os fixes novos confirmam a parada enquanto ela durar.
                stationarySinceWall[userId] = System.currentTimeMillis()
                stationaryCandidatePoint.remove(userId)
                stationaryCandidateStart.remove(userId)
            }
            Log.i(
                TAG,
                "backfill aplicado: user=$userId servidor=${valid.size} memoria=${existing.size} para=${rebuilt.size} estado=${gate.state}"
            )
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
        // Ocultação por tempo decorrido precisa rodar também no redraw: o
        // cronômetro do auto-hide pode vencer sem que um novo fix chegue.
        autoHideSweepLocked()
        trails.mapValues { (_, points) -> segment(points) }
    }

    // ----------------------------------------------------------------------
    // Internos
    // ----------------------------------------------------------------------

    private fun today(): String = LocalDate.now().toString()

    /** Anexa o ponto à lista do membro (janela deslizante até o teto). */
    private fun appendPointRawLocked(point: TrailPoint) {
        val list = trails.getOrPut(point.userId) { mutableListOf() }
        list.add(point)
        lastPoint[point.userId] = point
        if (list.size > RouteQuality.TRAIL_MAX_POINTS_PER_MEMBER) {
            list.removeAt(0)
        }
        Log.d(TAG, "ponto aceito: user=${point.userId} total=${list.size} acc=${point.accuracy ?: "?"}")
    }

    /**
     * Janela de confirmação MOVING -> STATIONARY (CORR-X): enquanto MOVING, se
     * os fixes permanecerem dentro de [RouteQuality.TRAIL_STATIONARY_CONFIRM_RADIUS_M]
     * do ponto que iniciou a janela por [RouteQuality.TRAIL_STATIONARY_CONFIRM_DURATION_MS],
     * o membro é confirmado parado e a âncora passa a ser o local de parada.
     * Se um fix sai do raio, a janela reinicia daquele ponto — ainda se move.
     * Mede em relação ao 1º fix da janela (não ao mais recente) para que uma
     * caminhada lenta com passos curtos não seja interpretada como parada.
     */
    private fun confirmStopLocked(userId: String, point: TrailPoint, now: Long) {
        val first = stationaryCandidatePoint[userId]
        if (first == null) {
            stationaryCandidatePoint[userId] = point
            stationaryCandidateStart[userId] = now
            return
        }
        val dist = distanceMetersLocked(first, point)
        if (dist > RouteQuality.TRAIL_STATIONARY_CONFIRM_RADIUS_M) {
            stationaryCandidatePoint[userId] = point
            stationaryCandidateStart[userId] = now
            return
        }
        val since = stationaryCandidateStart[userId] ?: now
        if (now - since < RouteQuality.TRAIL_STATIONARY_CONFIRM_DURATION_MS) return
        motionStates[userId] = MotionState.STATIONARY
        anchors[userId] = lastPoint[userId] ?: point
        stationarySinceWall[userId] = now
        stationaryCandidatePoint.remove(userId)
        stationaryCandidateStart.remove(userId)
        Log.i(
            TAG,
            "parada confirmada (${RouteQuality.TRAIL_STATIONARY_CONFIRM_DURATION_MS / 1000}s sem deslocamento): user=$userId"
        )
    }

    /**
     * Ocultação automática do trecho concluído (CORR-X): após o membro ser
     * confirmado parado e permanecer [RouteQuality.TRAIL_AUTO_HIDE_AFTER_STATIONARY_MS]
     * sem se mover, o trecho que ele acabou de desenhar SOME do mapa. A âncora
     * permanece: um movimento futuro parte dela e monta um trecho novo do zero.
     * Roda no addPoint (via confirmação) e no snapshot (por tempo de parede).
     */
    private fun autoHideSweepLocked() {
        val now = System.currentTimeMillis()
        for ((uid, since) in stationarySinceWall.toList()) {
            if (now - since < RouteQuality.TRAIL_AUTO_HIDE_AFTER_STATIONARY_MS) continue
            stationarySinceWall.remove(uid)
            val list = trails.remove(uid)
            if (list.isNullOrEmpty()) continue
            lastPoint.remove(uid)
            version++
            maybePersistLocked()
            Log.i(TAG, "trecho oculto (5 min parado): user=$uid pontos=${list.size}")
        }
    }

    /** Redefine toda a máquina de estados de movimento (rollover/limpeza). */
    private fun resetMotionLocked() {
        motionStates.clear()
        anchors.clear()
        stationarySinceWall.clear()
        stationaryCandidatePoint.clear()
        stationaryCandidateStart.clear()
    }

    /** Resultado do replay da máquina de estados sobre pontos históricos. */
    private data class MovementGateResult(
        val kept: List<TrailPoint>,
        val state: MotionState,
        val anchor: TrailPoint?
    )

    /**
     * Replay da máquina de estados de movimento (CORR-X) sobre uma sequência
     * ORDENADA por [TrailPoint.recordedAt] (histórico do servidor, dia inteiro).
     * Tem as mesmas regras do Realtime [addPoint]:
     *  - STATIONARY: descarta pontos dentro de [RouteQuality.TRAIL_MOVEMENT_START_THRESHOLD_M]
     *    da âncora (tremor de quem está parado não vira trecho);
     *  - ao cruzar o limiar, MOVING e o trecho passa a acumular partindo da âncora;
     *  - MOVING confirma parada se permanecer dentro do raio por
     *    [RouteQuality.TRAIL_STATIONARY_CONFIRM_DURATION_MS] e, após
     *    [RouteQuality.TRAIL_AUTO_HIDE_AFTER_STATIONARY_MS] parado, o trecho
     *    concluído é descartado (queda de um dia com volta para casa não deixa
     *    lixo na casa).
     */
    private fun applyMovementGateLocked(points: List<TrailPoint>): MovementGateResult {
        val sorted = points.sortedBy { it.recordedAt }
        val kept = mutableListOf<TrailPoint>()
        var state = MotionState.STATIONARY
        var anchor: TrailPoint? = null
        var stopSince: Long? = null
        var movingAnchor: TrailPoint? = null
        var movingSince = 0L

        for (p in sorted) {
            if (state == MotionState.STATIONARY) {
                if (anchor == null) {
                    anchor = p
                    continue
                }
                // Auto-hide: já está parado há 5 min? O trecho anterior, se
                // houver, era de uma movimentação que terminou — some do mapa.
                val stop = stopSince
                if (stop != null && p.recordedAt - stop >= RouteQuality.TRAIL_AUTO_HIDE_AFTER_STATIONARY_MS) {
                    kept.clear()
                    stopSince = null
                }
                val d = distanceMetersLocked(anchor, p)
                if (d < RouteQuality.TRAIL_MOVEMENT_START_THRESHOLD_M) continue
                // Partiu de verdade: novo trecho começa na âncora.
                state = MotionState.MOVING
                stopSince = null
                if (kept.isEmpty()) kept += anchor
                kept += p
                movingAnchor = p
                movingSince = p.recordedAt
            } else {
                // MOVING: janela de confirmação de parada em relação ao 1º fix.
                val first = movingAnchor
                if (first != null) {
                    val dCand = distanceMetersLocked(first, p)
                    if (dCand <= RouteQuality.TRAIL_STATIONARY_CONFIRM_RADIUS_M) {
                        if (p.recordedAt - movingSince >= RouteQuality.TRAIL_STATIONARY_CONFIRM_DURATION_MS) {
                            // Parou: âncora passa a ser o local de parada.
                            state = MotionState.STATIONARY
                            anchor = p
                            stopSince = p.recordedAt
                            kept += p
                            continue
                        }
                        kept += p
                        continue
                    }
                }
                kept += p
                movingAnchor = p
                movingSince = p.recordedAt
            }
        }

        val lastKept = kept.lastOrNull()
        if (state == MotionState.STATIONARY && anchor == null) {
            anchor = lastKept
        }
        return MovementGateResult(kept, state, anchor)
    }

    /** Distância em metros (haversine via Location). */
    private fun distanceMetersLocked(a: TrailPoint, b: TrailPoint): Float {
        return distanceMetersLocked(a.latitude, a.longitude, b.latitude, b.longitude)
    }

    private fun distanceMetersLocked(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dist = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, dist)
        return dist[0]
    }

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
                val restoredAt = System.currentTimeMillis()
                for ((uid, list) in trails.toMap()) {
                    val lastPt = list.lastOrNull() ?: continue
                    lastPoint[uid] = lastPt
                    // Ao restaurar, o membro começa STATIONARY ancorado no
                    // último ponto: fixes de tremor não reacumulam rastro.
                    anchors[uid] = lastPt
                    motionStates[uid] = MotionState.STATIONARY
                    val ageMs = restoredAt - lastPt.recordedAt
                    if (ageMs >= RouteQuality.TRAIL_AUTO_HIDE_AFTER_STATIONARY_MS) {
                        // Sessão anterior: movimentação antiga que terminou há
                        // muito tempo. Trecho concluído já deve estar oculto.
                        trails.remove(uid)
                        Log.i(TAG, "trecho de sessão anterior ocultado (parado): user=$uid")
                    } else {
                        // Parou há pouco (ou ainda movendo): inicia a contagem
                        // do auto-hide; o próximo fix em movimento a cancela.
                        stationarySinceWall[uid] = restoredAt
                    }
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
        resetMotionLocked()
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