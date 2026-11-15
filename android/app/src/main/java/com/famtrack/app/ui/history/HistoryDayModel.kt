package com.famtrack.app.ui.history

import android.location.Location
import androidx.compose.ui.graphics.Color
import com.famtrack.app.data.model.RoutePoint
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds

// ---------------------------------------------------------------------------
// Modelo da linha do tempo do dia (HIST-UX): o dia de UM membro vira uma
// sequência simples "Parada -> Trajeto -> Parada -> ...", no estilo das
// linhas do tempo do Google Maps / Life360. Reaproveita computeTrips (mesma
// detecção de paradas/percursos) — aqui só organizamos para a tela.
// ---------------------------------------------------------------------------

/**
 * Paleta de cores por membro — a MESMA do rastro ao vivo da Home (TRACK-1c,
 * HomeScreen.TRAIL_PALETTE / trailColorForMember). Mantenha as duas em
 * sincronia: o membro tem UMA cor em todo o app.
 */
private val MEMBER_PALETTE = listOf(
    Color(0xFFE53935), // vermelho
    Color(0xFF1E88E5), // azul
    Color(0xFF43A047), // verde
    Color(0xFFFB8C00), // laranja
    Color(0xFF8E24AA), // roxo
    Color(0xFF00ACC1), // ciano
    Color(0xFF3949AB), // indigo
    Color(0xFFD81B60), // rosa
    Color(0xFF00897B), // verde-água
    Color(0xFF6D4C41)  // marrom
)

/** Cor estável do membro: hash determinístico do userId (igual à Home). */
internal fun memberColorFor(userId: String?): Color {
    if (userId.isNullOrEmpty()) return MEMBER_PALETTE[1]
    return MEMBER_PALETTE[Math.floorMod(userId.hashCode(), MEMBER_PALETTE.size)]
}

/** Cores fixas dos marcadores A (saída) e B (chegada) — sempre com a letra. */
internal val START_MARKER_COLOR = Color(0xFF1B7F3B)
internal val END_MARKER_COLOR = Color(0xFFC62828)

/** Iniciais do nome (até 2 letras). */
internal fun memberInitials(name: String?): String =
    (name ?: "")
        .trim()
        .split(Regex("\\s+"))
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifEmpty { "?" }

/** Um item da linha do tempo do dia. */
internal sealed interface DayTimelineEntry {
    /**
     * Permanência num local. [anchorIndex] aponta para o rótulo do local
     * (0..trips.size): 0 = origem do 1º trajeto, k+1 = destino do trajeto k.
     * [isDayStart]/[isDayEnd] marcam o primeiro/último local do registro.
     */
    data class Stay(
        val anchorIndex: Int,
        val position: LatLng,
        val fromMillis: Long,
        val toMillis: Long,
        val isDayStart: Boolean,
        val isDayEnd: Boolean
    ) : DayTimelineEntry

    /** Deslocamento [tripIndex] (índice em TripsResult.trips). */
    data class Move(val tripIndex: Int) : DayTimelineEntry
}

/**
 * Posições dos locais do dia ("âncoras"): origem do 1º trajeto e destino de
 * cada trajeto. Sem trajetos, um único local (primeiro ponto).
 */
internal fun dayAnchorPositions(points: List<RoutePoint>, trips: List<TripSummary>): List<LatLng> {
    if (points.isEmpty()) return emptyList()
    if (trips.isEmpty()) {
        val p = points.first()
        return listOf(LatLng(p.latitude, p.longitude))
    }
    val out = ArrayList<LatLng>(trips.size + 1)
    val first = points[trips.first().pointStart.coerceIn(0, points.lastIndex)]
    out += LatLng(first.latitude, first.longitude)
    trips.forEach { t ->
        val p = points[t.pointEnd.coerceIn(0, points.lastIndex)]
        out += LatLng(p.latitude, p.longitude)
    }
    return out
}

