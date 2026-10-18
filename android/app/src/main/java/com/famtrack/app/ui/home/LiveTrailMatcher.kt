package com.famtrack.app.ui.home

import android.os.SystemClock
import android.util.Log
import com.famtrack.app.data.model.RoutePoint
import com.famtrack.app.data.offline.TrailPoint
import com.famtrack.app.ui.history.MatchedSegment
import com.famtrack.app.ui.history.RouteMatchStatus
import com.famtrack.app.ui.history.snapTraceToRoads
import com.google.android.gms.maps.model.LatLng
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "FamTrackTrailSnap"

/**
 * Pedaço desenhável do rastro ao vivo (TRACK-2).
 * [onRoad] = true: geometria encaixada nas ruas (linha cheia).
 * [onRoad] = false: GPS bruto — trecho ainda não processado ou que o servidor
 * não conseguiu encaixar (linha tracejada, sinalizando que é aproximado).
 */
internal data class TrailPiece(
    val points: List<LatLng>,
    val onRoad: Boolean
)

/**
 * Resultado por membro: pedaços na ordem do trajeto e o timestamp do último
 * ponto bruto já considerado. A UI completa com os pontos mais novos que
 * [coveredUntil] (chegaram depois do último processamento) como trecho bruto.
 */
internal data class SnappedTrail(
    val pieces: List<TrailPiece>,
    val coveredUntil: Long
)

/**
 * TRACK-2: encaixa o rastro ao vivo nas ruas.
 *
 * O LiveTrailStore entrega pontos brutos (o aparelho de um familiar envia um
 * ponto a cada ~15 m / 45 s; de carro isso dá centenas de metros entre pontos),
 * e ligá-los com retas corta quarteirões. Aqui cada trecho contínuo é dividido
 * em blocos fixos de [CHUNK_POINTS] pontos (com o ponto de emenda repetido):
 *
 * - Bloco COMPLETO: encaixado uma única vez e guardado em cache (o conteúdo
 *   não muda mais). Se o servidor falhar, tenta de novo após
 *   [FAILED_RETRY_MS].
 * - Bloco FINAL (ainda crescendo): reencaixado no máximo a cada
 *   [TAIL_MIN_INTERVAL_MS]; entre um e outro, mostra o último resultado + os
 *   pontos novos em bruto.
 *
 * Assim o número de chamadas ao OSRM fica proporcional ao trajeto, não à
 * frequência de redesenho.
 */
internal object LiveTrailMatcher {

    private const val CHUNK_POINTS = 30
    private const val TAIL_MIN_INTERVAL_MS = 15_000L
    private const val FAILED_RETRY_MS = 120_000L
    private const val CACHE_MAX = 400

    private class CacheEntry(
        val segment: MatchedSegment,
        val coveredUntil: Long,
        val atMs: Long
    )

    private val mutex = Mutex()

