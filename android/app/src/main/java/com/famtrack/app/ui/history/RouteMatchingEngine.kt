package com.famtrack.app.ui.history

import android.util.Log
import com.famtrack.app.BuildConfig
import com.famtrack.app.data.model.RoutePoint
import com.google.android.gms.maps.model.LatLng
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ---------------------------------------------------------------------------
// OSRM Map Matching — UMA chamada por segmento, nunca uma trace que cruze gaps.
//
// SERVIDOR: configurável via OSRM_BASE_URL no local.properties (TRACK-3).
// Sem configuração, usa router.project-osrm.org, o endpoint público de
// DEMONSTRAÇÃO: só para testes, uso não comercial e no máximo 1 req/s, sem
// SLA. Servidor próprio: docs/osrm-servidor-proprio.md.
// Dados de mapa: © OpenStreetMap (ODbL).
//
// CORR-0 (resiliência do cliente HTTP):
//  1. TLS via MOTOR OKHTTP (TLS nativo do Android: ALPN, cipher suites padrão
//     do sistema e cert store do SO). Causa raiz confirmada no Galaxy A36:
//     100% das chamadas falhavam no handshake TLS do motor CIO (TLS 100% Kotlin
//     sem ALPN) contra router.project-osrm.org; com OkHttp o handshake usa a
//     pilha TLS do Android, a mesma que o resto do app já usa com sucesso.
//  2. Concorrência BOUNDADA por semáforo (padrão 6; ajustável em runtime via
//     broadcast de DEBUG para comparar 3/6/10 e escolher o limite real).
//  3. Retry com backoff exponencial para falhas transitórias RÁPIDAS (TLS,
//     connect, DNS, 429/RATE_LIMIT, 5xx). Timeouts de rede não são retentados
//     (evita estourar o withTimeout de 20s do HomeScreen). Não retenta
//     rejeições definitivas (NoMatch).
//  4. Causa do erro classificada e logada (TLS/TIMEOUT/RATE_LIMIT/NoMatch...)
//     em vez de "falha" genérica.
// ---------------------------------------------------------------------------

private const val OSRM_TAG = "FamTrackRouteMatching"

/** Concorrência padrão para o endpoint demo público (escolhida após teste 3/6/10). */
private const val OSRM_CONCURRENCY_DEFAULT = 6

/** Retentativas adicionais para falhas transitórias (1 chamada + N retries). */
private const val OSRM_RETRY_DEFAULT = 2

/** Backoff base (500ms -> 1s -> ... exponencial). */
private const val OSRM_RETRY_BASE_DELAY_MS = 500L

/**
 * Ajuste em runtime do cliente OSRM (CORR-0). O padrão é de produção; builds
 * de DEBUG podem alterar via broadcast (OsrmTuningReceiver) para comparar
 * limites de concorrência (3/6/10) sem recompilar.
 */
internal object OsrmTuning {
    @Volatile
    var maxConcurrency: Int = OSRM_CONCURRENCY_DEFAULT
    @Volatile
    var maxRetries: Int = OSRM_RETRY_DEFAULT
    @Volatile
    var retryBaseDelayMs: Long = OSRM_RETRY_BASE_DELAY_MS
}

/** Resultado de uma tentativa de /match: sucesso, rejeição definitiva ou falha transitória. */
private sealed interface OsrmOutcome {
    data class Matched(val parts: List<List<LatLng>>) : OsrmOutcome
    data class Rejected(val code: String) : OsrmOutcome
    data class Transient(val reason: String, val cause: Exception) : OsrmOutcome
}

/** Falha transitória com causa conhecida (ex.: HTTP 429 / 5xx). */
private class TransientOsrmException(val reason: String) : IOException(reason)

/** Taxa de MATCHED acumulada (para logs de diagnóstico e comparação 3/6/10). */
private object OsrmStats {
    @Volatile
    var total = 0L
    @Volatile
    var matched = 0L

    fun record(matchedNow: Boolean) {
        total += 1
        if (matchedNow) matched += 1
    }

