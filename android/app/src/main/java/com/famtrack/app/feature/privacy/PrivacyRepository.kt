package com.famtrack.app.feature.privacy

import com.famtrack.app.data.remote.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Flag de privacidade do membro (colunas opcionais adicionadas em family_members).
 */
data class MemberFlags(
    val user_id: String,
    val sharing_paused: Boolean = false,
    val share_health: Boolean = false
)

/**
 * Leitura e gravação das flags de privacidade em family_members (F7).
 */
class PrivacyRepository {

    private fun JsonObject.toFlags(): MemberFlags? {
        val uid = this["user_id"]?.jsonPrimitive?.content
            ?: return null
        return MemberFlags(
            user_id = uid,
            sharing_paused = this["sharing_paused"]?.jsonPrimitive?.booleanOrNull ?: false,
            share_health = this["share_health"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }

    suspend fun getMyFlags(userId: String): MemberFlags? {
        return SupabaseClient.getInstance().from("family_members")
            .select {
                filter {
                    eq("user_id", userId)
                    eq("accepted", true)
                }
            }
            .decodeList<JsonObject>()
            .firstNotNullOfOrNull { it.toFlags() }
    }

    suspend fun getMemberFlags(familyId: String): List<MemberFlags> {
        return SupabaseClient.getInstance().from("family_members")
            .select {
                filter {
                    eq("family_id", familyId)
                    eq("accepted", true)
                }
            }
            .decodeList<JsonObject>()
            .mapNotNull { it.toFlags() }
    }

    suspend fun setSharingPaused(userId: String, paused: Boolean) {
        SupabaseClient.getInstance().from("family_members")
            .update(mapOf("sharing_paused" to paused)) {
                filter {
                    eq("user_id", userId)
                }
            }
    }

    suspend fun setShareHealth(userId: String, share: Boolean) {
        SupabaseClient.getInstance().from("family_members")
            .update(mapOf("share_health" to share)) {
                filter {
                    eq("user_id", userId)
                }
            }
    }
}