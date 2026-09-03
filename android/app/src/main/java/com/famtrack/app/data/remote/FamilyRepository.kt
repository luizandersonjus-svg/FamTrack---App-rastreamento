package com.famtrack.app.data.remote

import com.famtrack.app.data.model.Family
import com.famtrack.app.data.model.FamilyMember
import io.github.jan.supabase.postgrest.from

class FamilyRepository {

    private val client = SupabaseClient.getInstance()

    // Criar nova família
    suspend fun createFamily(name: String, creatorId: String): Family {
        val family = client.from("families")
            .insert(
                mapOf(
                    "name" to name,
                    "creator_id" to creatorId,
                    "invite_code" to generateInviteCode()
                )
            )
            .decodeSingle<Family>()

        // Adicionar o criador como admin
        addMember(family.id, creatorId, "admin", true)

        return family
    }

    // Encontrar família por código de convite
    suspend fun getFamilyByInviteCode(inviteCode: String): Family? {
        return client.from("families")
            .select {
                filter {
                    eq("invite_code", inviteCode)
                }
            }
            .decodeSingleOrNull<Family>()
    }

    // Entrar em família existente usando código de convite
    suspend fun joinFamilyByCode(inviteCode: String, userId: String): Family {
        val family = getFamilyByInviteCode(inviteCode)
            ?: throw IllegalStateException("Codigo de convite invalido")

        // Verifica se já é membro
        val existing = client.from("family_members")
            .select {
                filter {
                    eq("family_id", family.id)
                    eq("user_id", userId)
                }
            }
            .decodeSingleOrNull<FamilyMember>()

        if (existing == null) {
            addMember(family.id, userId, "member", true)
        } else if (!existing.accepted) {
            acceptInvite(existing.id)
        }

        return family
    }

    // Gerar código de convite de 6 caracteres
    private fun generateInviteCode(): String {
        val chars = ('A'..'Z') + ('0'..'9')
        return (1..6)
            .map { chars.random() }
            .joinToString("")
    }

    // Obter famílias do usuário
    suspend fun getUserFamilies(userId: String): List<Family> {
        return client.from("families")
            .select {
                filter {
                    eq("creator_id", userId)
                }
            }
            .decodeList<Family>()
    }

    // Obter família por ID
    suspend fun getFamilyById(familyId: String): Family? {
        return client.from("families")
            .select {
                filter {
                    eq("id", familyId)
                }
            }
            .decodeSingleOrNull<Family>()
    }

    // Obter família do usuário (via family_members)
    suspend fun getUserFamily(userId: String): FamilyMember? {
        return client.from("family_members")
            .select {
                filter {
                    eq("user_id", userId)
                    eq("accepted", true)
                }
            }
            .decodeSingleOrNull<FamilyMember>()
    }

    // Adicionar membro à família
    suspend fun addMember(
        familyId: String,
        userId: String,
        role: String = "member",
        accepted: Boolean = false
    ): FamilyMember {
        return client.from("family_members")
            .insert(
                mapOf(
                    "family_id" to familyId,
                    "user_id" to userId,
                    "role" to role,
                    "accepted" to accepted
                )
            )
            .decodeSingle<FamilyMember>()
    }

    // Obter membros da família
    suspend fun getFamilyMembers(familyId: String): List<FamilyMember> {
        return client.from("family_members")
            .select {
                filter {
                    eq("family_id", familyId)
                }
            }
            .decodeList<FamilyMember>()
    }

    // Aceitar convite
    suspend fun acceptInvite(memberId: String) {
        client.from("family_members")
            .update(mapOf("accepted" to true)) {
                filter {
                    eq("id", memberId)
                }
            }
    }

    // Remover membro
    suspend fun removeMember(memberId: String) {
        client.from("family_members")
            .delete {
                filter {
                    eq("id", memberId)
                }
            }
    }

    // Sair da família
    suspend fun leaveFamily(familyId: String, userId: String) {
        client.from("family_members")
            .delete {
                filter {
                    eq("family_id", familyId)
                    eq("user_id", userId)
                }
            }
    }
}
