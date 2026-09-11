package com.famtrack.app.data.observability

import android.content.Context
import android.util.Log
import com.famtrack.app.BuildConfig
import com.famtrack.app.data.offline.RoutePointStore
import com.famtrack.app.data.remote.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Linha de telemetria (ETAPA 7). A RLS da tabela public.telemetry aceita
 * apenas linhas com user_id = auth.uid() (protecao por identidade); o
 * instance_id distingue aparelhos da mesma conta.
 */
@Serializable
private data class TelemetryRow(
    @SerialName("user_id") val userId: String,
    @SerialName("instance_id") val instanceId: String,
    @SerialName("app_version") val appVersion: String?,
    @SerialName("sent_by") val sentBy: String,
    @SerialName("service_alive_seconds") val serviceAliveSeconds: Long?,
    @SerialName("last_fix_seconds") val lastFixSeconds: Long?,
    @SerialName("last_upsert_seconds") val lastUpsertSeconds: Long?,
    @SerialName("fix_count") val fixCount: Long,
    @SerialName("upsert_ok") val upsertOk: Long,
    @SerialName("upsert_fail") val upsertFail: Long,
    @SerialName("sync_ok") val syncOk: Long,
    @SerialName("sync_fail") val syncFail: Long,
    @SerialName("geofence_transitions") val geofenceTransitions: Long,
    @SerialName("queue_size") val queueSize: Int,
    @SerialName("geofence_states") val geofenceStates: Int,
    @SerialName("watermark") val watermark: String?
)

/**
 * Observabilidade da cadeia de localizacao (ETAPA 7).
 *
 * Prova de vida: o [LocationService] chama [registerFix] a cada fix de GPS,
 * que grava um batimento persistente (prefs). O [HealthCheckWorker] (a cada
 * 10 min) usa esse batimento para detectar servico parado e religa-lo, alem
 * de postar um snapshot estruturado em public.telemetry (RLS: so o proprio
 * usuario). Contadores em memoria valem apenas para a sessao atual do processo.
 */
object Telemetry {
    private const val TAG = "FamTrackTelemetry"
    private const val PREFS = "telemetry_prefs"
    private const val KEY_SERVICE_ALIVE = "service_alive_ms"
    private const val KEY_UPSERT = "last_upsert_ms"
    private const val KEY_INSTANCE = "instance_id"

    @Volatile
    private var fixCount = 0L
    @Volatile
    private var upsertOk = 0L
    @Volatile
    private var upsertFail = 0L
    @Volatile
    private var syncOk = 0L
    @Volatile
    private var syncFail = 0L
    @Volatile
    private var geofenceTransitions = 0L

    private fun ctx(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Id único por instalação (sobrevive a reinícios; distingue aparelhos). */
    fun instanceId(context: Context): String {
        val prefs = ctx(context)
        val existing = prefs.getString(KEY_INSTANCE, null)
        if (existing != null) return existing
        val created = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTANCE, created).apply()
        return created
    }

    /** Grava o batimento (e, opcionalmente, o último upsert de locations). */
    fun heartbeat(context: Context, upserted: Boolean = false) {
        val now = System.currentTimeMillis()
        ctx(context).edit()
            .putLong(KEY_SERVICE_ALIVE, now)
            .apply()
        if (upserted) {
            ctx(context).edit().putLong(KEY_UPSERT, now).apply()
        }
    }

    /** Chamado a cada fix de GPS: prova de vida da cadeia (FLP -> serviço). */
    fun registerFix(context: Context) {
        fixCount += 1
        heartbeat(context)
    }

    /** Resultado do upsert da localização atual (RPC rpc_upsert_location). */
    fun registerUpsert(context: Context, ok: Boolean) {
        if (ok) {
            upsertOk += 1
            heartbeat(context, upserted = true)
        } else {
            upsertFail += 1
        }
    }

    /** Resultado da sincronização da fila de rota (RouteSyncCoordinator). */
    fun registerSync(ok: Boolean) {
        if (ok) syncOk += 1 else syncFail += 1
    }

    /** Total de transições de geofence avaliadas nesta sessão. */
    fun registerGeofence(transitions: Int) {
        if (transitions > 0) geofenceTransitions += transitions
    }

    fun lastServiceAliveMs(context: Context): Long =
        ctx(context).getLong(KEY_SERVICE_ALIVE, 0L)

    fun lastUpsertMs(context: Context): Long =
        ctx(context).getLong(KEY_UPSERT, 0L)

    /** Milissegundos sem batimento do serviço (Long.MAX_VALUE se nunca houve). */
    fun serviceStoppedMillis(context: Context): Long {
        val last = lastServiceAliveMs(context)
        return if (last <= 0L) Long.MAX_VALUE else (System.currentTimeMillis() - last).coerceAtLeast(0L)
    }

    private fun age(millis: Long): Long? =
        if (millis <= 0L) null else ((System.currentTimeMillis() - millis) / 1000L).coerceAtLeast(0L)

    private fun geofenceStatesCount(context: Context): Int =
        context.getSharedPreferences("geofence_state", Context.MODE_PRIVATE).all.size

    private fun queueSize(context: Context): Int = try {
        RoutePointStore(context).pendingCount()
    } catch (e: Exception) {
        Log.w(TAG, "falha ao ler fila offline para telemetria: ${e.javaClass.simpleName}")
        -1
    }

    /** Log estruturado (grep: FamTrackTelemetry) da cadeia completa. */
    fun logSnapshot(context: Context) {
        Log.i(
            TAG,
            "health " +
                "service_age=${age(lastServiceAliveMs(context)) ?: "-"}s " +
                "upsert_age=${age(lastUpsertMs(context)) ?: "-"}s " +
                "fixes=$fixCount " +
                "upsert_ok=$upsertOk upsert_fail=$upsertFail " +
                "sync_ok=$syncOk sync_fail=$syncFail " +
                "geofence_x=$geofenceTransitions " +
                "queue=${queueSize(context)} " +
                "geofence_states=${geofenceStatesCount(context)} " +
                "v=${BuildConfig.VERSION_NAME}"
        )
    }

    /**
     * Posta um snapshot em public.telemetry. Sem sessão, apenas loga.
     * Falha de rede é engolida: a próxima rodada do worker tenta de novo.
     */
    suspend fun post(context: Context) {
        logSnapshot(context)
        val client = SupabaseClient.getInstance()
        val user = client.auth.currentUserOrNull() ?: return
        val row = TelemetryRow(
            userId = user.id,
            instanceId = instanceId(context),
            appVersion = BuildConfig.VERSION_NAME,
            sentBy = "health_check",
            serviceAliveSeconds = age(lastServiceAliveMs(context)),
            lastFixSeconds = age(lastServiceAliveMs(context)),
            lastUpsertSeconds = age(lastUpsertMs(context)),
            fixCount = fixCount,
            upsertOk = upsertOk,
            upsertFail = upsertFail,
            syncOk = syncOk,
            syncFail = syncFail,
            geofenceTransitions = geofenceTransitions,
            queueSize = queueSize(context),
            geofenceStates = geofenceStatesCount(context),
            watermark = java.time.Instant.now().toString()
        )
        try {
            client.postgrest.from("telemetry").insert(row)
        } catch (e: Exception) {
            Log.w(TAG, "post falhou (próxima rodada tenta de novo): ${e.javaClass.simpleName} ${shortMessage(e)}")
        }
    }

    private fun shortMessage(e: Exception): String =
        e.localizedMessage?.substringBefore('\n')?.take(120) ?: "erro desconhecido"
}