package com.famtrack.app.data.session

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.famtrack.app.data.offline.LiveTrailStore
import com.famtrack.app.data.offline.RoutePointStore
import com.famtrack.app.data.offline.RouteSyncCoordinator
import com.famtrack.app.data.remote.LocationRepository
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.service.HealthCheckWorker
import com.famtrack.app.service.LocationService
import com.famtrack.app.service.ROUTE_SYNC_PERIODIC_WORK_NAME
import com.famtrack.app.service.ROUTE_SYNC_WORK_NAME
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Logout completo. Antes, sair da conta só encerrava a sessão do Supabase:
 * o LocationService continuava com GPS ligado, era religado pelo watchdog /
 * onResume / boot com as ids da conta anterior, e trajeto, rastro e estados
 * de geofence ficavam no aparelho para a próxima conta.
 */
object SessionCleanup {

    private const val TAG = "FamTrackAuth"
    private const val FINAL_SYNC_TIMEOUT_MS = 5_000L

    // SharedPreferences com dados da conta. "privacy_prefs" fica de fora de
    // propósito: apagar a pausa local religaria o compartilhamento de quem
    // pausou ao entrar de novo (falha para o lado seguro).
    private val USER_PREFS = listOf(
        "geofence_state",
        "low_battery_log",
        "famtrack_last_fix"
    )

    suspend fun signOut(context: Context): Unit = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val store = RoutePointStore(app)

        // 1) Última tentativa de enviar pontos pendentes enquanto a sessão vale.
        try {
            withTimeoutOrNull(FINAL_SYNC_TIMEOUT_MS) {
                RouteSyncCoordinator.trySync(store, LocationRepository())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "logout: sync final falhou (${e.javaClass.simpleName}); pontos descartados")
        }

        // 2) Para o tracking e impede que seja religado com as ids antigas.
        LocationService.clearPersistedIds(app)
        try {
            LocationService.stopService(app)
        } catch (e: Exception) {
            Log.w(TAG, "logout: falha ao parar serviço (${e.javaClass.simpleName})")
        }

        // 3) Cancela os trabalhos periódicos ligados à conta.
        WorkManager.getInstance(app).apply {
            cancelUniqueWork(GEOFENCE_CHECK_WORK_NAME)
            cancelUniqueWork(ROUTE_SYNC_WORK_NAME)
            cancelUniqueWork(ROUTE_SYNC_PERIODIC_WORK_NAME)
        }
        HealthCheckWorker.cancel(app)

        // 4) Apaga dados locais da conta.
        try {
            store.clearAll()
        } catch (e: Exception) {
            Log.w(TAG, "logout: falha ao limpar fila offline (${e.javaClass.simpleName})")
        }
        LiveTrailStore.clearToday()
        USER_PREFS.forEach { name ->
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        LocationService.currentPlaceName = null
        LocationService.currentPlaceSteps = 0

        // 5) Encerra a sessão. signOut() relança erros de rede sem limpar a
        //    sessão local; nesse caso a limpeza local é forçada.
        val auth = SupabaseClient.getInstance().auth
        try {
            auth.signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "logout: signOut remoto falhou (${e.javaClass.simpleName}); limpando sessão local")
            auth.clearSession()
        }
    }

    const val GEOFENCE_CHECK_WORK_NAME = "geofence_check"
}
