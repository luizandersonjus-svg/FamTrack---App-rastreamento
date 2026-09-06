package com.famtrack.app.data.remote

import com.famtrack.app.data.model.Location
import com.famtrack.app.data.model.RoutePoint
import android.util.Log
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    /**
     * Histórico de rota de um usuário entre dois instantes (UTC), paginado no servidor.
     * PostgREST limita a 1.000 linhas por requisição por padrão: pagina até esgotar
     * ou atingir o teto de segurança. Retorna os pontos em ordem cronológica.
     */
    suspend fun getRouteHistoryForDay(
        familyId: String,
        userId: String,
        startUtc: String,
        endUtc: String
    ): List<RoutePoint> {
        val points = mutableListOf<RoutePoint>()
        var from = 0L
        while (true) {
            val page = client.from("route_history")
                .select {
                    filter {
                        eq("family_id", familyId)
                        eq("user_id", userId)
                        gte("recorded_at", startUtc)
                        lt("recorded_at", endUtc)
                    }
                    order("recorded_at", Order.ASCENDING)
                    range(from, from + ROUTE_PAGE_SIZE - 1)
                }
                .decodeList<RoutePoint>()
            points += page
            if (page.size < ROUTE_PAGE_SIZE) break
            from += ROUTE_PAGE_SIZE
            if (points.size >= MAX_ROUTE_POINTS_PER_DAY) {
                Log.w(TAG, "Dia com mais de $MAX_ROUTE_POINTS_PER_DAY pontos; resposta truncada")
                break
            }
        }
        return points
    }

    /**
     * Tenta gravar o ponto no schema completo (SQL 04). Se a migração
     * sql/04_route_history.sql ainda não foi aplicada no Supabase (colunas
     * accuracy/speed/bearing/battery_level/provider ausentes), a inserção
     * completa falha e um fallback legado com apenas as colunas originais é
     * tentado para o percurso não se perder. Retorna true quando o ponto foi
     * persistido por qualquer caminho.
     */
    suspend fun saveRoutePoint(routePoint: RoutePoint): Boolean {
        return try {
            client.from("route_history").insert(routePoint)
            true
        } catch (e: Exception) {
            Log.w(
                ROUTE_HISTORY_TAG,
                "Insert completo falhou (possível falta de sql/04_route_history.sql); tentando fallback legado",
                e
            )
            try {
                client.from("route_history")
                    .insert(
                        buildJsonObject {
                            put("family_id", routePoint.family_id)
                            put("user_id", routePoint.user_id)
                            put("latitude", routePoint.latitude)
                            put("longitude", routePoint.longitude)
                            put(
                                "recorded_at",
                                routePoint.recorded_at
                                    ?: java.time.Instant.now().toString()
                            )
                        }
                    )
                Log.w(
                    ROUTE_HISTORY_TAG,
                    "Fallback legado aplicado: aplique sql/04_route_history.sql no Supabase para histórico completo"
                )
                true
            } catch (e2: Exception) {
                Log.w(ROUTE_HISTORY_TAG, "Fallback legado também falhou; ponto descartado", e2)
                false
            }
        }
    }

    companion object {
        private const val TAG = "LocationRepository"
        private const val ROUTE_HISTORY_TAG = "FamTrackRouteHistory"
        private const val ROUTE_PAGE_SIZE = 1000L
        private const val MAX_ROUTE_POINTS_PER_DAY = 20_000
    }
}
