package com.famtrack.app.util

import java.text.SimpleDateFormat
import java.util.*

fun String.toDate(): Date? {
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        format.parse(this)
    } catch (e: Exception) {
        null
    }
}

fun String.toFormattedDate(): String {
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val outputFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val date = inputFormat.parse(this)
        outputFormat.format(date!!)
    } catch (e: Exception) {
        this
    }
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
