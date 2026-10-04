package com.famtrack.app.data.remote

import com.famtrack.app.data.model.Geofence
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        // JsonObject explícito: Map<String, Any?> não é serializável pelo
        // kotlinx.serialization e fazia o update falhar em tempo de execução.
        val payload = buildJsonObject {
            put("name", geofence.name)
            put("center_lat", geofence.center_lat)
            put("center_lon", geofence.center_lon)
            put("radius_meters", geofence.radius_meters)
            put("color", geofence.color)
            put("active", geofence.active)
            geofence.placeId?.let { put("place_id", it) }
        }
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
