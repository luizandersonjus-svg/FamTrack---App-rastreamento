package com.famtrack.app.service

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.famtrack.app.data.offline.RoutePointStore
import com.famtrack.app.data.offline.RouteSyncCoordinator
import com.famtrack.app.data.remote.LocationRepository
import com.famtrack.app.data.remote.RouteSyncPermanentException
import com.famtrack.app.data.remote.RouteSyncRetriableException
import com.famtrack.app.data.remote.routeSyncErrorCode
import com.famtrack.app.data.remote.SupabaseClient
import io.github.jan.supabase.auth.auth

private const val TAG = "FamTrackRoute"
private const val BACKOFF_SEED_SECONDS = 30L

internal const val ROUTE_SYNC_WORK_NAME = "famtrack_route_sync"

/**
 * Drena a fila offline de route_history quando há internet. Envia no máximo
 * 20 pontos por execução e apenas os pontos do usuário autenticado atual.
 *
 * - falha de rede/timeout/5xx: mantém os pontos e retorna Result.retry() com
 *   backoff exponencial do WorkManager;
 * - falha permanente (auth/RLS): mantém os pontos e encerra de forma
 *   controlada (aguarda nova sessão), sem apagar nada.
 */
class RouteSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = RoutePointStore(applicationContext)
        val repository = LocationRepository()
        return try {
            val ran = RouteSyncCoordinator.trySync(store, repository)
            if (!ran) return Result.retry()
            val uid = try {
                SupabaseClient.getInstance().auth.currentUserOrNull()?.id
            } catch (e: Exception) {
                null
            }
            if (uid != null && store.pendingCountFor(uid) > 0) {
                enqueueRouteSync(applicationContext)
            }
            Result.success()
        } catch (e: RouteSyncRetriableException) {
            Log.w(TAG, "sincronização adiada: rede indisponível")
            Result.retry()
        } catch (e: RouteSyncPermanentException) {
            Log.w(TAG, "falha permanente: ${routeSyncErrorCode(e)}")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "sincronização adiada: rede indisponível")
            Result.retry()
        }
    }
}

/** Agenda (ou reaproveita) o trabalho único de sincronização de rota. */
internal fun enqueueRouteSync(context: Context) {
    val request = OneTimeWorkRequestBuilder<RouteSyncWorker>()
        .setConstraints(Constraints(NetworkType.CONNECTED))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SEED_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        ROUTE_SYNC_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        request
    )
}