/**
 * Monta a linha do tempo: sempre começa e termina num local, com os
 * trajetos entre eles. Sem trajetos, um único local "o dia todo".
 */
internal fun buildDayTimeline(
    points: List<RoutePoint>,
    trips: List<TripSummary>,
    anchors: List<LatLng>
): List<DayTimelineEntry> {
    if (points.isEmpty() || anchors.isEmpty()) return emptyList()
    val firstMillis = points.firstNotNullOfOrNull { parseTimestampMillis(it.recorded_at) }
    val lastMillis = points.asReversed().firstNotNullOfOrNull { parseTimestampMillis(it.recorded_at) }
    if (trips.isEmpty()) {
        val from = firstMillis ?: return emptyList()
        return listOf(
            DayTimelineEntry.Stay(
                anchorIndex = 0,
                position = anchors[0],
                fromMillis = from,
                toMillis = lastMillis ?: from,
                isDayStart = true,
                isDayEnd = true
            )
        )
    }
    val out = mutableListOf<DayTimelineEntry>()
    val t0 = trips.first()
    out += DayTimelineEntry.Stay(
        anchorIndex = 0,
        position = anchors[0],
        fromMillis = minOf(firstMillis ?: t0.startMillis, t0.startMillis),
        toMillis = t0.startMillis,
        isDayStart = true,
        isDayEnd = false
    )
    trips.forEachIndexed { k, trip ->
        out += DayTimelineEntry.Move(k)
        val next = trips.getOrNull(k + 1)
        val anchor = anchors.getOrElse(k + 1) { anchors.last() }
        out += if (next != null) {
            DayTimelineEntry.Stay(k + 1, anchor, trip.endMillis, next.startMillis, false, false)
        } else {
            DayTimelineEntry.Stay(
                anchorIndex = k + 1,
                position = anchor,
                fromMillis = trip.endMillis,
                toMillis = maxOf(lastMillis ?: trip.endMillis, trip.endMillis),
                isDayStart = false,
                isDayEnd = true
            )
        }
    }
    return out
}

/** Meio de deslocamento ESTIMADO pela velocidade (nunca afirmado como certo). */
internal enum class TripMode { WALK, VEHICLE, UNKNOWN }

internal fun tripModeOf(trip: TripSummary): TripMode {
    val avg = trip.avgSpeedKmh ?: return TripMode.UNKNOWN
    val max = trip.maxSpeedKmh
    return when {
        avg < 6.5 && (max == null || max < 14.0) -> TripMode.WALK
        avg >= 15.0 || (max != null && max >= 35.0) -> TripMode.VEHICLE
        else -> TripMode.UNKNOWN
    }
}

/**
 * Geometria de exibição de um trajeto: trechos contínuos (sem gaps de
 * gravação), cada um simplificado por Douglas–Peucker. Divide ANTES de
 * simplificar para não criar falsos gaps.
 */
internal fun tripDisplaySegments(slice: List<RoutePoint>): List<List<LatLng>> =
    splitSegments(slice)
        .filter { it.size >= 2 }
        .map { seg ->
            val simplified = if (seg.size > 300) douglasPeucker(seg, 8.0) else seg
            simplified.map { LatLng(it.latitude, it.longitude) }
        }

internal fun boundsOf(points: List<LatLng>): LatLngBounds? {
    if (points.isEmpty()) return null
    val builder = LatLngBounds.builder()
    points.forEach { builder.include(it) }
    return builder.build()
}

/** Seta de direção desenhada sobre a linha do trajeto selecionado. */
internal data class DirectionArrow(val position: LatLng, val bearing: Float)

private fun cumulativeMeters(path: List<LatLng>): DoubleArray {
    val cum = DoubleArray(path.size)
    for (i in 1 until path.size) {
        cum[i] = cum[i - 1] + haversineMeters(
            path[i - 1].latitude, path[i - 1].longitude,
            path[i].latitude, path[i].longitude
        )
    }
    return cum
}

