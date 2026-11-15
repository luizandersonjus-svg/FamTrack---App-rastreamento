package com.famtrack.app.data.offline

/** Distância em metros entre dois pontos do rastro. */
internal typealias TrailDistance = (TrailPoint, TrailPoint) -> Float

/** Estado de movimento do membro (máquina de estados por usuário). */
internal enum class MotionState { STATIONARY, MOVING }

/** Resultado do replay da máquina de estados sobre pontos históricos. */
internal data class MovementGateResult(
    val kept: List<TrailPoint>,
    val state: MotionState,
    val anchor: TrailPoint?
)

/**
 * Regras puras do rastro ao vivo (sem Android, sem estado, sem relógio),
 * extraídas do [LiveTrailStore] para serem testáveis em JVM. A distância é
 * injetada: em produção é `Location.distanceBetween`; nos testes, haversine.
 */
internal object LiveTrailLogic {

    /**
     * Replay da máquina de estados de movimento (CORR-X) sobre uma sequência
     * de pontos (histórico do servidor, dia inteiro), ordenada aqui por
     * [TrailPoint.recordedAt]. Tem as mesmas regras do Realtime
     * ([LiveTrailStore.addPoint]):
     *  - STATIONARY: descarta pontos dentro de [RouteQuality.TRAIL_MOVEMENT_START_THRESHOLD_M]
     *    da âncora (tremor de quem está parado não vira trecho);
     *  - ao cruzar o limiar, MOVING e o trecho passa a acumular partindo da âncora;
     *  - MOVING confirma parada se permanecer dentro do raio por
     *    [RouteQuality.TRAIL_STATIONARY_CONFIRM_DURATION_MS] e, após
     *    [RouteQuality.TRAIL_AUTO_HIDE_AFTER_STATIONARY_MS] parado, o trecho
     *    concluído é descartado (queda de um dia com volta para casa não deixa
     *    lixo na casa).
     */
    fun applyMovementGate(points: List<TrailPoint>, distance: TrailDistance): MovementGateResult {
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
                val d = distance(anchor, p)
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
                    val dCand = distance(first, p)
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

    /**
     * Ordena os pontos por timestamp e divide em trechos contínuos, quebrando
     * sempre que o passo entre dois consecutivos for implausível ([isTrailGap]).
     * Trechos com menos de 2 pontos não são desenháveis e ficam de fora.
     */
    fun segment(points: List<TrailPoint>, distance: TrailDistance): List<List<TrailPoint>> {
        if (points.size < 2) return listOf(points)
        val sorted = points.sortedBy { it.recordedAt }
        val segments = mutableListOf<List<TrailPoint>>()
        var current = mutableListOf<TrailPoint>()
        for (p in sorted) {
            val last = current.lastOrNull()
            if (last != null && isTrailGap(last, p, distance)) {
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
    fun isTrailGap(a: TrailPoint, b: TrailPoint, distance: TrailDistance): Boolean {
        val dt = b.recordedAt - a.recordedAt
        if (dt < 0) return true
        val dist = distance(a, b)
        if (dt > RouteQuality.TRAIL_GAP_MAX_DT_MILLIS &&
            dist > RouteQuality.TRAIL_GAP_MAX_DIST_METERS
        ) {
            return true
        }
        if (dt < RouteQuality.TRAIL_MIN_DT_FOR_SPEED_MS) return false
        val kmh = (dist / (dt / 1000.0)) * 3.6
        return kmh > RouteQuality.TRAIL_MAX_IMPLIED_KMH
    }

    /**
     * Re-decimação do zero (usada pelo backfill, TRACK-1d): mantém o primeiro
     * ponto e só aceita os seguintes a [RouteQuality.TRAIL_MIN_DISTANCE_M] ou
     * mais do último mantido, aplicando o teto da janela deslizante no fim.
     */
    fun decimate(points: List<TrailPoint>, distance: TrailDistance): List<TrailPoint> {
        val out = mutableListOf<TrailPoint>()
        for (p in points) {
            val last = out.lastOrNull()
            if (last != null && distance(last, p) < RouteQuality.TRAIL_MIN_DISTANCE_M) continue
            out.add(p)
        }
        val max = RouteQuality.TRAIL_MAX_POINTS_PER_MEMBER
        return if (out.size > max) out.drop(out.size - max) else out
    }
}
