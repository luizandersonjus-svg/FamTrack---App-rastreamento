package com.famtrack.app.feature.places

import com.famtrack.app.data.model.Geofence
import com.famtrack.app.data.remote.GeofenceRepository
import com.famtrack.app.data.remote.SupabaseClient
import io.github.jan.supabase.postgrest.from

/**
 * CRUD da coleção `places`. Ao salvar/excluir um local, também registra/remove
 * a geofence correspondente usando a função JÁ EXISTENTE (GeofenceRepository).
 */
class PlacesRepository {

    private val geofenceRepository = GeofenceRepository()

    private suspend fun registerGeofence(supabase: io.github.jan.supabase.SupabaseClient, place: Place): String? {
        return try {
            geofenceRepository.createGeofence(
                Geofence(
                    family_id = place.family_id,
                    name = place.name,
                    center_lat = place.center_lat,
                    center_lon = place.center_lon,
                    radius_meters = place.radius_meters.toDouble()
                )
            ).id
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun removeGeofence(geofenceId: String) {
        try {
            geofenceRepository.deleteGeofence(geofenceId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun createPlace(place: Place): Place? {
        val geofenceId = registerGeofence(SupabaseClient.getInstance(), place)
            ?: return null
        return SupabaseClient.getInstance().from("places")
            .insert(place.copy(geofence_id = geofenceId))
            .decodeSingle<Place>()
    }

    suspend fun getFamilyPlaces(familyId: String): List<Place> {
        return SupabaseClient.getInstance().from("places")
            .select {
                filter {
                    eq("family_id", familyId)
                }
            }
            .decodeList<Place>()
    }

    suspend fun updatePlace(place: Place) {
        SupabaseClient.getInstance().from("places")
            .update(place) {
                filter {
                    eq("id", place.id!!)
                }
            }
        // Mantém o raio/nome da geofence existente sincronizado
        place.geofence_id?.let { geofenceId ->
            try {
                geofenceRepository.updateGeofence(
                    Geofence(
                        id = geofenceId,
                        family_id = place.family_id,
                        name = place.name,
                        center_lat = place.center_lat,
                        center_lon = place.center_lon,
                        radius_meters = place.radius_meters.toDouble(),
                        color = "#FF0000"
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun deletePlace(place: Place) {
        place.geofence_id?.let { removeGeofence(it) }
        try {
            SupabaseClient.getInstance().from("places")
                .delete {
                    filter {
                        eq("id", place.id!!)
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}