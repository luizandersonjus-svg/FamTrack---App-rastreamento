package com.famtrack.app.feature.places

import kotlinx.serialization.Serializable

/**
 * Local salvo da família (coleção nova `places` no backend).
 * Tipos: CASA, ESCOLA, TRABALHO, OUTRO.
 */
@Serializable
data class Place(
    val id: String? = null,
    val family_id: String,
    val name: String,
    val type: String = "OUTRO",
    val center_lat: Double,
    val center_lon: Double,
    val radius_meters: Int = 150,
    val alert_on_enter: Boolean? = null,
    val alert_on_exit: Boolean? = null,
    val geofence_id: String? = null,
    val created_by: String,
    val created_at: String? = null
)