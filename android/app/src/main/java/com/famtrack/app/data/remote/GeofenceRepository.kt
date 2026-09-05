package com.famtrack.app.data.remote

import com.famtrack.app.data.model.Geofence
import io.github.jan.supabase.postgrest.from

class GeofenceRepository {

    private val client = SupabaseClient.getInstance()

    // Criar geofence
    suspend fun createGeofence(geofence: Geofence): Geofence {
        return client.from("geofences")
            .insert(geofence)
            .decodeSingle<Geofence>()
    }

    // Obter geofences da família
    suspend fun getFamilyGeofences(familyId: String): List<Geofence> {
        return client.from("geofences")
            .select {
                filter {
                    eq("family_id", familyId)
                }
            }
            .decodeList<Geofence>()
    }

    // Atualizar geofence
    suspend fun updateGeofence(geofence: Geofence) {
        val payload = mutableMapOf<String, Any?>(
            "name" to geofence.name,
            "center_lat" to geofence.center_lat,
            "center_lon" to geofence.center_lon,
            "radius_meters" to geofence.radius_meters,
            "color" to geofence.color,
            "active" to geofence.active
        )
        geofence.placeId?.let { payload["place_id"] = it }
        client.from("geofences")
            .update(payload) {
                filter {
                    eq("id", geofence.id!!)
                }
            }
    }

    // Vincula explicitamente uma geofence a um place_id (relação F6)
    suspend fun linkPlaceToGeofence(geofenceId: String, placeId: String) {
        client.from("geofences")
            .update(mapOf("place_id" to placeId)) {
                filter {
                    eq("id", geofenceId)
                }
            }
    }

    // Deletar geofence
    suspend fun deleteGeofence(geofenceId: String) {
        client.from("geofences")
            .delete {
                filter {
                    eq("id", geofenceId)
                }
            }
    }

    // Ativar/desativar geofence
    suspend fun toggleGeofence(geofenceId: String, active: Boolean) {
        client.from("geofences")
            .update(mapOf("active" to active)) {
                filter {
                    eq("id", geofenceId)
                }
            }
    }
}