    fun rate(): Int = if (total == 0L) 0 else ((matched * 100) / total).toInt()
}

private val osrmClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
    }
    engine {
        // OkHttp usa a pilha TLS nativa do Android (ALPN, ciphers e cert store
        // do SO) — o handshake TLS puro-Kotlin do motor CIO falhava 100% das
        // vezes contra o endpoint demo do OSRM no Galaxy A36 (CORR-0).
        config {
            connectTimeout(10, TimeUnit.SECONDS)
            readTimeout(15, TimeUnit.SECONDS)
            writeTimeout(15, TimeUnit.SECONDS)
            retryOnConnectionFailure(false)
        }
    }
}

// ---------------------------------------------------------------------------
// TRACK-3: servidor configurável (local.properties -> BuildConfig).
//  - OSRM_BASE_URL vazio: usa o servidor de DEMONSTRAÇÃO público. A política
//    dele permite só uso não comercial e no máximo 1 requisição/segundo; o
//    portão abaixo força 1 chamada por vez com 1,1 s de intervalo.
//  - OSRM_API_KEY: enviado no cabeçalho X-FamTrack-Key; o proxy (Caddy) do
//    servidor próprio recusa chamadas sem ele (docs/osrm-servidor-proprio.md).
// ---------------------------------------------------------------------------
private const val OSRM_DEMO_URL = "https://router.project-osrm.org"
private const val OSRM_DEMO_MIN_SPACING_MS = 1_100L
private const val OSRM_KEY_HEADER = "X-FamTrack-Key"

private val OSRM_BASE_URL: String =
    BuildConfig.OSRM_BASE_URL.trim().trimEnd('/').ifBlank { OSRM_DEMO_URL }

private val OSRM_API_KEY: String = BuildConfig.OSRM_API_KEY.trim()

/** true enquanto o app usa o servidor de demonstração (limite de 1 req/s). */
internal val isUsingOsrmDemoServer: Boolean = OSRM_BASE_URL == OSRM_DEMO_URL

/** Portão de concorrência: redimensionável em runtime (troca o semáforo). */
private object OsrmGate {
    private val spacingMutex = Mutex()
    @Volatile
    private var lastStartMs = 0L

    @Volatile
    private var semaphore = Semaphore(OsrmTuning.maxConcurrency)
    @Volatile
    private var currentPermits = OsrmTuning.maxConcurrency

    /** Aplica o valor de [OsrmTuning.maxConcurrency] trocando o semáforo se mudou. */
    fun sync() {
        // Servidor de demonstração: 1 chamada por vez (política de uso).
        val target = if (isUsingOsrmDemoServer) 1 else OsrmTuning.maxConcurrency.coerceIn(1, 20)
        OsrmTuning.maxConcurrency = target
        if (target != currentPermits) {
            semaphore = Semaphore(target)
            currentPermits = target
            Log.w(OSRM_TAG, "gate redimensionado: concurrency=$target")
        }
    }

