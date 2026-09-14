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

/**
 * Mapa-base (visualização) do mapa principal. Diferente das camadas de
 * informação, é de escolha única: mudar o tipo só troca os tiles exibidos
 * pelo Google Maps e nunca dispara qualquer consulta de dados da família
 * (regra da auditoria L3 C1).
 */
enum class MapBaseType(val prefKey: String) {
    NORMAL("normal"),
    SATELLITE("satellite"),
    HYBRID("hybrid"),
    TERRAIN("terrain")
}

object MapLayerPrefs {

    private const val PREFS_NAME = "map_layer_prefs"
    private const val KEY_BASE = "baseType"

    fun isOn(context: Context, layer: MapLayer): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(layer.prefKey, layer.defaultOn)

    fun setOn(context: Context, layer: MapLayer, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(layer.prefKey, on)
            .apply()
    }

    fun getBaseType(context: Context): MapBaseType {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BASE, MapBaseType.NORMAL.prefKey)
            ?: return MapBaseType.NORMAL
        return MapBaseType.entries.firstOrNull { it.prefKey == stored } ?: MapBaseType.NORMAL
    }

    fun setBaseType(context: Context, type: MapBaseType) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE, type.prefKey)
            .apply()
    }
}