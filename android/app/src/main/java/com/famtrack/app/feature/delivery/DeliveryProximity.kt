package com.famtrack.app.feature.delivery

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Raio em que o entregador "chegou" ao endereço do pacote. */
internal const val ARRIVAL_RADIUS_M = 40.0

/**
 * Raio de saída (histerese): só depois de se afastar além dele o mesmo
 * pacote pode disparar de novo. Evita o painel pular para frente e para
 * trás com o GPS oscilando na porta.
 */
internal const val ARRIVAL_EXIT_RADIUS_M = 70.0

/** Quanto mais perto o vizinho precisa estar para tomar o lugar do atual. */
private const val SWITCH_MARGIN_M = 10.0

/** Distância em metros entre dois pontos (haversine, raio médio da Terra). */
internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

internal fun Delivery.distanceTo(lat: Double, lon: Double): Double =
    haversineMeters(lat, lon, latitude, longitude)

/** "a 85 m" / "a 1,2 km". */
internal fun formatDistance(meters: Double): String =
    if (meters < 1000) "a ${meters.toInt()} m"
    else "a " + String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f km", meters / 1000)

/**
 * Detecta a chegada a um endereço pendente para o painel rolar sozinho até
 * o pacote daquele lugar.
 *
 * Cada pacote dispara uma vez por aproximação: precisa sair do raio de
 * saída ([ARRIVAL_EXIT_RADIUS_M]) para disparar de novo. Com vários
 * pendentes no raio (prédio, vila), vence o mais perto.
 */
internal class ArrivalTracker(
    private val arrivalRadius: Double = ARRIVAL_RADIUS_M,
    private val exitRadius: Double = ARRIVAL_EXIT_RADIUS_M
) {
    private var currentId: String? = null

    /**
     * Nova posição do aparelho. Devolve o id do pacote ao qual acabou de
     * chegar, ou null se nada mudou.
     */
    fun update(lat: Double, lon: Double, deliveries: List<Delivery>): String? {
        val pending = deliveries.filter { !it.delivered && it.id != null }

        // Ainda perto do pacote atual (ou ele sumiu/foi entregue)?
        var currentDistance: Double? = null
        currentId?.let { id ->
            val d = pending.firstOrNull { it.id == id }?.distanceTo(lat, lon)
            if (d == null || d > exitRadius) currentId = null else currentDistance = d
        }

        val (nearest, nearestDistance) = pending
            .map { it to it.distanceTo(lat, lon) }
            .filter { it.second <= arrivalRadius }
            .minByOrNull { it.second }
            ?: return null

        if (nearest.id == currentId) return null
        // Vizinho colado: só troca se o novo estiver claramente mais perto,
        // senão o GPS oscilando faria o painel alternar entre os dois.
        val held = currentDistance
        if (held != null && held <= arrivalRadius && nearestDistance + SWITCH_MARGIN_M > held) {
            return null
        }
        currentId = nearest.id
        return nearest.id
    }
}
