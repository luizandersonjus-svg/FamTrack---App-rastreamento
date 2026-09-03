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
        client.from("geofences")
            .update(geofence) {
                filter {
                    eq("id", geofence.id!!)
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
