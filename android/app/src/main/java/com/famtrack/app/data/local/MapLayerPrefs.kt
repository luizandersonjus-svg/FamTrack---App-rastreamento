package com.famtrack.app.data.local

import android.content.Context

/**
 * Preferências locais das camadas do mapa principal (ETAPA 10D).
 *
 * Apenas a camada de Familiares fica ativa por padrão; as demais são opt-in
 * e persistem localmente neste arquivo (sem banco, sem rede).
 */
enum class MapLayer(val prefKey: String, val defaultOn: Boolean) {
    FAMILY("family", true),
    GEOFENCES("geofences", false),
    EVENTS("events", false),
    ROUTES("routes", false),
    WEATHER("weather", false)
}

object MapLayerPrefs {

    private const val PREFS_NAME = "map_layer_prefs"

    fun isOn(context: Context, layer: MapLayer): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(layer.prefKey, layer.defaultOn)

    fun setOn(context: Context, layer: MapLayer, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(layer.prefKey, on)
            .apply()
    }
}