package com.famtrack.app.data.remote

import com.famtrack.app.data.model.SosAlert
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SosRepository {

    private val client = SupabaseClient.getInstance()

    suspend fun triggerSos(
        familyId: String,
        userId: String,
        latitude: Double,
        longitude: Double,
        accuracy: Double? = null,
        fixAt: Long? = null,
        message: String = "SOS acionado!"
    ): SosAlert {
        val payload = buildJsonObject {
            put("family_id", familyId)
            put("user_id", userId)
            put("latitude", latitude)
            put("longitude", longitude)
            put("message", message)
            accuracy?.let { put("accuracy", it) }
            fixAt?.let { put("fix_at", java.time.Instant.ofEpochMilli(it).toString()) }
        }
        val alert = client.from("sos_alerts")
            .insert(payload)
            .decodeSingle<SosAlert>()

        // Notifica todos os membros da família (exceto o remetente)
        try {
            NotificationRepository().notifyFamily(
                familyId = familyId,
                senderUserId = userId,
                title = "Alerta SOS",
                message = "Um membro da familia acionou o SOS!",
                type = "sos"
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return alert
    }

    suspend fun getActiveSosAlerts(familyId: String): List<SosAlert> {
        return client.from("sos_alerts")
            .select {
                filter {
                    eq("family_id", familyId)
                    eq("resolved", false)
                }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<SosAlert>()
    }

    suspend fun resolveSos(sosId: String) {
        client.from("sos_alerts")
            .update(mapOf("resolved" to true)) {
                filter {
                    eq("id", sosId)
                }
            }
    }

    suspend fun getSosHistory(familyId: String, limit: Long = 50): List<SosAlert> {
        return client.from("sos_alerts")
            .select {
                filter {
                    eq("family_id", familyId)
                }
                order("created_at", Order.DESCENDING)
                limit(limit)
            }
            .decodeList<SosAlert>()
    }
}
