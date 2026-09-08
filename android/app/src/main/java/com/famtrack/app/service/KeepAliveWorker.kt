package com.famtrack.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Watchdog do rastreamento: periodicamente garante que o LocationService esteja
 * vivo. O startVia é idempotente (start() só registra os updates uma vez), então
 * chamá-lo a cada ciclo é seguro. Cobre matança do processo por OEM/Doze, reboot
 * (com até 15 min de atraso) e "recents swipe".
 */
class KeepAliveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ids = LocationService.lastPersistedIds(applicationContext) ?: return Result.success()
        if (!LocationService.locationPermissionGranted(applicationContext)) return Result.success()
        return try {
            LocationService.startService(applicationContext, ids.first, ids.second)
            Result.success()
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException ou similar (Android 12+):
            // tenta de novo no próximo ciclo.
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "famtrack_service_keepalive"
        private const val INTERVAL_MINUTES = 15L

        /** Agenda (ou mantém) o watchdog periódico único. */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            ).setInitialDelay(5, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}