    // Blocos completos: chave estável -> resultado.
    private val chunkCache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > CACHE_MAX
    }

    // Bloco final de cada trecho: chave (membro + início do bloco) -> último resultado.
    private val tailCache = mutableMapOf<String, CacheEntry>()

    /**
     * Encaixa o snapshot atual do LiveTrailStore. Suspende enquanto consulta o
     * servidor (com concorrência limitada pelo motor de map-matching).
     */
    suspend fun snap(trails: Map<String, List<List<TrailPoint>>>): Map<String, SnappedTrail> =
        coroutineScope {
            trails.mapValues { (uid, segments) ->
                async { snapMember(uid, segments) }
            }.mapValues { it.value.await() }
        }

    private suspend fun snapMember(uid: String, segments: List<List<TrailPoint>>): SnappedTrail {
        val pieces = mutableListOf<TrailPiece>()
        var coveredUntil = Long.MIN_VALUE
        for (raw in segments) {
            if (raw.size < 2) continue
            val seg = raw.sortedBy { it.recordedAt }
            val (segPieces, segCovered) = snapSegment(uid, seg)
            pieces += segPieces
            coveredUntil = maxOf(coveredUntil, segCovered)
        }
        return SnappedTrail(pieces, coveredUntil)
    }

    private suspend fun snapSegment(
        uid: String,
        seg: List<TrailPoint>
    ): Pair<List<TrailPiece>, Long> = coroutineScope {
        val step = CHUNK_POINTS - 1
        val chunks = mutableListOf<List<TrailPoint>>()
        var start = 0
        while (start < seg.lastIndex) {
            val end = minOf(start + step, seg.lastIndex)
            chunks += seg.subList(start, end + 1)
            start = end
        }

        val results = chunks.map { chunk ->
            // Com CHUNK_POINTS pontos o bloco está fechado: o próximo ponto
            // abre um bloco novo (repetindo o ponto de emenda).
            val complete = chunk.size == CHUNK_POINTS
            async {
                if (complete) snapCompleteChunk(uid, chunk) else snapTailChunk(uid, chunk)
            }
        }.awaitAll()

        val pieces = mutableListOf<TrailPiece>()
        for ((i, entry) in results.withIndex()) {
            val chunk = chunks[i]
            if (entry == null) {
                pieces += rawPiece(chunk)
                continue
            }
            val onRoad = entry.segment.status == RouteMatchStatus.MATCHED
            pieces += TrailPiece(entry.segment.points, onRoad)
            // Pontos do bloco mais novos que o último encaixe: ligam o fim da
            // geometria encaixada aos pontos brutos recentes (provisório).
            val newer = chunk.filter { it.recordedAt > entry.coveredUntil }
            if (newer.isNotEmpty()) {
                val bridge = listOfNotNull(entry.segment.points.lastOrNull()) +
                    newer.map { LatLng(it.latitude, it.longitude) }
                if (bridge.size >= 2) pieces += TrailPiece(bridge, onRoad = false)
            }
        }
        pieces to seg.last().recordedAt
    }

    private suspend fun snapCompleteChunk(uid: String, chunk: List<TrailPoint>): CacheEntry? {
        val key = "$uid|${chunk.first().recordedAt}|${chunk.last().recordedAt}|${chunk.size}"
        val now = SystemClock.elapsedRealtime()
        mutex.withLock {
            val hit = chunkCache[key]
            if (hit != null && (hit.segment.status == RouteMatchStatus.MATCHED ||
                    now - hit.atMs < FAILED_RETRY_MS)
            ) {
                return hit
            }
        }
        val entry = CacheEntry(snapTraceToRoads(toRoutePoints(chunk)), chunk.last().recordedAt, now)
        mutex.withLock { chunkCache[key] = entry }
        // Bloco ficou completo: o resultado provisório dele não é mais necessário.
        mutex.withLock { tailCache.remove(tailKey(uid, chunk)) }
        return entry
    }

    private suspend fun snapTailChunk(uid: String, chunk: List<TrailPoint>): CacheEntry? {
        val key = tailKey(uid, chunk)
        val now = SystemClock.elapsedRealtime()
        val last = chunk.last().recordedAt
        val previous = mutex.withLock { tailCache[key] }
        if (previous != null) {
            val upToDate = previous.coveredUntil >= last
            val tooSoon = now - previous.atMs < TAIL_MIN_INTERVAL_MS
            if (upToDate || tooSoon) return previous
        }
        val entry = CacheEntry(snapTraceToRoads(toRoutePoints(chunk)), last, now)
        if (entry.segment.status != RouteMatchStatus.MATCHED && previous != null &&
            previous.segment.status == RouteMatchStatus.MATCHED
        ) {
            // Falha transitória: mantém o último encaixe bom e tenta de novo depois.
            Log.d(TAG, "encaixe do trecho final falhou; mantendo resultado anterior")
            val kept = CacheEntry(previous.segment, previous.coveredUntil, now)
            mutex.withLock { tailCache[key] = kept }
            return kept
        }
        mutex.withLock {
            tailCache[key] = entry
            if (tailCache.size > CACHE_MAX) tailCache.keys.firstOrNull()?.let { tailCache.remove(it) }
        }
        return entry
    }

    private fun tailKey(uid: String, chunk: List<TrailPoint>) = "$uid|${chunk.first().recordedAt}"

    private fun rawPiece(chunk: List<TrailPoint>) =
        TrailPiece(chunk.map { LatLng(it.latitude, it.longitude) }, onRoad = false)

    private fun toRoutePoints(chunk: List<TrailPoint>): List<RoutePoint> = chunk.map {
        RoutePoint(
            family_id = "",
            user_id = it.userId,
            latitude = it.latitude,
            longitude = it.longitude,
            recorded_at = Instant.ofEpochMilli(it.recordedAt).toString(),
            accuracy = it.accuracy,
            speed = it.speed
        )
    }

    /** Descarta todos os encaixes (ex.: ao limpar o rastro). */
    suspend fun clear() {
        mutex.withLock {
            chunkCache.clear()
            tailCache.clear()
        }
    }
}
