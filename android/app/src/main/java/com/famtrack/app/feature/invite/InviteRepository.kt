package com.famtrack.app.feature.invite

import com.famtrack.app.data.remote.FamilyRepository
import com.famtrack.app.data.remote.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.Serializable

/**
 * Parâmetro da RPC `regenerate_invite_code`.
 */
@Serializable
private data class RegenerateInviteParams(
    val p_family_id: String
)

/**
 * Operações de convite. A regeneração de código é feita pela RPC
 * `regenerate_invite_code` (adicionada no SQL de migração da F4).
 */
class InviteRepository {

    private val client = SupabaseClient.getInstance()

    suspend fun regenerateInviteCode(familyId: String): String? {
        client.postgrest.rpc("regenerate_invite_code", RegenerateInviteParams(familyId))
        return FamilyRepository().getFamilyById(familyId)?.invite_code
    }
}