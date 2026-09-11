package com.famtrack.app.ui.history

import com.famtrack.app.data.model.RoutePoint
import com.google.android.gms.maps.model.LatLng
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Modelos de Map Matching — rota por SEGMENTO, nunca uma lista única que
// atravesse gaps. O contrato garante que nenhum trecho mistura os dois lados
// de uma interrupção de gravação.
// ---------------------------------------------------------------------------

enum class RouteMatchStatus {
    /** OSRM devolveu geometria casada na malha viária. */
    MATCHED,

    /** OSRM falhou/incompleto — este segmento usa os pontos brutos do GPS. */
    PARTIAL
}

/** Um trecho contínuo da rota (sem gaps) e o que o OSRM respondeu para ele. */
data class MatchedSegment(
    val points: List<LatLng>,
    val status: RouteMatchStatus
)

/** Interrupção identificada no registro: [afterIndex] = índice do ponto APÓS o gap. */
data class MatchGap(
    val afterIndex: Int,
    val durationMillis: Long
)

/**
 * Resultado do casamento do dia inteiro. [segments] nunca conecta dois lados
 * de um gap; [gaps] documenta as quebras para o player/render saber onde pular.
 */
data class MatchedRouteResult(
    val segments: List<MatchedSegment>,
    val gaps: List<MatchGap>,
    val totalPoints: Int,
    val matchedPoints: Int
) {
    val isEmpty: Boolean get() = segments.isEmpty()
}

// ---------------------------------------------------------------------------
// Critérios de gap (ETAPA 3) — quebram o segmento ANTES de qualquer OSRM:
//  - timestamp ausente ou ilegível;
//  - dt entre amostras > GAP_MAX_DT_MILLIS (3 min);
//  - distância em linha reta > GAP_MAX_DIST_METERS (500 m);
//  - velocidade implícita > GAP_MAX_IMPLIED_KMH (180 km/h) em janela < 30 s.
// ---------------------------------------------------------------------------

internal const val GAP_MAX_DT_MILLIS = 3 * 60 * 1000L
internal const val GAP_MAX_DIST_METERS = 500.0
internal const val GAP_MAX_IMPLIED_KMH = 180.0
internal const val GAP_IMPLIED_WINDOW_MILLIS = 30_000L

internal fun isGapBetween(a: RoutePoint, b: RoutePoint): Boolean {
    val aMillis = parseTimestampMillis(a.recorded_at)
    val bMillis = parseTimestampMillis(b.recorded_at)
    if (aMillis == null || bMillis == null) return true
    val dt = bMillis - aMillis
    if (dt > GAP_MAX_DT_MILLIS) return true
    val distMeters = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
    if (distMeters > GAP_MAX_DIST_METERS) return true
    if (dt < GAP_IMPLIED_WINDOW_MILLIS && distMeters / maxOf(dt, 1L) * 3600.0 > GAP_MAX_IMPLIED_KMH) {
        return true
    }
    return false
}

/** Índices em que o dia quebra: retorna o índice do PRIMEIRO ponto do novo segmento. */
internal fun detectGapIndices(points: List<RoutePoint>): List<Int> {
    if (points.size < 2) return emptyList()
    val breaks = mutableListOf<Int>()
    for (i in 1 until points.size) {
        if (isGapBetween(points[i - 1], points[i])) breaks += i
    }
    return breaks
}

/** Divisão dos pontos em trechos confiáveis (nunca cruza um gap). */
internal fun splitSegments(points: List<RoutePoint>): List<List<RoutePoint>> {
    if (points.isEmpty()) return emptyList()
    if (points.size == 1) return listOf(points)
    val breaks = detectGapIndices(points)
    if (breaks.isEmpty()) return listOf(points)
    val result = mutableListOf<List<RoutePoint>>()
    var start = 0
    for (b in breaks) {
        if (b > start) result += points.subList(start, b)
        start = b
    }
    if (start < points.size) result += points.subList(start, points.size)
    return result
}

