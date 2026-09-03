package com.famtrack.app.data.remote

import com.famtrack.app.data.model.Location
import com.famtrack.app.data.model.RoutePoint
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order

class LocationRepository {

    private val client = SupabaseClient.getInstance()

    suspend fun sendLocation(location: Location) {
        client.from("locations").insert(location)
    }

    suspend fun updateLocation(location: Location) {
        client.from("locations").update(location) {
            filter {
                eq("user_id", location.user_id)
                eq("family_id", location.family_id)
            }
        }
    }

    suspend fun upsertLocation(location: Location) {
        // Mantém apenas a última localização por usuário: atualiza se já existe, senão insere
        val existing = getUserLocation(location.family_id, location.user_id)
        if (existing != null) {
            updateLocation(location)
        } else {
            sendLocation(location)
        }
    }

    suspend fun getFamilyLocations(familyId: String): List<Location> {
        return client.from("locations")
            .select {
                filter {
                    eq("family_id", familyId)
                }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<Location>()
    }

    suspend fun getUserLocation(familyId: String, userId: String): Location? {
        return client.from("locations")
            .select {
                filter {
                    eq("family_id", familyId)
                    eq("user_id", userId)
                }
                order("created_at", Order.DESCENDING)
            }
            .decodeSingleOrNull<Location>()
    }

    suspend fun getRouteHistory(familyId: String, userId: String): List<RoutePoint> {
        return client.from("route_history")
            .select {
                filter {
                    eq("family_id", familyId)
                    eq("user_id", userId)
                }
                order("recorded_at", Order.DESCENDING)
            }
            .decodeList<RoutePoint>()
    }

    suspend fun saveRoutePoint(routePoint: RoutePoint) {
        client.from("route_history").insert(routePoint)
    }
}
