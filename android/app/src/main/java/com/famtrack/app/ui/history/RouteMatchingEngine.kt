package com.famtrack.app.ui.history

import android.util.Log
import com.famtrack.app.data.model.RoutePoint
import com.google.android.gms.maps.model.LatLng
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ---------------------------------------------------------------------------
// OSRM Map Matching — UMA chamada por segmento, nunca uma trace que cruze gaps.
//
// ATENÇÃO: router.project-osrm.org é o endpoint público de DEMONSTRAÇÃO do
// projeto OSRM. Ele NÃO é um backend de produção: serve para testes e uso
// residencial leve, sem SLA, com throttling e possível indisponibilidade. Em
// produção, usar provedor/self-hosted. Dados de mapa: © OpenStreetMap (ODbL).
// ---------------------------------------------------------------------------

private const val OSRM_TAG = "FamTrackRouteMatching"

private val osrmClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 10_000
    }
}

/** Tolerância Douglas–Peucker por segmento antes do OSRM (8–15 m). */
internal const val DECIMATION_EPSILON_METERS = 12.0

/** Limite da API pública do OSRM para /match (configurável; manter conservador). */
internal const val OSRM_MAX_POINTS_PER_SEGMENT = 100

private sealed class CachedMatch {
    class Ready(val segment: MatchedSegment) : CachedMatch()
    class InFlight(val deferred: Deferred<MatchedSegment>) : CachedMatch()
}

/**
 * Cache LRU (24 entradas) de segmentos casados + deduplicação de chamadas em
 * voo: se duas telas pedem o mesmo trecho na mesma sessão, ambas aguardam a
 * MESMA requisição HTTP.
 */
private object OsrmRouteCache {
    private const val MAX_SIZE = 24

    private val cache = object : LinkedHashMap<String, CachedMatch>(MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedMatch>?): Boolean =
            size > MAX_SIZE
    }

    @Synchronized
    fun store(key: String, entry: CachedMatch) {
        cache[key] = entry
    }

    @Synchronized
    fun lookup(key: String): CachedMatch? = cache[key]
}

private val osrmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val cacheMutex = Mutex()

/**
 * Casa a rota do dia por segmento (ETAPA 5): detecta gaps, divide o registro e
 * chama /match uma vez por segmento, em sequência. Falha em um segmento vira
 * PARTIAL (pontos brutos daquele trecho) — nunca conecta os lados de um gap.
 */
internal suspend fun fetchMatchedRoute(
    points: List<RoutePoint>,
    epsilonMeters: Double = DECIMATION_EPSILON_METERS
): MatchedRouteResult {
    if (points.size < 2) return MatchedRouteResult(emptyList(), emptyList(), points.size, 0)

    val gapIndices = detectGapIndices(points)
    val gaps = gapIndices.map { MatchGap(afterIndex = it, durationMillis = gapDurationMillis(points, it)) }
    val rawSegments = splitSegments(points)

    val work = rawSegments.mapNotNull { raw ->
        if (raw.size < 2) null
        else {
            val decimated = capPoints(douglasPeucker(raw, epsilonMeters))
            decimated to raw
        }
    }

    // TRAJ-2b: segmentos são independentes — casa concorrentemente (ordem
    // preservada via awaitAll). Com o endpoint público do OSRM degradado/lento,
    // o tempo total cai de N*duracao p/ ~max(duracao), permitindo o fallback
    // PARTIAL concluir dentro do withTimeout do HomeScreen; com servidor ok,
    // apenas acelera sem mudar o resultado.
    val deferred = coroutineScope {
        work.map { (decimated, raw) ->
            async { matchSingleSegment(decimated, raw) }
        }
    }
    val matchedInOrder = deferred.awaitAll()

    var matched = 0
    val total = work.sumOf { it.first.size }
    val segments = mutableListOf<MatchedSegment>()
    for (seg in matchedInOrder) {
        if (seg.status == RouteMatchStatus.MATCHED) matched += seg.points.size
        segments += seg
    }
    return MatchedRouteResult(segments, gaps, total, matched)
}

