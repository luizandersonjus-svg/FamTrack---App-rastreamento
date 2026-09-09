package com.famtrack.app.data.remote

import com.famtrack.app.data.model.Location
import com.famtrack.app.data.model.RoutePoint
import com.famtrack.app.data.offline.RoutePointStore
import com.famtrack.app.data.offline.StoredPoint
import android.util.Log
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/** Falha transitória (rede/timeout/5xx): mantém os pontos e tenta de novo. */
internal class RouteSyncRetriableException(message: String) : Exception(message)

/** Falha permanente (auth/RLS/sem sessão): mantém os pontos e aguarda nova sessão. */
internal class RouteSyncPermanentException(message: String) : Exception(message)

/** Código curto e sem detalhes sensíveis para os logs de sincronização. */
internal fun routeSyncErrorCode(e: Exception): String {
    val text = listOfNotNull(e.message, e.cause?.message).filter { it.isNotBlank() }
    val status = text.flatMap { Regex("\\b(4\\d\\d|5\\d\\d)\\b").findAll(it).map { m -> m.value } }.firstOrNull()
    return status ?: text.firstOrNull()?.take(6) ?: "erra"
}

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
                // Limite 1: atualiza sempre a linha mais recente daquele usuário.
                // Duplicatas antigas (idade pré-constraint única) não travam mais o
                // upsert — sem ele, decodeSingleOrNull lançava e o feed congelava.
                range(0, 0)
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

    // --------------------------------------------------------------------------
    // Sincronização em lote da fila offline (Fase 1)
    // --------------------------------------------------------------------------

    /**
     * Drena a fila offline do usuário autenticado em lotes de até
     * [MAX_SYNC_BATCH] pontos. Sucesso = remove apenas os confirmados.
     * Erro retriable/permanente vira exceção específica (mantém a fila).
     */
    internal suspend fun syncRouteQueue(store: RoutePointStore) {
        val uid = client.auth.currentUserOrNull()?.id
            ?: throw RouteSyncPermanentException("sem sessão")
        // Pontos de outro usuário não são descartáveis: vão para a quarentena.
        store.quarantineForeignPoints(uid)
        val batch = store.snapshotFor(uid).take(MAX_SYNC_BATCH)
        if (batch.isEmpty()) {
            Log.d(SYNC_TAG, "lote enviado=0 ok=0")
            return
        }

        val synced = try {
            insertBatch(batch, enriched = true)
            batch.map { it.id }.toSet()
        } catch (e: Exception) {
            when {
                isMissingColumnError(e) -> {
                    Log.w(ROUTE_TAG, "schema antigo: fallback mínimo")
                    try {
                        insertBatch(batch, enriched = false)
                        batch.map { it.id }.toSet()
                    } catch (e2: Exception) {
                        resolveIndividual(batch, e2)
                    }
                }
                isConflictError(e) -> {
                    Log.w(SYNC_TAG, "conflito 23505: individual")
                    resolveIndividual(batch, e)
                }
                else -> throw classifyForSync(e)
            }
        }

        if (synced.isNotEmpty()) {
            store.removeSynced(synced)
            Log.d(SYNC_TAG, "lote enviado=${batch.size} ok=${synced.size}")
        } else {
            Log.d(SYNC_TAG, "lote enviado=${batch.size} ok=0")
        }
    }

    /**
     * Lote em insert único via PostgREST. Inclui o [StoredPoint.id] como chave
     * primária; um conflito do mesmo UUID indica resposta perdida (já sincronizado).
     */
    private suspend fun insertBatch(points: List<StoredPoint>, enriched: Boolean) {
        val array = buildJsonArray {
            points.forEach { add(pointToJson(it, enriched)) }
        }
        client.from("route_history").insert(array)
    }

    private suspend fun insertSingle(point: StoredPoint, enriched: Boolean) {
        client.from("route_history").insert(pointToJson(point, enriched))
    }

    /**
     * Conflito misto (alguns UUIDs já sincronizados) ou falha do fallback mínimo:
     * tenta ponto a ponto; conflito individual do MESMO UUID é tratado como
     * "já sincronizado". Outros erros são classificados e propagados.
     */
    private suspend fun resolveIndividual(batch: List<StoredPoint>, trigger: Exception): Set<String> {
        val synced = HashSet<String>()
        for (point in batch) {
            val ok = try {
                insertSingle(point, enriched = true)
                true
            } catch (e: Exception) {
                val text = errText(e)
                when {
                    isConflictError(e) -> true
                    isMissingColumnError(e) -> {
                        try {
                            insertSingle(point, enriched = false)
                            true
                        } catch (e2: Exception) {
                            if (isConflictError(e2)) true else throw classifyForSync(e2)
                        }
                    }
                    else -> throw classifyForSync(e)
                }
            }
            if (ok) synced += point.id
        }
        return synced
    }

    /**
     * Fallback mínimo NUNCA é usado para conflito/fallback de RLS, auth ou rede:
     * só quando o erro comprova coluna inexistente.
     */
    private fun pointToJson(point: StoredPoint, enriched: Boolean) = buildJsonObject {
        put("id", point.id)
        put("family_id", point.familyId)
        put("user_id", point.userId)
        put("latitude", point.latitude)
        put("longitude", point.longitude)
        put("recorded_at", point.recordedAt ?: java.time.Instant.now().toString())
        if (enriched) {
            point.accuracy?.let { put("accuracy", it) }
            point.speed?.let { put("speed", it) }
            point.bearing?.let { put("bearing", it) }
            point.batteryLevel?.let { put("battery_level", it) }
            point.provider?.let { put("provider", it) }
        }
    }

    /** Conflito de chave primária do mesmo UUID (resposta perdida). */
    private fun isConflictError(e: Exception): Boolean {
        val text = errText(e)
        return text.contains("23505") ||
            text.contains("duplicate key") ||
            text.contains("already exists") ||
            text.contains("duplicate")
    }

    /** Erro permanente: auth/RLS/sem permissão não reenviáveis nesta sessão. */
    private fun isAuthOrRlsError(e: Exception): Boolean {
        val text = errText(e)
        return text.contains("401") ||
            text.contains("403") ||
            text.contains("jwt") ||
            text.contains("unauthorized") ||
            text.contains("api key") ||
            text.contains("forbidden") ||
            text.contains("not authenticated") ||
            text.contains("row-level security") ||
            text.contains("permission denied") ||
            text.contains("no permission") ||
            text.contains("not authorized")
    }

    private fun classifyForSync(e: Exception): Exception =
        if (isAuthOrRlsError(e)) {
            RouteSyncPermanentException(shortMessage(e))
        } else {
            RouteSyncRetriableException(shortMessage(e))
        }

    private fun errText(e: Exception): String =
        listOfNotNull(e.message, e.cause?.message).joinToString(" ").lowercase()

    companion object {
        private const val TAG = "LocationRepository"
        private const val ROUTE_TAG = "FamTrackRoute"
        private const val SYNC_TAG = "FamTrackRouteSync"
        private const val ROUTE_PAGE_SIZE = 1000L
        private const val MAX_ROUTE_POINTS_PER_DAY = 20_000
        private const val MAX_SYNC_BATCH = 20
    }
}