    suspend fun <T> withPermit(block: suspend () -> T): T {
        val sem = semaphore
        sem.acquire()
        try {
            if (isUsingOsrmDemoServer) {
                spacingMutex.withLock {
                    val wait = lastStartMs + OSRM_DEMO_MIN_SPACING_MS - System.currentTimeMillis()
                    if (wait > 0) delay(wait)
                    lastStartMs = System.currentTimeMillis()
                }
            }
            return block()
        } finally {
            sem.release()
        }
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

    // CORR-0: aplica a concorrência configurada (debug pode ter alterado).
    OsrmGate.sync()

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
    var partial = 0
    val total = work.sumOf { it.first.size }
    val segments = mutableListOf<MatchedSegment>()
    for (seg in matchedInOrder) {
        if (seg.status == RouteMatchStatus.MATCHED) {
            matched += seg.points.size
        } else {
            partial += 1
        }
        segments += seg
    }
    // CORR-0: taxa de sucesso por execução + acumulada (compara concorrência 3/6/10).
    val matchedSegs = segments.count { it.status == RouteMatchStatus.MATCHED }
    Log.d(OSRM_TAG, "resumo: segmentos=${segments.size} matched=$matchedSegs partial=$partial " +
        "pts=${total} concurrency=${OsrmTuning.maxConcurrency} " +
        "rateAcumulada=${OsrmStats.rate()}% (total=${OsrmStats.total} matched=${OsrmStats.matched})")
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

    val deferred = osrmScope.async { snapDecimated(decimated, raw) }

    cacheMutex.withLock {
        OsrmRouteCache.store(key, CachedMatch.InFlight(deferred))
    }
    val result = deferred.await()
    cacheMutex.withLock {
        OsrmRouteCache.store(key, CachedMatch.Ready(result))
    }
    return result
}

/**
 * TRACK-2: encaixa um trecho contínuo (sem gaps) na malha viária, sem passar
 * pelo cache LRU do Histórico. Usado pelo rastro ao vivo ([LiveTrailMatcher]),
 * que mantém o próprio cache por bloco de pontos.
 *
 * Ordem de tentativa: /match (com raios e timestamps) -> /route pelos pontos
 * (preenche trechos espaçados pelas ruas) -> pontos brutos (PARTIAL).
 */
internal suspend fun snapTraceToRoads(raw: List<RoutePoint>): MatchedSegment {
    if (raw.size < 2) return partialSegment(raw)
    OsrmGate.sync()
    val decimated = capPoints(douglasPeucker(raw, DECIMATION_EPSILON_METERS))
    return snapDecimated(decimated, raw)
}

private suspend fun snapDecimated(
    decimated: List<RoutePoint>,
    raw: List<RoutePoint>
): MatchedSegment {
    val segment = try {
        queryOsrm(decimated)
            ?: routeThroughPoints(raw)
            ?: partialSegment(raw)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(OSRM_TAG, "matching inesperado: ${classifyFailure(e)}")
        partialSegment(raw)
    }
    OsrmStats.record(segment.status == RouteMatchStatus.MATCHED)
    return segment
}

// ---------------------------------------------------------------------------
// TRACK-2: parâmetros do /match.
//
// - radiuses: raio de busca por ponto = precisão reportada pelo GPS, limitada
//   a [OSRM_MIN_RADIUS_M, OSRM_MAX_RADIUS_M]. Sem isso o OSRM usa ~5 m e
//   rejeita (NoMatch / matchings partidos) os fixes urbanos típicos de 20-60 m.
// - timestamps: deixam o OSRM descartar caminhos incompatíveis com o tempo
//   decorrido entre pontos espaçados (ex.: 45 s entre envios de um membro).
// - gaps=ignore: lacunas já foram tratadas por splitSegments/LiveTrailStore;
//   o OSRM não deve partir o trecho por conta própria.
// - tidy=true: limpa pontos redundantes/ruidosos antes do casamento.
// ---------------------------------------------------------------------------
private const val OSRM_MIN_RADIUS_M = 10.0
private const val OSRM_MAX_RADIUS_M = 50.0
private const val OSRM_DEFAULT_RADIUS_M = 25.0

/** Distância máxima para ligar dois pedaços de matching por /route. */
private const val BRIDGE_MAX_DISTANCE_M = 1_000.0

/** Decimação mais forte para o /route: menos pontos ruidosos = menos voltas falsas. */
private const val ROUTE_FALLBACK_EPSILON_M = 25.0

/** Waypoints máximos por /route (limite conservador do endpoint público). */
private const val ROUTE_MAX_WAYPOINTS = 25

/**
 * Sanidade do /route: a rota pelas ruas não pode ser muito mais longa que o
 * caminho percorrido pelo GPS (senão um ponto ruidoso gerou uma volta falsa).
 */
private const val ROUTE_MAX_LENGTH_RATIO = 1.6
private const val ROUTE_MAX_EXTRA_M = 150.0

private fun radiusFor(p: RoutePoint): Double {
    val acc = p.accuracy?.toDouble()
    if (acc == null || acc <= 0.0 || acc.isNaN()) return OSRM_DEFAULT_RADIUS_M
    return acc.coerceIn(OSRM_MIN_RADIUS_M, OSRM_MAX_RADIUS_M)
}

private fun coordsParam(points: List<RoutePoint>): String =
    points.joinToString(";") { "${it.longitude},${it.latitude}" }

private fun radiusesParam(points: List<RoutePoint>): String =
    points.joinToString(";") { String.format(java.util.Locale.US, "%.1f", radiusFor(it)) }

/** Timestamps em segundos, ou null se algum faltar ou estiver fora de ordem. */
private fun timestampsParam(points: List<RoutePoint>): String? {
    val secs = points.map { parseTimestampMillis(it.recorded_at)?.div(1000) ?: return null }
    for (i in 1 until secs.size) if (secs[i] < secs[i - 1]) return null
    return secs.joinToString(";")
}

private fun pathLengthMeters(points: List<LatLng>): Double {
    var total = 0.0
    for (i in 1 until points.size) {
        total += haversineMeters(
            points[i - 1].latitude, points[i - 1].longitude,
            points[i].latitude, points[i].longitude
        )
    }
    return total
}

private fun parseGeometry(geometry: kotlinx.serialization.json.JsonObject?): List<LatLng> {
    val coordsArray = geometry?.get("coordinates")?.jsonArray ?: return emptyList()
    return coordsArray.map { arr ->
        val c = arr.jsonArray
        LatLng(c[1].jsonPrimitive.content.toDouble(), c[0].jsonPrimitive.content.toDouble())
    }
}

/** Junta pedaços consecutivos sem repetir o ponto de emenda. */
private fun appendPath(out: MutableList<LatLng>, part: List<LatLng>) {
    if (part.isEmpty()) return
    if (out.isNotEmpty() && out.last() == part.first()) out.addAll(part.drop(1)) else out.addAll(part)
}

/**
 * Consulta o /match para um único trecho, com a resiliência do CORR-0.
 * TRACK-2: envia radiuses/timestamps/gaps/tidy e aproveita TODOS os pedaços
 * de `matchings` (antes só o primeiro era usado e o resto do trajeto caía para
 * linha reta). Pedaços consecutivos são ligados pelas ruas via /route.
 * Retorna null quando não há caminho confiável.
 */
private suspend fun queryOsrm(decimated: List<RoutePoint>): MatchedSegment? {
    if (decimated.size < 2) return null
    val sb = StringBuilder("$OSRM_BASE_URL/match/v1/driving/")
        .append(coordsParam(decimated))
        .append("?geometries=geojson&overview=full&gaps=ignore&tidy=true")
        .append("&radiuses=").append(radiusesParam(decimated))
    timestampsParam(decimated)?.let { sb.append("&timestamps=").append(it) }
    val url = sb.toString()

    val parts = osrmCall(url, "match", decimated.size) { root ->
        val matchings = root["matchings"]?.jsonArray
        val geoms = matchings.orEmpty()
            .map { parseGeometry(it.jsonObject["geometry"]?.jsonObject) }
            .filter { it.size >= 2 }
        if (geoms.isEmpty()) OsrmOutcome.Rejected("OK_EMPTY_GEOMETRY") else OsrmOutcome.Matched(geoms)
    } ?: return null

    val out = mutableListOf<LatLng>()
    for ((i, part) in parts.withIndex()) {
        if (i > 0 && out.isNotEmpty()) {
            val from = out.last()
            val to = part.first()
            val gap = haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude)
            if (gap > 1.0) {
                val bridge = if (gap <= BRIDGE_MAX_DISTANCE_M) routeBetween(from, to) else null
                // Sem ponte pelas ruas: liga reto (trecho curto entre pedaços).
                appendPath(out, bridge ?: listOf(from, to))
            }
        }
        appendPath(out, part)
    }
    if (out.size < 2) return null
    if (parts.size > 1) Log.d(OSRM_TAG, "match em ${parts.size} pedaços unidos (${out.size} pts)")
    return MatchedSegment(out, RouteMatchStatus.MATCHED)
}