private fun pointAtDistance(path: List<LatLng>, cum: DoubleArray, target: Double): LatLng {
    if (path.size == 1) return path[0]
    var i = 1
    while (i < path.lastIndex && cum[i] < target) i++
    val a = path[i - 1]
    val b = path[i]
    val segLen = cum[i] - cum[i - 1]
    val t = if (segLen <= 0.0) 0.0 else ((target - cum[i - 1]) / segLen).coerceIn(0.0, 1.0)
    return LatLng(
        a.latitude + (b.latitude - a.latitude) * t,
        a.longitude + (b.longitude - a.longitude) * t
    )
}

/** Ponto no meio do caminho (por distância) — onde fica o número do trajeto. */
internal fun pathMidpoint(path: List<LatLng>): LatLng? {
    if (path.isEmpty()) return null
    if (path.size == 1) return path[0]
    val cum = cumulativeMeters(path)
    return pointAtDistance(path, cum, cum.last() / 2.0)
}

/**
 * Setas distribuídas ao longo do caminho (1 a [maxArrows], ~1 a cada
 * [spacingMeters]). A direção é suavizada (±25 m) para não "tremer" em
 * pontos ruidosos.
 */
internal fun directionArrows(
    path: List<LatLng>,
    maxArrows: Int = 7,
    spacingMeters: Double = 250.0
): List<DirectionArrow> {
    if (path.size < 2) return emptyList()
    val cum = cumulativeMeters(path)
    val total = cum.last()
    if (total < 60.0) return emptyList()
    val count = (total / spacingMeters).toInt().coerceIn(1, maxArrows)
    val out = ArrayList<DirectionArrow>(count)
    for (k in 0 until count) {
        val target = total * (k + 0.5) / count
        val pos = pointAtDistance(path, cum, target)
        val from = pointAtDistance(path, cum, (target - 25.0).coerceAtLeast(0.0))
        val to = pointAtDistance(path, cum, (target + 25.0).coerceAtMost(total))
        val res = FloatArray(2)
        Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, res)
        if (res[0] > 0.5f) out += DirectionArrow(pos, res[1])
    }
    return out
}

// ---------------------------------------------------------------------------
// Bitmaps dos marcadores (A/B, número do trajeto e seta de direção)
// ---------------------------------------------------------------------------

/** Círculo colorido com borda branca e um texto curto ("A", "B", "3"). */
internal fun labeledCircleBitmap(
    density: Float,
    text: String,
    fillArgb: Int,
    diameterDp: Float = 34f,
    borderDp: Float = 3f
): android.graphics.Bitmap {
    val size = (diameterDp * density).toInt().coerceAtLeast(1)
    val border = (borderDp * density).coerceAtLeast(1.5f)
    val r = size / 2f
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val white = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
    }
    canvas.drawCircle(r, r, r, white)
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = fillArgb
    }
    canvas.drawCircle(r, r, r - border, fill)
    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
        )
        textSize = size * (if (text.length > 1) 0.40f else 0.50f)
    }
    val fm = textPaint.fontMetrics
    canvas.drawText(text, r, r - (fm.ascent + fm.descent) / 2f, textPaint)
    return bmp
}

/**
 * Seta (chevron) branca com contorno escuro, apontando para o NORTE; o
 * Marker gira pelo bearing (flat = true).
 */
internal fun directionArrowBitmap(density: Float, outlineArgb: Int): android.graphics.Bitmap {
    val size = (18f * density).toInt().coerceAtLeast(1)
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val w = size.toFloat()
    val path = android.graphics.Path().apply {
        moveTo(w * 0.5f, w * 0.12f)
        lineTo(w * 0.88f, w * 0.82f)
        lineTo(w * 0.5f, w * 0.62f)
        lineTo(w * 0.12f, w * 0.82f)
        close()
    }
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }
    val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = outlineArgb
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = (1.5f * density).coerceAtLeast(1f)
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    canvas.drawPath(path, fill)
    canvas.drawPath(path, stroke)
    return bmp
}