internal fun gapDurationMillis(points: List<RoutePoint>, breakIndex: Int): Long {
    val a = parseTimestampMillis(points.getOrNull(breakIndex - 1)?.recorded_at)
    val b = parseTimestampMillis(points.getOrNull(breakIndex)?.recorded_at)
    return if (a != null && b != null) maxOf(0L, b - a) else 0L
}

// ---------------------------------------------------------------------------
// Distâncias (haversine) e Douglas–Peucker com tolerância em metros
// (ETAPA 4). Preserva primeiro/último e o formato das curvas; usa a distância
// perpendicular em projeção local para escolher os pontos significativos.
// ---------------------------------------------------------------------------

internal fun haversineMeters(
    aLat: Double, aLon: Double,
    bLat: Double, bLon: Double
): Double {
    val earthRadiusMeters = 6_371_000.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) *
        sin(dLon / 2) * sin(dLon / 2)
    return 2 * earthRadiusMeters * asin(sqrt(h))
}

/**
 * Douglas–Peucker com tolerância em metros (8–15 m recomendado). Reduz pontos
 * redundantes preservando curvas e extremidades da amostra.
 */
internal fun douglasPeucker(points: List<RoutePoint>, epsilonMeters: Double): List<RoutePoint> {
    if (points.size <= 2) return points
    val keep = BooleanArray(points.size) { false }
    keep[0] = true
    keep[points.lastIndex] = true
    val stack = mutableListOf<Int>()
    stack.add(0)
    stack.add(points.lastIndex)
    while (stack.isNotEmpty()) {
        val end = stack.removeAt(stack.size - 1)
        val start = stack.removeAt(stack.size - 1)
        if (end <= start + 1) continue
        var maxDist = -1.0
        var maxIdx = -1
        for (i in start + 1 until end) {
            val d = perpendicularMeters(points[start], points[end], points[i])
            if (d > maxDist) {
                maxDist = d
                maxIdx = i
            }
        }
        if (maxIdx >= 0 && maxDist > epsilonMeters) {
            keep[maxIdx] = true
            stack.add(start)
            stack.add(maxIdx)
            stack.add(maxIdx)
            stack.add(end)
        }
    }
    return points.filterIndexed { i, _ -> keep[i] }
}

/** Distância perpendicular do ponto [p] à reta [a]-[b], em metros (projeção local). */
internal fun perpendicularMeters(a: RoutePoint, b: RoutePoint, p: RoutePoint): Double =
    perpendicularMetersLatLng(
        a.latitude, a.longitude,
        b.latitude, b.longitude,
        p.latitude, p.longitude
    )

internal fun perpendicularMetersLatLng(
    aLat: Double, aLon: Double,
    bLat: Double, bLon: Double,
    pLat: Double, pLon: Double
): Double {
    val midLat = (aLat + bLat) / 2
    val mPerLat = 111_132.954 - 559.822 * cos(2 * Math.toRadians(midLat))
    val mPerLon = 111_319.491 * cos(Math.toRadians(midLat))
    val ax = aLon * mPerLon
    val ay = aLat * mPerLat
    val bx = bLon * mPerLon
    val by = bLat * mPerLat
    val px = pLon * mPerLon
    val py = pLat * mPerLat
    val dx = bx - ax
    val dy = by - ay
    if (dx == 0.0 && dy == 0.0) return haversineMeters(aLat, aLon, pLat, pLon)
    val t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
    val cx = ax + t * dx
    val cy = ay + t * dy
    return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
}

/** Chave estável de cache: lat/lon arredondados a ~10 cm, na ordem dos pontos. */
internal fun stableSegmentKey(points: List<RoutePoint>): String {
    val sb = StringBuilder(points.size * 18)
    for (p in points) {
        sb.append(Math.round(p.latitude * 1_000_000)).append(',').append(Math.round(p.longitude * 1_000_000)).append(';')
    }
    return sb.toString()
}