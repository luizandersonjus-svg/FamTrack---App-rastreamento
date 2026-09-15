package com.famtrack.app.ui.history

import android.util.Log
import com.famtrack.app.data.model.RoutePoint
import com.google.android.gms.maps.model.LatLng
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
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
// ATENÇÃO — SERVIDOR ATUAL: router.project-osrm.org é o endpoint público de
// DEMONSTRAÇÃO do projeto OSRM. Ele NÃO é um backend de produção: serve para
// testes e uso residencial leve, SEM SLA, com throttling e possível
// indisponibilidade. Para produção, avaliar provedor/self-hosted (CORR-0).
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
private const val OSRM_URL = "https://router.project-osrm.org/match/v1/driving"

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
    data class Matched(val segment: MatchedSegment) : OsrmOutcome
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

/** Portão de concorrência: redimensionável em runtime (troca o semáforo). */
private object OsrmGate {
    @Volatile
    private var semaphore = Semaphore(OsrmTuning.maxConcurrency)
    @Volatile
    private var currentPermits = OsrmTuning.maxConcurrency

    /** Aplica o valor de [OsrmTuning.maxConcurrency] trocando o semáforo se mudou. */
    fun sync() {
        val target = OsrmTuning.maxConcurrency.coerceIn(1, 20)
        OsrmTuning.maxConcurrency = target
        if (target != currentPermits) {
            semaphore = Semaphore(target)
            currentPermits = target
            Log.w(OSRM_TAG, "gate redimensionado: concurrency=$target")
        }
    }

    suspend fun <T> withPermit(block: suspend () -> T): T {
        semaphore.acquire()
        try {
            return block()
        } finally {
            semaphore.release()
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

    val deferred = osrmScope.async {
        val segment = try {
            queryOsrm(decimated) ?: partialSegment(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(OSRM_TAG, "matching inesperado: ${classifyFailure(e)}")
            partialSegment(raw)
        }
        OsrmStats.record(segment.status == RouteMatchStatus.MATCHED)
        segment
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

/**
 * Consulta o OSRM para um único trecho, com a resiliência do CORR-0:
 *  - semáforo limita chamadas simultâneas (3/6/10 configurável);
 *  - falhas transitórias RÁPIDAS (TLS, connect, DNS, 429, 5xx) têm retry com
 *    backoff exponencial; timeouts de rede não são retentados (budget 20s);
 *  - rejeição definitiva do OSRM (ex.: NoMatch) NÃO é retentada;
 *  - a causa real é classificada e registrada nos logs.
 * Retorna null quando não há caminho confiável (falta -> partialSegment).
 */
private suspend fun queryOsrm(decimated: List<RoutePoint>): MatchedSegment? {
    if (decimated.size < 2) return null
    val coords = decimated.joinToString(";") { "${it.longitude},${it.latitude}" }
    val url = "$OSRM_URL/$coords?geometries=geojson&overview=full"

    var lastReason = "UNKNOWN"
    var lastCause: Exception? = null
    val attempts = 1 + OsrmTuning.maxRetries
    for (attempt in 1..attempts) {
        val outcome = try {
            OsrmGate.withPermit { singleOsrmAttempt(url, decimated.size) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OsrmOutcome.Transient(classifyFailure(e), e)
        }

        when (outcome) {
            is OsrmOutcome.Matched -> {
                Log.d(OSRM_TAG, "MATCHED: ${decimated.size} pts -> ${outcome.segment.points.size} casados (concurrency=${OsrmTuning.maxConcurrency})")
                return outcome.segment
            }
            is OsrmOutcome.Rejected -> {
                Log.d(OSRM_TAG, "REJEITADO_${outcome.code}: ${decimated.size} pts (definitivo, sem retry)")
                return null
            }
            is OsrmOutcome.Transient -> {
                lastReason = outcome.reason
                lastCause = outcome.cause
                // Retry só para falhas RÁPIDAS (handshake TLS/connect/DNS/429/5xx).
                // Timeouts de rede/leitura indicam servidor lento ou inacessível:
                // retentá-los estouraria o withTimeout de 20s do HomeScreen sem
                // ganho real (a 2ª tentativa tende a falhar igual).
                val retryable = shouldRetry(lastReason)
                if (retryable && attempt < attempts) {
                    val backoff = OsrmTuning.retryBaseDelayMs * (1L shl (attempt - 1))
                    Log.w(OSRM_TAG, "tentativa $attempt/$attempts: reason=$lastReason backoff=${backoff}ms")
                    delay(backoff)
                } else if (!retryable) {
                    break
                }
            }
        }
    }
    Log.w(OSRM_TAG, "falhou após $attempts tentativas: reason=$lastReason")
    if (lastCause != null && lastReason == "TLS") {
        val c = lastCause!!
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
private suspend fun singleOsrmAttempt(url: String, inputSize: Int): OsrmOutcome {
    val response = osrmClient.get(url)
    val status = response.status.value
    val text = response.bodyAsText()

    if (status == 429) throw TransientOsrmException("RATE_LIMIT_429")
    if (status >= 500) throw TransientOsrmException("SERVER_ERROR_$status")
    if (status != 200) throw TransientOsrmException("HTTP_$status")

    val root = Json.parseToJsonElement(text).jsonObject
    val code = root["code"]?.jsonPrimitive?.content ?: "UNKNOWN"
    if (code != "Ok") return OsrmOutcome.Rejected(code)

    val matchings = root["matchings"]?.jsonArray
    val geometry = matchings?.firstOrNull()?.jsonObject?.get("geometry")?.jsonObject
    val coordsArray = geometry?.get("coordinates")?.jsonArray
    if (coordsArray.isNullOrEmpty()) {
        Log.w(OSRM_TAG, "OK mas sem geometria (${inputSize} pts)")
        return OsrmOutcome.Rejected("OK_EMPTY_GEOMETRY")
    }
    val points = coordsArray.map { arr ->
        val c = arr.jsonArray
        LatLng(c[1].jsonPrimitive.content.toDouble(), c[0].jsonPrimitive.content.toDouble())
    }
    if (points.size < 2) return OsrmOutcome.Rejected("OK_TOO_SHORT")
    return OsrmOutcome.Matched(MatchedSegment(points, RouteMatchStatus.MATCHED))
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