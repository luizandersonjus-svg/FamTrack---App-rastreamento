package com.famtrack.app.feature.sos

import com.famtrack.app.data.model.SosAlert
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Ouve em tempo real novos alertas SOS da família (F5). Como não há FCM
 * ainda, esta é a via de notificação instantânea dentro do app aberto.
 */
object RealtimeAlertListener {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun collectSosAlerts(
        supabase: SupabaseClient,
        familyId: String,
        ownUserId: String,
        onAlert: (SosAlert) -> Unit
    ) {
        val channel = supabase.channel("sos-alerts-$familyId")
        channel.subscribe()
        channel
            .postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                table = "sos_alerts"
            }
            .collect { change ->
                val alert = json.decodeFromJsonElement<SosAlert>(change.record)
                if (alert.user_id != ownUserId && !alert.resolved) {
                    onAlert(alert)
                }
            }
    }
}