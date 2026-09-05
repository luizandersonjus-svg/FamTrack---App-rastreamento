package com.famtrack.app.feature.activity

import com.famtrack.app.data.remote.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order

/**
 * Registro e leitura de eventos de atividade da família.
 */
class ActivityRepository {

    suspend fun recordEvent(event: Event) {
        try {
            SupabaseClient.getInstance().from("events").insert(event)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun getFamilyEvents(
        familyId: String,
        limit: Long = 50,
        offset: Long = 0
    ): List<Event> {
        return SupabaseClient.getInstance().from("events")
            .select {
                filter {
                    eq("family_id", familyId)
                }
                order("created_at", Order.DESCENDING)
                limit(limit)
                range(offset, offset + limit - 1)
            }
            .decodeList<Event>()
    }

    suspend fun getMemberEvents(familyId: String, memberId: String): List<Event> {
        return SupabaseClient.getInstance().from("events")
            .select {
                filter {
                    eq("family_id", familyId)
                    eq("member_id", memberId)
                }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<Event>()
    }
}