package com.famtrack.app.feature.sos

import android.content.Context

/**
 * Último fix conhecido pelo aparelho, com metadados de precisão/frescor.
 * Usado como fallback no SOS quando o app não tem um fix em memória.
 */
data class SosFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double? = null,
    val fixAtMillis: Long
)

/**
 * Persiste o último fix conhecido (SharedPreferences) para que o SOS
 * consiga enviar mesmo sem um fix em memória (app reaberto após kill/reboot).
 */
object LocationFixStore {

    private const val PREFS = "famtrack_last_fix"
    private const val KEY_LAT = "sos_lat"
    private const val KEY_LON = "sos_lon"
    private const val KEY_ACC = "sos_acc"
    private const val KEY_TIME = "sos_time"

    fun save(context: Context, fix: SosFix) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_LAT, fix.latitude.toFloat())
            .putFloat(KEY_LON, fix.longitude.toFloat())
            .putFloat(KEY_ACC, fix.accuracy?.toFloat() ?: -1f)
            .putLong(KEY_TIME, fix.fixAtMillis)
            .apply()
    }

    fun load(context: Context): SosFix? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = prefs.getLong(KEY_TIME, 0L)
        if (time == 0L) return null
        val acc = prefs.getFloat(KEY_ACC, -1f)
        return SosFix(
            latitude = prefs.getFloat(KEY_LAT, 0.0f).toDouble(),
            longitude = prefs.getFloat(KEY_LON, 0.0f).toDouble(),
            accuracy = if (acc >= 0f) acc.toDouble() else null,
            fixAtMillis = time
        )
    }
}