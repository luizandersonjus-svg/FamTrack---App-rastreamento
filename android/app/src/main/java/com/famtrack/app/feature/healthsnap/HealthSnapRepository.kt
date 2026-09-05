package com.famtrack.app.feature.healthsnap

import android.content.Context
import com.famtrack.app.data.remote.HealthConnectRepository
import com.famtrack.app.data.remote.SupabaseClient
import io.github.jan.supabase.postgrest.from
import java.time.LocalDate

/**
 * Sincroniza o resumo de batimentos do dia (lido do HealthConnect) com o
 * backend, apenas quando o usuário autorizou o compartilhamento de saúde (F7).
 */
class HealthSnapRepository(private val context: Context) {

    private val healthConnect = HealthConnectRepository(context)

    suspend fun getTodaySnapshots(familyId: String): List<HealthSnapshot> {
        val today = LocalDate.now().toString()
        return SupabaseClient.getInstance().from("health_snapshots")
            .select {
                filter {
                    eq("family_id", familyId)
                    eq("date", today)
                }
            }
            .decodeList<HealthSnapshot>()
    }

    suspend fun getByUserToday(familyId: String, userId: String): HealthSnapshot? {
        val today = LocalDate.now().toString()
        return SupabaseClient.getInstance().from("health_snapshots")
            .select {
                filter {
                    eq("family_id", familyId)
                    eq("user_id", userId)
                    eq("date", today)
                }
            }
            .decodeSingleOrNull<HealthSnapshot>()
    }

    /** Lê os batimentos hoje e grava/atualiza o resumo do usuário. Retorna o snapshot gravado, ou null. */
    suspend fun syncOwnToday(familyId: String, userId: String): HealthSnapshot? {
        val summary = healthConnect.readTodayHeartRate() ?: return null
        val today = LocalDate.now().toString()

        val existing = SupabaseClient.getInstance().from("health_snapshots")
            .select {
                filter {
                    eq("family_id", familyId)
                    eq("user_id", userId)
                    eq("date", today)
                }
            }
            .decodeSingleOrNull<HealthSnapshot>()

        return if (existing != null) {
            SupabaseClient.getInstance().from("health_snapshots")
                .update(
                    existing.copy(
                        bpm_media = summary.media,
                        bpm_min = summary.min,
                        bpm_max = summary.max,
                        samples = summary.amostras
                    )
                ) {
                    filter {
                        eq("id", existing.id!!)
                    }
                }
            existing.copy(
                bpm_media = summary.media,
                bpm_min = summary.min,
                bpm_max = summary.max,
                samples = summary.amostras
            )
        } else {
            SupabaseClient.getInstance().from("health_snapshots")
                .insert(
                    HealthSnapshot(
                        user_id = userId,
                        family_id = familyId,
                        date = today,
                        bpm_media = summary.media,
                        bpm_min = summary.min,
                        bpm_max = summary.max,
                        samples = summary.amostras
                    )
                )
                .decodeSingle<HealthSnapshot>()
        }
    }
}