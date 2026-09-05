package com.famtrack.app.feature.sos

import android.util.Log
import com.famtrack.app.data.model.SosAlert
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

private const val RETRY_DELAY_MS = 5000L

/**
 * Ouve em tempo real os alertas SOS da família (F5).
 * Filtro de family_id feito server-side (F8). Cada alerta é entregue uma
 * única vez por ID; atualizações de resolução permitem cancelar a
 * notificação local do dispositivo remetente.
 *
 * Reconexão automática: se a conexão cair o canal é removido, aguarda-se
 * RETRY_DELAY_MS e o ciclo recomeça, até o escopo do compose ser destruido.
 */
object RealtimeAlertListener {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun collectSosAlerts(
        supabase: SupabaseClient,
        familyId: String,
        ownUserId: String,
        onAlert: (SosAlert) -> Unit,
        onResolved: (String) -> Unit
    ) {
        while (currentCoroutineContext().isActive) {
            val channel = supabase.channel("sos-alerts-$familyId")
            try {
                channel.subscribe()

                channel
                    .postgresChangeFlow<PostgresAction>(schema = "public") {
                        table = "sos_alerts"
                        filter("family_id", FilterOperator.EQ, familyId)
                    }
                    .collect { action ->
                        when (action) {
                            is PostgresAction.Insert -> {
                                val alert = json.decodeFromJsonElement<SosAlert>(action.record)
                                if (alert.user_id != ownUserId && !alert.resolved) {
                                    onAlert(alert)
                                }
                            }
                            is PostgresAction.Update -> {
                                val alert = json.decodeFromJsonElement<SosAlert>(action.record)
                                val alertId = alert.id
                                if (alertId != null && alert.resolved && alert.user_id != ownUserId) {
                                    onResolved(alertId)
                                }
                            }
                            else -> {}
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("RealtimeAlerts", "Erro no canal SOS realtime", e)
            } finally {
                try {
                    supabase.realtime.removeChannel(channel)
                } catch (_: Exception) {
                    // Ignora erro ao limpar canal morto.
                }
            }
            delay(RETRY_DELAY_MS)
        }
    }
}
