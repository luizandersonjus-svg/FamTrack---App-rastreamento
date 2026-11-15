package com.famtrack.app.feature.delivery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Setas de direção do Modo Entregas.
//
// O caminho vem do OSRM com perfil de carro (respeita mão única). Aqui fica
// a parte geométrica, pura (testável na JVM): recortar o trecho já
// percorrido, saber se o entregador saiu do caminho e espalhar as setas em
// intervalos regulares, cada uma apontando no sentido do trânsito.
// ---------------------------------------------------------------------------

/** Ponto geográfico simples (independente do SDK do Maps, para os testes). */
internal data class GeoPt(val lat: Double, val lon: Double)

/** Seta no mapa: posição e rumo em graus (0 = norte, sentido horário). */
internal data class RouteArrow(val lat: Double, val lon: Double, val bearing: Float)

/** O que as setas mostram. */
internal enum class ArrowMode(val label: String, val short: String) {
    SELECTED("Até o pacote escolhido", "até o pacote"),
    ROUTE("Rota inteira, na ordem da rota", "ordem da rota"),
    NUMBER("Rota inteira, pela ordem do nº do pacote", "ordem do nº"),
    OFF("Sem setas", "desligadas")
}

/** No máximo tantas paradas por pedido ao OSRM (limite prudente do servidor). */
internal const val MAX_ARROW_STOPS = 20

/**
 * Paradas que as setas percorrem, na ordem. SELECTED: só o pacote escolhido.
 * ROUTE / NUMBER: os pendentes na ordem da rota ou do número do pacote.
 */
internal fun arrowStops(mode: ArrowMode, all: List<Delivery>, selected: Delivery?): List<Delivery> =
    when (mode) {
        ArrowMode.OFF -> emptyList()
        ArrowMode.SELECTED -> listOfNotNull(selected)
        ArrowMode.ROUTE -> all.inRouteOrder().filter { !it.delivered }
        ArrowMode.NUMBER -> all.sortedAndFiltered(DeliverySort.PACOTE, DeliveryFilter.TODOS)
            .filter { !it.delivered }
    }.filter { it.id != null }.take(MAX_ARROW_STOPS)

// --- geometria local (projeção equirretangular em torno de um ponto) ------

private const val EARTH_R = 6_371_000.0

private fun toLocal(origin: GeoPt, p: GeoPt): Pair<Double, Double> {
    val x = Math.toRadians(p.lon - origin.lon) * EARTH_R * cos(Math.toRadians(origin.lat))
    val y = Math.toRadians(p.lat - origin.lat) * EARTH_R
    return x to y
}

private fun fromLocal(origin: GeoPt, x: Double, y: Double): GeoPt = GeoPt(
    origin.lat + Math.toDegrees(y / EARTH_R),
    origin.lon + Math.toDegrees(x / (EARTH_R * cos(Math.toRadians(origin.lat))))
)

/** Rumo de [a] para [b], em graus (0 = norte, 90 = leste). */
internal fun bearingDegrees(a: GeoPt, b: GeoPt): Float {
    val (x, y) = toLocal(a, b)
    val deg = Math.toDegrees(atan2(x, y))
    return ((deg + 360.0) % 360.0).toFloat()
}

/** Ponto mais próximo de [p] sobre o caminho: segmento, ponto e distância. */
internal data class PathProjection(val segment: Int, val point: GeoPt, val distanceM: Double)

internal fun projectOnPath(path: List<GeoPt>, p: GeoPt): PathProjection? {
    if (path.size < 2) return null
    var best: PathProjection? = null
    for (i in 0 until path.size - 1) {
        val (ax, ay) = toLocal(p, path[i])
        val (bx, by) = toLocal(p, path[i + 1])
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        // p é a origem (0, 0) do sistema local.
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val qx = ax + t * dx
        val qy = ay + t * dy
        val d = sqrt(qx * qx + qy * qy)
        if (best == null || d < best.distanceM) {
            best = PathProjection(i, fromLocal(p, qx, qy), d)
        }
    }
    return best
}

/**
 * Caminho que ainda falta: começa no ponto do caminho mais perto de [p]
 * (as setas já percorridas somem).
 */
internal fun remainingPath(path: List<GeoPt>, p: GeoPt?): List<GeoPt> {
    if (p == null || path.size < 2) return path
    val proj = projectOnPath(path, p) ?: return path
    return listOf(proj.point) + path.subList(proj.segment + 1, path.size)
}

internal fun segmentLength(a: GeoPt, b: GeoPt): Double {
    val (x, y) = toLocal(a, b)
    return sqrt(x * x + y * y)
}

/**
 * Setas a cada [spacingM] metros ao longo do caminho, começando em
 * [firstAtM] (para a primeira não ficar em cima do ponto azul). Cada seta
 * aponta no sentido do caminho naquele trecho. No máximo [maxArrows].
 */
internal fun arrowsAlong(
    path: List<GeoPt>,
    spacingM: Double,
    firstAtM: Double = spacingM / 2,
    maxArrows: Int = 200
): List<RouteArrow> {
    if (path.size < 2 || spacingM <= 0.0) return emptyList()
    val out = ArrayList<RouteArrow>()
    var nextAt = firstAtM
    var walked = 0.0
    for (i in 0 until path.size - 1) {
        val a = path[i]
        val b = path[i + 1]
        val len = segmentLength(a, b)
        if (len <= 0.0) continue
        val bearing = bearingDegrees(a, b)
        while (nextAt <= walked + len) {
            val f = (nextAt - walked) / len
            out += RouteArrow(
                a.lat + (b.lat - a.lat) * f,
                a.lon + (b.lon - a.lon) * f,
                bearing
            )
            if (out.size >= maxArrows) return out
            nextAt += spacingM
        }
        walked += len
    }
    return out
}

/** Metros por pixel no zoom dado (projeção Web Mercator do Google Maps). */
internal fun metersPerPixel(zoom: Float, latitude: Double): Double =
    156_543.03392 * cos(Math.toRadians(latitude)) / 2.0.pow(zoom.toDouble())

// --- preferência e desenho ------------------------------------------------

private const val PREFS = "delivery_prefs"
private const val KEY_ARROW_MODE = "arrow_mode"

internal fun loadArrowMode(context: Context): ArrowMode {
    val name = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ARROW_MODE, null)
    } catch (e: Exception) {
        null
    }
    return ArrowMode.entries.firstOrNull { it.name == name } ?: ArrowMode.SELECTED
}

internal fun saveArrowMode(context: Context, mode: ArrowMode) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_ARROW_MODE, mode.name).apply()
}

/**
 * Seta apontando para CIMA (norte), no estilo haste + ponta aberta. Preta
 * com contorno branco para aparecer sobre qualquer rua. O marcador é "flat"
 * e girado pelo rumo, então gira junto com o mapa.
 */
internal fun buildArrowBitmap(density: Float): Bitmap {
    val w = (14f * density).toInt().coerceAtLeast(8)
    val h = (20f * density).toInt().coerceAtLeast(10)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val top = 2.5f * density
    val bottom = h - 2.5f * density
    val headH = 6f * density
    val headW = 5f * density
    val path = Path().apply {
        moveTo(cx, bottom)
        lineTo(cx, top)
        moveTo(cx - headW, top + headH)
        lineTo(cx, top)
        lineTo(cx + headW, top + headH)
    }
    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4.6f * density
        color = 0xF2FFFFFF.toInt()
    }
    val ink = Paint(halo).apply {
        strokeWidth = 2.2f * density
        color = 0xFF1A1A1A.toInt()
    }
    c.drawPath(path, halo)
    c.drawPath(path, ink)
    return bmp
}
