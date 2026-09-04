package com.famtrack.app.data.remote

import com.famtrack.app.data.model.Family
import com.famtrack.app.data.model.FamilyMember
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.Serializable

/**
 * Parâmetro da função "create_family" no banco.
 */
@Serializable
private data class CreateFamilyParams(
    val p_name: String
)

/**
 * Parâmetro da função "join_family_by_code" no banco.
 */
@Serializable
private data class JoinFamilyParams(
    val p_invite_code: String
)

/**
 * Parâmetro da função "get_family_members_display" no banco.
 */
@Serializable
private data class FamilyMembersDisplayParams(
    val p_family_id: String
)

/**
 * Parâmetro da função "update_my_nickname" no banco.
 */
@Serializable
private data class UpdateNicknameParams(
    val p_nickname: String
)

/**
 * Parâmetro da função "update_my_avatar" no banco.
 */
@Serializable
private data class UpdateAvatarParams(
    val p_avatar_url: String
)

/**
 * Parâmetro da função "rename_family" no banco.
 */
@Serializable
private data class RenameFamilyParams(
    val p_family_id: String,
    val p_new_name: String
)

/**
 * Um membro da família com o que precisamos mostrar no mapa:
 * id, nome de exibição e url da foto (avatar).
 */
@Serializable
data class FamilyMemberDisplay(
    val user_id: String,
    val display_name: String,
    val avatar_url: String? = null
)

class FamilyRepository {

    private val client = SupabaseClient.getInstance()

    /**
     * Criar nova família. A criação acontece DENTRO do banco (função segura
     * create_family): gera o código, cria a família e adiciona o criador como
     * admin aceito — tudo numa operação só.
     */
    suspend fun createFamily(name: String): Family {
        return client.postgrest.rpc(
            "create_family",
            CreateFamilyParams(name)
        ).decodeSingle<Family>()
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

    /**
     * Entrar em família existente usando código de convite.
     * Chama a função segura "join_family_by_code" no banco.
     */
    suspend fun joinFamilyByCode(inviteCode: String): Family {
        return try {
            client.postgrest.rpc(
                "join_family_by_code",
                JoinFamilyParams(inviteCode)
            ).decodeSingle<Family>()
        } catch (e: Exception) {
            throw IllegalStateException("Codigo de convite invalido")
        }
    }

    /**
     * Define o NOME (nickname) do usuário logado na família dele.
     * É esse nome que aparece no pino do mapa.
     */
    suspend fun updateMyNickname(nickname: String) {
        client.postgrest.rpc(
            "update_my_nickname",
            UpdateNicknameParams(nickname)
        )
    }

    /**
     * Define a FOTO (avatar_url) do usuário logado na família dele.
     * Exige que a função "update_my_avatar" exista no banco.
     */
    suspend fun updateMyAvatar(avatarUrl: String) {
        client.postgrest.rpc(
            "update_my_avatar",
            UpdateAvatarParams(avatarUrl)
        )
    }

    /**
     * Renomeia a família (somente o criador/admin). Retorna true se conseguiu.
     */
    suspend fun renameFamily(familyId: String, newName: String): Boolean {
        return try {
            client.postgrest.rpc(
                "rename_family",
                RenameFamilyParams(familyId, newName)
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    // Gerar código de convite de 6 caracteres (usado se precisar no app)
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

    // Obter o NICKNAME (nome próprio) do usuário logado, se já tiver definido
    suspend fun getMyNickname(userId: String): String? {
        return client.from("family_members")
            .select {
                filter {
                    eq("user_id", userId)
                    eq("accepted", true)
                }
            }
            .decodeSingleOrNull<FamilyMember>()
            ?.nickname
    }

    // Obter NOME + FOTO de cada membro da família (para mostrar no mapa)
    suspend fun getFamilyMemberDisplay(familyId: String): List<FamilyMemberDisplay> {
        return client.postgrest.rpc(
            "get_family_members_display",
            FamilyMembersDisplayParams(familyId)
        ).decodeList()
    }

    // Adicionar membro à família (para usos futuros de convite manual)
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
            ) {
                select()
            }
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