/** Limita o tamanho enviado ao OSRM usando Douglas–Peucker progressivo. */
private fun capPoints(points: List<RoutePoint>): List<RoutePoint> {
    if (points.size <= OSRM_MAX_POINTS_PER_SEGMENT) return points
    var epsilon = DECIMATION_EPSILON_METERS
    var current = douglasPeucker(points, epsilon)
    while (current.size > OSRM_MAX_POINTS_PER_SEGMENT && epsilon < 5_000.0) {
        epsilon *= 2
        current = douglasPeucker(points, epsilon)
    }
    if (current.size <= OSRM_MAX_POINTS_PER_SEGMENT) return current
    // Última linha de defesa (nunca usado em traços reais): mantém extremidades.
    val step = (current.size - 1).toDouble() / (OSRM_MAX_POINTS_PER_SEGMENT - 1).toDouble()
    val idx = (0 until OSRM_MAX_POINTS_PER_SEGMENT - 1).map { (it * step).toInt() } + current.lastIndex
    return idx.distinct().map { current[it] }
}

private suspend fun matchSingleSegment(
    decimated: List<RoutePoint>,
    raw: List<RoutePoint>
): MatchedSegment {
    val key = stableSegmentKey(decimated)

    cacheMutex.withLock {
        OsrmRouteCache.lookup(key)?.let { hit ->
            return when (hit) {
                is CachedMatch.Ready -> hit.segment
                is CachedMatch.InFlight -> hit.deferred.await()
            }
        }
    }

    val deferred = osrmScope.async {
        try {
            queryOsrm(decimated) ?: partialSegment(raw)
        } catch (e: Exception) {
            Log.d(OSRM_TAG, "matching inesperado: ${e.message}")
            partialSegment(raw)
        }
    }

    cacheMutex.withLock {
        OsrmRouteCache.store(key, CachedMatch.InFlight(deferred))
    }
    val result = deferred.await()
    cacheMutex.withLock {
        OsrmRouteCache.store(key, CachedMatch.Ready(result))
    }
    return result
}

/** Consulta o OSRM para um único trecho. Retorna null em qualquer falha. */
private suspend fun queryOsrm(decimated: List<RoutePoint>): MatchedSegment? {
    if (decimated.size < 2) return null
    val coords = decimated.joinToString(";") { "${it.longitude},${it.latitude}" }
    val url = "https://router.project-osrm.org/match/v1/driving/$coords?geometries=geojson&overview=full"
    return try {
        val text = osrmClient.get(url).bodyAsText()
        val root = Json.parseToJsonElement(text).jsonObject
        val code = root["code"]?.jsonPrimitive?.content
        val matchings = root["matchings"]?.jsonArray
        val geometry = matchings?.firstOrNull()?.jsonObject?.get("geometry")?.jsonObject
        val coordsArray = geometry?.get("coordinates")?.jsonArray
        if (code != "Ok" || coordsArray.isNullOrEmpty()) {
            Log.d(OSRM_TAG, "sem matchings (${decimated.size} pts)")
            null
        } else {
            val points = coordsArray.map { arr ->
                val c = arr.jsonArray
                LatLng(c[1].jsonPrimitive.content.toDouble(), c[0].jsonPrimitive.content.toDouble())
            }
            Log.d(OSRM_TAG, "ok: ${decimated.size} pts -> ${points.size} matched")
            MatchedSegment(points, RouteMatchStatus.MATCHED)
        }
    } catch (e: Exception) {
        Log.d(OSRM_TAG, "falha: ${e.message}")
        null
    }
}

/** Fallback PARTIAL: pontos brutos do trecho (sem OSRM). */
private fun partialSegment(raw: List<RoutePoint>): MatchedSegment =
    MatchedSegment(
        points = raw.map { LatLng(it.latitude, it.longitude) },
        status = RouteMatchStatus.PARTIAL
    )