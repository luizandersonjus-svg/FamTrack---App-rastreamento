package com.famtrack.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.famtrack.app.FamTrackApp
import com.famtrack.app.MainActivity
import com.famtrack.app.R
import com.famtrack.app.data.model.Notification
import com.famtrack.app.data.remote.NotificationRepository
import com.famtrack.app.data.remote.SupabaseClient
import io.github.jan.supabase.auth.auth
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * Trabalho periódico de geofences. NÃO detecta mais transições.
 *
 * Desenho (M4): a transição ENTER/EXIT de um membro é detectada apenas no
 * aparelho DELE (LocationService, a cada fix, com histerese) e gravada uma
 * única vez no backend: 1 evento em `events` + 1 notificação por membro via
 * `insert_family_notifications`. Antes este worker também calculava, em cada
 * aparelho da família, as transições dos OUTROS membros a partir da última
 * posição e inseria mais uma notificação para si — duplicando a da RPC, com
 * atraso, sem histerese e anunciando "chegou" na 1ª execução (estado inicial
 * "fora").
 *
 * O que ele faz agora:
 *  1. Esvazia a fila de transições próprias pendentes (detectadas sem rede ou
 *     com o serviço morto antes do envio) — rede de segurança do LocationService.
 *  2. Repassa ao sistema (alerta do Android) as notificações de geofence que
 *     ESTE usuário recebeu no backend e ainda não foram exibidas. Não há push
 *     (FCM) no app; sem este repasse, os outros membros só veriam a entrada/
 *     saída abrindo a tela de Notificações. Não cria linha nenhuma: cada
 *     notificação do banco vira no máximo um alerta (dedupe pelo id da linha).
 *     Na primeira execução da conta só marca o que já existe como visto.
 */
class GeofenceWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val user = SupabaseClient.getInstance().auth.currentUserOrNull()
                ?: return Result.success()

            purgeLegacyMemberStates(user.id)

            // 1) Transições próprias pendentes (nunca lança, exceto cancelamento).
            GeofenceTransitionOutbox(applicationContext).flush()

            // 2) Alertas recebidos da família.
            relayReceivedGeofenceAlerts(user.id)

            Result.success()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            // Offline/erro de backend: nada é perdido (fila durável e ids vistos só
            // avançam após exibir). A próxima execução periódica tenta de novo.
            Log.w(TAG, "geofence worker falhou (${e.javaClass.simpleName}); tenta no próximo ciclo")
            Result.success()
        }
    }

    private suspend fun relayReceivedGeofenceAlerts(userId: String) {
        val prefs = applicationContext.getSharedPreferences(RELAY_PREFS, Context.MODE_PRIVATE)
        val initialized = prefs.getString(KEY_OWNER, null) == userId
        val seen = if (initialized) {
            prefs.getString(KEY_SEEN, null)
                ?.split(',')
                ?.filter { it.isNotBlank() }
                .orEmpty()
        } else {
            emptyList()
        }
        val seenSet = seen.toHashSet()

        val recent = NotificationRepository()
            .getUserNotificationsOfType(userId, type = "geofence", maxRows = RELAY_FETCH_LIMIT)
            .filter { it.id != null }

        if (initialized) {
            val now = System.currentTimeMillis()
            recent
                .filter { n ->
                    val id = n.id ?: return@filter false
                    id !in seenSet && !n.read && isFresh(n, now)
                }
                .sortedBy { it.created_at ?: "" }
                .forEach { postSystemNotification(it) }
        }

        // Marca tudo o que veio como visto (inclusive antigos/lidos). Os ids da
        // janela atual vão para o fim, para nunca serem os primeiros cortados.
        val currentIds = recent.mapNotNull { it.id }
        val currentSet = currentIds.toHashSet()
        val updated = (seen.filterNot { it in currentSet } + currentIds)
            .takeLast(MAX_SEEN_IDS)
        prefs.edit()
            .putString(KEY_OWNER, userId)
            .putString(KEY_SEEN, updated.joinToString(","))
            .apply()
    }

    private fun isFresh(notification: Notification, now: Long): Boolean {
        val created = notification.created_at ?: return false
        return try {
            now - OffsetDateTime.parse(created).toInstant().toEpochMilli() <= RELAY_MAX_AGE_MS
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Versões antigas deste worker guardavam aqui o estado dentro/fora dos
     * OUTROS membros. O LocationService só usa as chaves do próprio usuário.
     */
    private fun purgeLegacyMemberStates(userId: String) {
        val prefs = applicationContext.getSharedPreferences("geofence_state", Context.MODE_PRIVATE)
        val foreign = prefs.all.keys.filterNot { it.startsWith("${userId}_") }
        if (foreign.isEmpty()) return
        val editor = prefs.edit()
        foreign.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun postSystemNotification(notification: Notification) {
        val notificationId = notification.id.hashCode()
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val systemNotification = NotificationCompat.Builder(applicationContext, FamTrackApp.GEOFENCE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notification.title)
            .setContentText(notification.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(notificationId, systemNotification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS negada: a notificação segue visível na tela do app.
        }
    }

    companion object {
        private const val TAG = "FamTrackGeofence"

        /** Mesmo nome cancelado em SessionCleanup (GEOFENCE_CHECK_WORK_NAME). */
        const val WORK_NAME = "geofence_check"

        /**
         * O WorkManager não executa PeriodicWorkRequest com intervalo menor que
         * 15 min (PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS): pedir 5 min
         * era arredondado silenciosamente para 15. A detecção em tempo real é
         * do LocationService; aqui é só repasse/rede de segurança.
         */
        const val REPEAT_INTERVAL_MINUTES = 15L

        /** Também listado em SessionCleanup (apagado no logout). */
        const val RELAY_PREFS = "geofence_relay"
        private const val KEY_OWNER = "owner"
        private const val KEY_SEEN = "seen_ids"
        private const val MAX_SEEN_IDS = 200
        private const val RELAY_FETCH_LIMIT = 30L

        // Alertas recebidos com mais de 3 h não viram alerta do sistema
        // (ficam só na tela de Notificações).
        private const val RELAY_MAX_AGE_MS = 3L * 60 * 60 * 1000

        fun schedule(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<GeofenceWorker>(
                REPEAT_INTERVAL_MINUTES, TimeUnit.MINUTES
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }
    }
}
