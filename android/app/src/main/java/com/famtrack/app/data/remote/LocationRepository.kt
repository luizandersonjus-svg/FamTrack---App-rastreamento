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
     * Tenta gravar o ponto no schema completo (SQL 04). Se a migração ainda
     * não foi aplicada no Supabase (colunas de enriquecimento inexistentes),
     * detecta o ERRO DE SCHEMA e cai automaticamente para o payload mínimo
     * (family_id, user_id, latitude, longitude, recorded_at) para o percurso
     * não se perder — mantendo compatibilidade com schema antigo e novo.
     *
     * Falhas transitórias (rede, rate limit) NÃO disparam o fallback: são
     * reportadas com mensagem curta e o ponto é descartado; o serviço tenta de
     * novo no próximo fix. Retorna true quando o ponto foi persistido por
     * qualquer caminho.
     */
    suspend fun saveRoutePoint(routePoint: RoutePoint): Boolean {
        return try {
            client.from("route_history").insert(routePoint)
            true
        } catch (e: Exception) {
            if (isMissingColumnError(e)) {
                Log.w(ROUTE_TAG, "schema antigo detectado; fallback mínimo usado")
                try {
                    client.from("route_history")
                        .insert(minimalRoutePayload(routePoint))
                    true
                } catch (e2: Exception) {
                    Log.w(ROUTE_TAG, "rota: falha ao salvar: ${shortMessage(e2)}")
                    false
                }
            } else {
                Log.w(ROUTE_TAG, "rota: falha ao salvar: ${shortMessage(e)}")
                false
            }
        }
    }

    /** Payload mínimo exigido pelo schema original de route_history. */
    private fun minimalRoutePayload(routePoint: RoutePoint) =
        buildJsonObject {
            put("family_id", routePoint.family_id)
            put("user_id", routePoint.user_id)
            put("latitude", routePoint.latitude)
            put("longitude", routePoint.longitude)
            put(
                "recorded_at",
                routePoint.recorded_at ?: java.time.Instant.now().toString()
            )
        }

    /**
     * Erro de schema (SQL 03/04) quando uma/mais colunas não existem na tabela.
     * Firewall PostgREST costuma expor código 42703 ou "column … does not exist".
     */
    private fun isMissingColumnError(e: Exception): Boolean {
        val text = listOfNotNull(e.message, e.cause?.message)
            .joinToString(" ")
            .lowercase()
        return text.contains("does not exist") ||
            text.contains("undefined_column") ||
            text.contains("42703") ||
            text.contains("column")
    }

    /** Mensagem curta e sem dados sensíveis (não loga stacktrace/URLs completos). */
    private fun shortMessage(e: Exception): String =
        e.localizedMessage
            ?.substringBefore('\n')
            ?.trim()
            ?.take(120)
            ?.takeIf { it.isNotBlank() }
            ?: "erro desconhecido"

    companion object {
        private const val TAG = "LocationRepository"
        private const val ROUTE_TAG = "FamTrackRoute"
        private const val ROUTE_PAGE_SIZE = 1000L
        private const val MAX_ROUTE_POINTS_PER_DAY = 20_000
    }
}
