package com.famtrack.app.service

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.famtrack.app.data.offline.RoutePointStore
import com.famtrack.app.data.offline.RouteSyncCoordinator
import com.famtrack.app.data.remote.LocationRepository
import com.famtrack.app.data.remote.RouteSyncPermanentException
import com.famtrack.app.data.remote.RouteSyncRetriableException
import com.famtrack.app.data.remote.routeSyncErrorCode
import com.famtrack.app.data.remote.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import java.util.concurrent.TimeUnit
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "FamTrackRoute"
private const val SYNC_TAG = "FamTrackRouteSync"
private const val BACKOFF_SEED_SECONDS = 30L
private const val SESSION_WAIT_TIMEOUT_MS = 15_000L
private const val REFRESH_EXPLICIT_TIMEOUT_MS = 10_000L
private const val PERIODIC_INTERVAL_MINUTES = 15L

internal const val ROUTE_SYNC_WORK_NAME = "famtrack_route_sync"
internal const val ROUTE_SYNC_PERIODIC_WORK_NAME = "famtrack_route_sync_periodic"

/**
 * Drena a fila offline de route_history quando há internet. Envia no máximo
 * 20 pontos por execução e apenas os pontos do usuário autenticado atual.
 *
 * - sessão ainda em restauração (Initializing) ou não autenticada: aguarda até
 *   SESSION_WAIT_TIMEOUT_MS antes de ler o usuário; se a sessão não ficar
 *   pronta, retorna Result.retry() e NUNCA conclui "sucesso" sem ter drenado;
 * - falha de rede/timeout/5xx/401/403/RLS: mantém os pontos e retorna
 *   Result.retry() com backoff exponencial do WorkManager;
 * - conflito (23505): tratado como já sincronizado, remove apenas esses id;
 * - sucesso parcial: remove só os ids confirmados e reagenda o one-time.
 */
class RouteSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = RoutePointStore(applicationContext)
        val repository = LocationRepository()
        store.logQueueStats()
        Log.d(SYNC_TAG, "worker inicio")
        return try {
            val session = waitForSession()
            Log.d(SYNC_TAG, "sessao=${session.javaClass.simpleName}")
            val uid = when (session) {
                is SessionStatus.Authenticated -> session.session.user?.id
                is SessionStatus.Initializing -> restoreUidFromPersistedSession()
                is SessionStatus.RefreshFailure,
                is SessionStatus.NotAuthenticated -> null
            }
            if (uid == null) {
                Log.d(SYNC_TAG, "sessao indisponivel; reagendando")
                return Result.retry()
            }

            val ran = RouteSyncCoordinator.trySync(store, repository)
            if (!ran) return Result.retry()

            val remaining = store.pendingCount()
            if (store.pendingCountFor(uid) > 0) {
                // Fila ainda não drenou por completo: reagenda o one-time já.
                enqueueRouteSync(applicationContext)
                Log.d(SYNC_TAG, "worker fim, fila restante=$remaining, reagendado")
                return Result.success()
            }
            Log.d(SYNC_TAG, "worker fim, fila restante=0, resultado=success")
            Result.success()
        } catch (e: RouteSyncRetriableException) {
            Log.w(SYNC_TAG, "erro=retriable/${routeSyncErrorCode(e)}")
            Result.retry()
        } catch (e: RouteSyncPermanentException) {
            Log.w(SYNC_TAG, "erro=permanente/${routeSyncErrorCode(e)}")
            Result.retry()
        } catch (e: Exception) {
            Log.w(SYNC_TAG, "erro=erro/${routeSyncErrorCode(e)}")
            Result.retry()
        }
    }

/**
     * Aguarda a sessão sair do estado Initializing (restauração do storage).
     * Timeout de 15s; depois disso o estado é considerado indisponível e o
     * Worker retorna retry — a fila NUNCA é concluída com sessão ausente.
     */
    private suspend fun waitForSession(): SessionStatus {
        val auth = SupabaseClient.getInstance().auth
        val resolved = withTimeoutOrNull(SESSION_WAIT_TIMEOUT_MS) {
            auth.sessionStatus.first { it !is SessionStatus.Initializing }
        }
        return resolved ?: SessionStatus.Initializing
    }

    /**
     * Estado Initializing preso (refresh pendurado na lib): lê a sessão que
     * ainda está gravada em storage e volta a usar o access token válido dela,
     * sem depender do auto-refresh da lib. Devolve o user.id da sessão, ou
     * null quando não há sessão persistida utilizável (=> Result.retry()).
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun restoreUidFromPersistedSession(): String? {
        val auth = SupabaseClient.getInstance().auth
        val persisted = try {
            auth.sessionManager.loadSession()
        } catch (e: Exception) {
            Log.w(SYNC_TAG, "loadSession falhou: ${e.message}")
            null
        } ?: run {
            Log.d(SYNC_TAG, "sem sessão persistida: retry")
            return null
        }

        val valid = persisted.expiresAt.toEpochMilliseconds() > System.currentTimeMillis() + 60_000L
        return if (valid) {
            auth.importSession(persisted, autoRefresh = false, source = SessionSource.Storage)
            Log.d(SYNC_TAG, "sessão via loadSession (válida)")
            auth.currentUserOrNull()?.id
        } else {
            Log.d(SYNC_TAG, "sessão expirada: refresh explícito")
            val refreshed = withTimeoutOrNull(REFRESH_EXPLICIT_TIMEOUT_MS) {
                try {
                    auth.refreshSession(persisted.refreshToken)
                } catch (e: Exception) {
                    Log.w(SYNC_TAG, "refresh explícito falhou: ${e.message}")
                    null
                }
            }
            if (refreshed == null) {
                Log.w(SYNC_TAG, "refresh timeout: retry")
                null
            } else {
                auth.importSession(refreshed, autoRefresh = false, source = SessionSource.Refresh(persisted))
                Log.d(SYNC_TAG, "sessão via refresh explícito (válida)")
                auth.currentUserOrNull()?.id
            }
        }
    }
}

/** Agenda o trabalho único de sincronização de rota (sem duplicar o que já está na fila). */
internal fun enqueueRouteSync(context: Context) {
    val request = OneTimeWorkRequestBuilder<RouteSyncWorker>()
        .setConstraints(Constraints(NetworkType.CONNECTED))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SEED_SECONDS, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        ROUTE_SYNC_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        request
    )
}

/**
 * Tarefa periódica de 15 minutos (nome próprio, diferente do one-time).
 * Mantém o KEEP: uma única ocorrência ativa, sem duplicar.
 */
internal fun enqueuePeriodicRouteSync(context: Context) {
    val request = PeriodicWorkRequestBuilder<RouteSyncWorker>(
        PERIODIC_INTERVAL_MINUTES,
        TimeUnit.MINUTES
    )
        .setConstraints(Constraints(NetworkType.CONNECTED))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SEED_SECONDS, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        ROUTE_SYNC_PERIODIC_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )
}