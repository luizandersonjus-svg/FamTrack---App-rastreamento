package com.famtrack.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val email: String? = null,
    val name: String? = null,
    val avatarUrl: String? = null,
    val phone: String? = null
)

@Serializable
data class Family(
    val id: String,
    val name: String,
    val creator_id: String,
    val invite_code: String? = null,
    val created_at: String? = null
)

@Serializable
data class FamilyMember(
    val id: String,
    val family_id: String,
    val user_id: String,
    val role: String = "member",
    val nickname: String? = null,
    val avatar_url: String? = null,
    val accepted: Boolean = false,
    val created_at: String? = null,
    // Dados do usuário (join)
    val user: User? = null
)

@Serializable
data class Location(
    val id: String? = null,
    val family_id: String,
    val user_id: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val bearing: Double? = null,
    val batteryLevel: Int? = null,
    val lastUpdatedAt: Long? = null,
    val created_at: String? = null,
    // Dados do usuário (join)
    val user: User? = null
)

@Serializable
data class Geofence(
    val id: String? = null,
    val family_id: String,
    val name: String,
    val center_lat: Double,
    val center_lon: Double,
    val radius_meters: Double = 100.0,
    val color: String = "#FF0000",
    val active: Boolean = true,
    val created_at: String? = null
)

@Serializable
data class SosAlert(
    val id: String? = null,
    val family_id: String,
    val user_id: String,
    val latitude: Double,
    val longitude: Double,
    val message: String = "SOS acionado!",
    val resolved: Boolean = false,
    val created_at: String? = null,
    // Dados do usuário (join)
    val user: User? = null
)

@Serializable
data class Notification(
    val id: String? = null,
    val family_id: String,
    val user_id: String,
    val title: String,
    val message: String,
    val type: String,
    val read: Boolean = false,
    val created_at: String? = null
)

@Serializable
data class RoutePoint(
    val id: String? = null,
    val family_id: String,
    val user_id: String,
    val latitude: Double,
    val longitude: Double,
    val recorded_at: String? = null
)
