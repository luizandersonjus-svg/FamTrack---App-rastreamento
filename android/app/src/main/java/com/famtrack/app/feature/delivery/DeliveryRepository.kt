package com.famtrack.app.feature.delivery

import com.famtrack.app.data.remote.FamilyRepository
import com.famtrack.app.data.remote.SupabaseClient
import io.github.jan.supabase.auth.auth
import com.famtrack.app.feature.common.ErrorMessages
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * CRUD da tabela `deliveries`. Os payloads são montados campo a campo
 * (buildJsonObject) porque serializar o data class omite os campos iguais
 * ao valor padrão — mesmo cuidado do PlacesRepository.updatePlace.
 */
class DeliveryRepository {

    private val familyRepository = FamilyRepository()

    /** Família do usuário logado, ou null se não houver sessão/família. */
    suspend fun currentFamilyId(): String? {
        val user = SupabaseClient.getInstance().auth.currentUserOrNull() ?: return null
        return familyRepository.getUserFamily(user.id)?.family_id
    }

    /** Pacotes da rota atual (não arquivados), na ordem da rota. */
    suspend fun list(familyId: String): List<Delivery> {
        return SupabaseClient.getInstance().from("deliveries")
            .select {
                filter {
                    eq("family_id", familyId)
                    eq("archived", false)
                }
            }
            .decodeList<Delivery>()
            .inRouteOrder()
    }

    /** Pacotes de rotas já encerradas (Histórico), do mais recente ao mais antigo. */
    suspend fun listArchived(familyId: String): List<Delivery> {
        return SupabaseClient.getInstance().from("deliveries")
            .select {
                filter {
                    eq("family_id", familyId)
                    eq("archived", true)
                }
            }
            .decodeList<Delivery>()
            .sortedWith(
                compareByDescending<Delivery> { it.archived_at ?: "" }.thenBy { it.route_order }
            )
    }

    /**
     * Encerra a rota: arquiva todos os pacotes ativos da família, entregues
     * ou não. Nada é apagado; o lote inteiro recebe o mesmo archived_at, que
     * é como o Histórico agrupa cada rota encerrada.
     *
     * Com o sql/15 aplicado, o trigger troca archived_at (e delivered_at /
     * delivered_by em setDelivered) pela hora e pelo usuário do servidor;
     * os valores mandados daqui só valem em banco sem o 15.
     */
    suspend fun archiveActive(familyId: String) {
        val payload = buildJsonObject {
            put("archived", true)
            put("archived_at", java.time.Instant.now().toString())
        }
        SupabaseClient.getInstance().from("deliveries")
            .update(payload) {
                filter {
                    eq("family_id", familyId)
                    eq("archived", false)
                }
            }
    }

    /** Insere no fim da rota ([nextOrder] = maior route_order atual + 1). */
    suspend fun add(delivery: Delivery, nextOrder: Int) {
        val payload = buildJsonObject {
            put("family_id", delivery.family_id)
            put("package_number", delivery.package_number.trim())
            put("street", delivery.street.trim())
            put("house_number", delivery.house_number.trim())
            put("place_type", delivery.place_type)
            val note = delivery.note?.trim().orEmpty()
            if (note.isNotEmpty()) put("note", note)
            put("latitude", delivery.latitude)
            put("longitude", delivery.longitude)
            put("route_order", nextOrder)
            put("delivered", false)
        }
        SupabaseClient.getInstance().from("deliveries").insert(payload)
    }

    /** Marca/desmarca a entrega. Registra quando e por quem foi entregue. */
    suspend fun setDelivered(id: String, delivered: Boolean) {
        val userId = SupabaseClient.getInstance().auth.currentUserOrNull()?.id
        val payload = buildJsonObject {
            put("delivered", delivered)
            if (delivered) {
                put("delivered_at", java.time.Instant.now().toString())
                if (userId != null) put("delivered_by", userId)
            } else {
                put("delivered_at", JsonNull)
                put("delivered_by", JsonNull)
            }
        }
        SupabaseClient.getInstance().from("deliveries")
            .update(payload) {
                filter {
                    eq("id", id)
                }
            }
    }

    /**
     * Grava a nova posição na rota de cada pacote (id -> route_order).
     *
     * Caminho normal: RPC reorder_deliveries (sql/15), um UPDATE só, tudo ou
     * nada. Se a função ainda não existir no banco, grava um por um como
     * antes (pode ficar parcial se a internet cair no meio).
     */
    suspend fun saveOrder(familyId: String, orderById: Map<String, Int>) {
        if (orderById.isEmpty()) return
        val idsInOrder = orderById.entries.sortedBy { it.value }.map { it.key }
        try {
            SupabaseClient.getInstance().postgrest.rpc(
                "reorder_deliveries",
                buildJsonObject {
                    put("p_family_id", familyId)
                    put("p_ids", JsonArray(idsInOrder.map { JsonPrimitive(it) }))
                }
            )
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorMessages.logSafe(e, "reorder_deliveries indisponível; gravando um por um")
        }
        for ((id, order) in orderById) {
            val payload = buildJsonObject { put("route_order", order) }
            SupabaseClient.getInstance().from("deliveries")
                .update(payload) {
                    filter {
                        eq("id", id)
                    }
                }
        }
    }

    suspend fun delete(id: String) {
        SupabaseClient.getInstance().from("deliveries")
            .delete {
                filter {
                    eq("id", id)
                }
            }
    }
}