/** /route entre dois pontos (ponte entre pedaços de matching). */
private suspend fun routeBetween(from: LatLng, to: LatLng): List<LatLng>? {
    val url = "$OSRM_BASE_URL/route/v1/driving/" +
        "${from.longitude},${from.latitude};${to.longitude},${to.latitude}" +
        "?geometries=geojson&overview=full"
    val straight = haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude)
    val path = osrmCall(url, "route", 2) { root -> parseRoute(root) }?.firstOrNull() ?: return null
    val len = pathLengthMeters(path)
    return if (len <= straight * ROUTE_MAX_LENGTH_RATIO * 2 + ROUTE_MAX_EXTRA_M) path else null
}

/**
 * TRACK-2: quando o /match rejeita o trecho, traça o caminho PELAS RUAS
 * passando pelos pontos (decimados com tolerância maior). Resolve as retas
 * que cortavam quarteirões entre pontos espaçados. Rejeita o resultado se a
 * rota ficar muito mais longa que o caminho do GPS (volta falsa causada por
 * ponto ruidoso do lado errado da via).
 */
private suspend fun routeThroughPoints(raw: List<RoutePoint>): MatchedSegment? {
    var pts = douglasPeucker(raw, ROUTE_FALLBACK_EPSILON_M)
    var eps = ROUTE_FALLBACK_EPSILON_M
    while (pts.size > ROUTE_MAX_WAYPOINTS && eps < 2_000.0) {
        eps *= 2
        pts = douglasPeucker(raw, eps)
    }
    if (pts.size < 2 || pts.size > ROUTE_MAX_WAYPOINTS) return null
    val url = "$OSRM_BASE_URL/route/v1/driving/" + coordsParam(pts) +
        "?geometries=geojson&overview=full&continue_straight=false" +
        "&radiuses=" + radiusesParam(pts)
    val path = osrmCall(url, "route", pts.size) { root -> parseRoute(root) }?.firstOrNull() ?: return null
    val gpsLen = pathLengthMeters(raw.map { LatLng(it.latitude, it.longitude) })
    val routeLen = pathLengthMeters(path)
    if (routeLen > gpsLen * ROUTE_MAX_LENGTH_RATIO + ROUTE_MAX_EXTRA_M) {
        Log.d(OSRM_TAG, "route descartada: gps=${gpsLen.toInt()}m rota=${routeLen.toInt()}m")
        return null
    }
    Log.d(OSRM_TAG, "ROUTED: ${pts.size} waypoints -> ${path.size} pts")
    return MatchedSegment(path, RouteMatchStatus.MATCHED)
}

