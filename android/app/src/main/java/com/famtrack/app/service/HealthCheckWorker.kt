package com.famtrack.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.famtrack.app.FamTrackApp
import com.famtrack.app.MainActivity
import com.famtrack.app.R
import com.famtrack.app.data.observability.Telemetry
import java.util.concurrent.TimeUnit

/**
 * Healthcheck da cadeia LBS (ETAPA 7).
 *
 * A cada [HEALTH_CHECK_INTERVAL_MIN] (10 min):
 * 1) WATCHDOG: se o batimento do [LocationService] (último fix de GPS) parou
 *    por mais de [RESTART_AFTER_MS], religa o serviço de forma idempotente e
 *    avisa o dono por notificação (somente quando há intenção de compartilhar:
 *    permissão concedida, sem pausa manual e com ids persistidos).
 * 2) OBSERVABILIDADE: posta um snapshot estruturado em public.telemetry
 *    (protegido por RLS/identidade). Falha de rede não vira retry — a próxima
 *    rodada tenta de novo.
 *
 * Sem constraint de rede: o watchdog precisa funcionar offline.
 */
class HealthCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        return try {
            restartServiceIfDead(context)
            Telemetry.post(context)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "healthcheck falhou: ${e.localizedMessage}")
            Result.success()
        }
    }

    private fun restartServiceIfDead(context: Context) {
        if (!LocationService.locationPermissionGranted(context)) return
        val prefs = context.getSharedPreferences("privacy_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("sharing_paused", false)) return
        val ids = LocationService.lastPersistedIds(context) ?: return

        val stopped = Telemetry.serviceStoppedMillis(context)
        if (stopped < RESTART_AFTER_MS && stopped != Long.MAX_VALUE) return

        if (stopped == Long.MAX_VALUE) {
            Log.w(TAG, "watchdog: nenhum batimento registrado nesta instalação; religando tracking")
        } else {
            Log.w(TAG, "watchdog: serviço sem fix há ${stopped / 1000}s; religando tracking")
        }
        LocationService.startService(context, ids.first, ids.second)
        notifyRestart(context)
    }

    private fun notifyRestart(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, FamTrackApp.GEOFENCE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.health_restart_title))
            .setContentText(context.getString(R.string.health_restart_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(RESTART_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val TAG = "FamTrackTelemetry"
        private const val HEALTH_CHECK_WORK_NAME = "health_check"
        private const val RESTART_AFTER_MS = 8 * 60_000L
        private const val HEALTH_CHECK_INTERVAL_MIN = 10L
        private const val RESTART_NOTIFICATION_ID = 9001

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthCheckWorker>(
                HEALTH_CHECK_INTERVAL_MIN, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                HEALTH_CHECK_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}