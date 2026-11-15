package com.famtrack.app.feature.delivery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Veículo que representa o entregador no mapa de entregas. */
internal enum class VehicleType(val label: String) {
    CAR("Carro"),
    MOTO("Moto")
}

private const val PREFS = "delivery_prefs"
private const val KEY_VEHICLE = "vehicle"

internal fun loadVehicleType(context: Context): VehicleType {
    val name = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VEHICLE, null)
    } catch (e: Exception) {
        null
    }
    return VehicleType.entries.firstOrNull { it.name == name } ?: VehicleType.CAR
}

internal fun saveVehicleType(context: Context, type: VehicleType) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_VEHICLE, type.name).apply()
}

/** Abaixo disso (m/s, ~5 km/h) o rumo do GPS é ruído: o veículo fica como estava. */
internal const val HEADING_MIN_SPEED_MPS = 1.4f

/** Deslocamento mínimo (m) para estimar o rumo pelos dois últimos pontos. */
internal const val HEADING_MIN_MOVE_M = 6.0

/**
 * Para onde o veículo aponta. Usa o rumo do GPS quando o aparelho está em
 * movimento; sem ele, o rumo entre o ponto anterior e o atual (se andou o
 * bastante); parado, mantém o último rumo (não fica girando no semáforo).
 */
internal fun nextHeading(
    previous: Float?,
    gpsBearing: Float?,
    speedMps: Float?,
    from: GeoPt?,
    to: GeoPt
): Float? {
    if (gpsBearing != null && speedMps != null && speedMps >= HEADING_MIN_SPEED_MPS) {
        return ((gpsBearing % 360f) + 360f) % 360f
    }
    if (from != null && segmentLength(from, to) >= HEADING_MIN_MOVE_M) {
        return bearingDegrees(from, to)
    }
    return previous
}

private const val BODY = 0xFF1E6FD9.toInt()
private const val BODY_LIGHT = 0xFF4A8FE8.toInt()
private const val GLASS = 0xFF22313A.toInt()
private const val TYRE = 0xFF1C1C1C.toInt()
private const val LIGHT = 0xFFFFF59D.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
private const val SHADOW = 0x40000000

/**
 * Veículo visto de cima, com a FRENTE PARA CIMA (norte). O marcador é flat
 * e girado pelo rumo, então gira junto com o mapa. Desenhado em unidades de
 * 1dp ([u] px), com margem para a sombra e o contorno.
 */
internal fun buildVehicleBitmap(type: VehicleType, density: Float): Bitmap {
    val u = density * 1.1f
    val pad = 3f
    val (w, h) = when (type) {
        VehicleType.CAR -> 22f to 40f
        VehicleType.MOTO -> 16f to 36f
    }
    val bmp = Bitmap.createBitmap(
        ((w + pad * 2) * u).toInt(),
        ((h + pad * 2) * u).toInt(),
        Bitmap.Config.ARGB_8888
    )
    val c = Canvas(bmp)
    c.scale(u, u)
    c.translate(pad, pad)
    when (type) {
        VehicleType.CAR -> drawCar(c)
        VehicleType.MOTO -> drawMoto(c)
    }
    return bmp
}

private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.STROKE
    strokeWidth = width
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

private fun poly(vararg xy: Float) = Path().apply {
    moveTo(xy[0], xy[1])
    var i = 2
    while (i < xy.size) {
        lineTo(xy[i], xy[i + 1])
        i += 2
    }
    close()
}

private fun drawCar(c: Canvas) {
    // Sombra.
    c.drawRoundRect(RectF(2.5f, 2.5f, 20.5f, 41f), 6f, 6f, fill(SHADOW))
    // Rodas (aparecem dos lados).
    val tyre = fill(TYRE)
    for (y in floatArrayOf(7f, 27f)) {
        c.drawRoundRect(RectF(0.5f, y, 3.5f, y + 6f), 1.2f, 1.2f, tyre)
        c.drawRoundRect(RectF(18.5f, y, 21.5f, y + 6f), 1.2f, 1.2f, tyre)
    }
    // Carroceria.
    val body = RectF(2f, 1f, 20f, 39f)
    c.drawRoundRect(body, 6f, 6f, fill(BODY))
    c.drawRoundRect(body, 6f, 6f, stroke(WHITE, 1.5f))
    // Para-brisa, teto e vidro traseiro.
    c.drawPath(poly(5f, 17f, 17f, 17f, 16f, 11f, 6f, 11f), fill(GLASS))
    c.drawRoundRect(RectF(5.5f, 17f, 16.5f, 29f), 2f, 2f, fill(BODY_LIGHT))
    c.drawPath(poly(6f, 29f, 16f, 29f, 15.5f, 33f, 6.5f, 33f), fill(GLASS))
    // Faróis (indicam a frente).
    c.drawRoundRect(RectF(4f, 2.3f, 7.5f, 4.5f), 1f, 1f, fill(LIGHT))
    c.drawRoundRect(RectF(14.5f, 2.3f, 18f, 4.5f), 1f, 1f, fill(LIGHT))
}

private fun drawMoto(c: Canvas) {
    // Sombra.
    c.drawRoundRect(RectF(5.6f, 1.2f, 11.4f, 36.4f), 3f, 3f, fill(SHADOW))
    // Rodas dianteira e traseira.
    val tyre = fill(TYRE)
    c.drawRoundRect(RectF(6.3f, 0.5f, 9.7f, 9.5f), 1.7f, 1.7f, tyre)
    c.drawRoundRect(RectF(6.3f, 26.5f, 9.7f, 35.5f), 1.7f, 1.7f, tyre)
    // Corpo.
    val body = RectF(4.8f, 6.5f, 11.2f, 29.5f)
    c.drawRoundRect(body, 3f, 3f, fill(BODY))
    c.drawRoundRect(body, 3f, 3f, stroke(WHITE, 1.3f))
    // Guidão com manoplas.
    c.drawLine(1.5f, 10f, 14.5f, 10f, stroke(WHITE, 3f))
    c.drawLine(1.5f, 10f, 14.5f, 10f, stroke(TYRE, 1.6f))
    // Piloto: ombros e capacete.
    c.drawOval(RectF(2.2f, 13.5f, 13.8f, 22f), fill(BODY_LIGHT))
    c.drawOval(RectF(2.2f, 13.5f, 13.8f, 22f), stroke(WHITE, 1f))
    c.drawCircle(8f, 17.2f, 3.9f, fill(TYRE))
    c.drawCircle(8f, 17.2f, 3.9f, stroke(WHITE, 0.9f))
    c.drawCircle(7f, 16f, 1.1f, fill(0x66FFFFFF))
    // Farol.
    c.drawCircle(8f, 2.2f, 1.2f, fill(LIGHT))
}
