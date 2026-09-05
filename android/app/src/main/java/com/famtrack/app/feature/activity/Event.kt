package com.famtrack.app.feature.activity

import kotlinx.serialization.Serializable

/**
 * Evento da família (coleção nova `events` no backend).
 * Tipos: ENTER, EXIT, SOS, CHECKIN, LOW_BATTERY.
 */
@Serializable
data class Event(
    val id: String? = null,
    val family_id: String,
    val type: String,
    val member_id: String,
    val place_id: String? = null,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val created_at: String? = null
)