private fun parseRoute(root: kotlinx.serialization.json.JsonObject): OsrmOutcome {
    val geom = root["routes"]?.jsonArray?.firstOrNull()?.jsonObject?.get("geometry")?.jsonObject
    val path = parseGeometry(geom)
    return if (path.size >= 2) OsrmOutcome.Matched(listOf(path)) else OsrmOutcome.Rejected("OK_EMPTY_ROUTE")
}

/**
 * Chamada ao OSRM com a resiliência do CORR-0:
 *  - semáforo limita chamadas simultâneas (3/6/10 configurável);
 *  - falhas transitórias RÁPIDAS (TLS, connect, DNS, 429, 5xx) têm retry com
 *    backoff exponencial; timeouts de rede não são retentados (budget 20s);
 *  - rejeição definitiva do OSRM (ex.: NoMatch/NoRoute) NÃO é retentada;
 *  - a causa real é classificada e registrada nos logs.
 */
private suspend fun osrmCall(
    url: String,
    service: String,
    inputSize: Int,
    parse: (kotlinx.serialization.json.JsonObject) -> OsrmOutcome
): List<List<LatLng>>? {
    var lastReason = "UNKNOWN"
    var lastCause: Exception? = null
    val attempts = 1 + OsrmTuning.maxRetries
    for (attempt in 1..attempts) {
        val outcome = try {
            OsrmGate.withPermit { singleOsrmAttempt(url, parse) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OsrmOutcome.Transient(classifyFailure(e), e)
        }

        when (outcome) {
            is OsrmOutcome.Matched -> {
                Log.d(OSRM_TAG, "$service OK: $inputSize pts -> ${outcome.parts.sumOf { it.size }} pts em ${outcome.parts.size} pedaço(s)")
                return outcome.parts
            }
            is OsrmOutcome.Rejected -> {
                Log.d(OSRM_TAG, "$service REJEITADO_${outcome.code}: $inputSize pts (definitivo, sem retry)")
                return null
            }
            is OsrmOutcome.Transient -> {
                lastReason = outcome.reason
                lastCause = outcome.cause
                val retryable = shouldRetry(lastReason)
                if (retryable && attempt < attempts) {
                    val backoff = OsrmTuning.retryBaseDelayMs * (1L shl (attempt - 1))
                    Log.w(OSRM_TAG, "$service tentativa $attempt/$attempts: reason=$lastReason backoff=${backoff}ms")
                    delay(backoff)
                } else if (!retryable) {
                    break
                }
            }
        }
    }
    Log.w(OSRM_TAG, "$service falhou após $attempts tentativas: reason=$lastReason")
    val c = lastCause
    if (c != null && lastReason == "TLS") {
        Log.d(OSRM_TAG, "detalhe TLS: ${c.javaClass.simpleName}: ${c.message?.take(200) ?: "-"}")
    }
    return null
}

/** Falhas que valem nova tentativa (rápidas); timeouts ficam de fora. */
private fun shouldRetry(reason: String): Boolean = when {
    reason == "TLS" || reason == "CERT" || reason == "CONNECT_DENIED" ||
        reason == "DNS" || reason == "IO" || reason == "RATE_LIMIT_429" -> true
    reason.startsWith("SERVER_ERROR_") -> true
    else -> false
}

/** Uma tentativa HTTP única (dentro do limite de concorrência). */
private suspend fun singleOsrmAttempt(
    url: String,
    parse: (kotlinx.serialization.json.JsonObject) -> OsrmOutcome
): OsrmOutcome {
    val response = osrmClient.get(url) {
        if (OSRM_API_KEY.isNotEmpty()) header(OSRM_KEY_HEADER, OSRM_API_KEY)
    }
    val status = response.status.value
    val text = response.bodyAsText()

    if (status == 429) throw TransientOsrmException("RATE_LIMIT_429")
    if (status >= 500) throw TransientOsrmException("SERVER_ERROR_$status")
    // 400 com JSON é rejeição definitiva do OSRM (NoMatch, NoRoute, TooBig...).
    val root = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw TransientOsrmException("HTTP_$status")
    }
    val code = root["code"]?.jsonPrimitive?.content ?: "HTTP_$status"
    if (code != "Ok") return OsrmOutcome.Rejected(code)
    return parse(root)
}

/** Classifica a causa exata da falha para diagnóstico (CORR-0). */
private fun classifyFailure(e: Exception): String = when (e) {
    is TransientOsrmException -> e.reason
    is ConnectTimeoutException -> "CONNECT_TIMEOUT"
    is SocketTimeoutException -> "SOCKET_TIMEOUT"
    is HttpRequestTimeoutException -> "REQUEST_TIMEOUT"
    is ConnectException -> "CONNECT_DENIED"
    is UnknownHostException -> "DNS"
    is SSLException -> "TLS"
    is CertificateException -> "CERT"
    is IOException -> "IO"
    else -> e.javaClass.simpleName
}

/** Fallback PARTIAL: pontos brutos do trecho (sem OSRM). */
private fun partialSegment(raw: List<RoutePoint>): MatchedSegment =
    MatchedSegment(
        points = raw.map { LatLng(it.latitude, it.longitude) },
        status = RouteMatchStatus.PARTIAL
    )