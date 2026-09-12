package com.famtrack.app.util

import java.text.SimpleDateFormat
import java.util.*

/**
 * Converte timestamp ISO-8601 do backend (timestamptz, UTC) em millis de época.
 * Aceita `2026-09-12T10:00:00Z`, variantes com fração (.123Z) e com offset
 * explícito (+00:00, +02:00). Dados antigos sem sufixo de fuso são tratados
 * como UTC (o armazenamento do FamTrack é UTC desde sempre).
 */
fun parseIsoInstantMillis(timestamp: String?): Long? {
    if (timestamp == null) return null
    val value = timestamp.trim()
    if (value.isEmpty()) return null
    val instant = runCatching { java.time.Instant.parse(value) }
        .getOrElse { runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull() }
    return instant?.toEpochMilli() ?: legacyUtcMillis(value)
}

/** Fallback para registros legados sem marca de fuso: interpreta como UTC. */
private fun legacyUtcMillis(value: String): Long? {
    val cleaned = value.substringBefore('.')
    val patterns = arrayOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss")
    for (pattern in patterns) {
        try {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return fmt.parse(cleaned)?.time
        } catch (e: Exception) {
            // tenta o próximo padrão
        }
    }
    return null
}

fun String.toDate(): Date? = parseIsoInstantMillis(this)?.let(::Date)

fun String.toFormattedDate(): String {
    val millis = parseIsoInstantMillis(this) ?: return this
    val outputFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return outputFormat.format(Date(millis))
}

fun Double.toFormattedCoordinate(): String {
    return String.format("%.6f", this)
}

fun calculateDistance(
    lat1: Double, lon1: Double,
    lat2: Double, lon2: Double
): Double {
    val results = FloatArray(1)
    android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
    return results[0].toDouble()
}

fun isInsideGeofence(
    userLat: Double, userLon: Double,
    centerLat: Double, centerLon: Double,
    radiusMeters: Double
): Boolean {
    val distance = calculateDistance(userLat, userLon, centerLat, centerLon)
    return distance <= radiusMeters
}
