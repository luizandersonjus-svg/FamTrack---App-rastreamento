package com.famtrack.app.data.offline

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "FamTrackRoute"
private const val SYNC_TAG = "FamTrackRouteSync"
private const val PREFS_NAME = "route_offline_store"
private const val KEY_POINTS = "points"
private const val KEY_QUARANTINE = "quarantine_points"
private const val KEY_ANCHOR = "anchor"
private const val MAX_QUEUED_POINTS = 500

/**
 * Ponto de rota pronto para sincronização. O [id] é um UUID gerado no aparelho
 * ANTES de entrar na fila e vira a chave primária em route_history, garantindo
 * idempotência quando a resposta do servidor é perdida.
 */
@Serializable
internal data class StoredPoint(
    val id: String,
    @SerialName("family_id") val familyId: String,
    @SerialName("user_id") val userId: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("recorded_at") val recordedAt: String?,
    val accuracy: Float?,
    val speed: Float?,
    val bearing: Float?,
    @SerialName("battery_level") val batteryLevel: Int?,
    val provider: String?
)

/**
 * Âncora de amostragem persistida. Pertence a um (family_id, user_id) e nunca
 * é reutilizada para outra família/usuário.
 */
@Serializable
internal data class RouteAnchor(
    @SerialName("family_id") val familyId: String,
    @SerialName("user_id") val userId: String,
    val latitude: Double,
    val longitude: Double,
    val timeMillis: Long,
    val bearing: Float,
    val hasBearing: Boolean
)

/**
 * Fila offline durável e limitada de pontos de rota + âncora persistente.
 *
 * Persistência: SharedPreferences com JSON (mecanismo já usado no projeto, sem
 * dependência nova). Escrita e leitura devem ocorrer fora da thread principal
 * (as chamadas vêm de coroutines IO do LocationService e do Worker).
 *
 * A fila sobrevive a morte do processo, recriação do serviço, perda/refora da
 * internet e bloqueio de tela. A gravação local bem-sucedida significa que o
 * ponto foi ACEITO para sincronização e não depende de rede.
 */
internal class RoutePointStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    fun enqueue(point: StoredPoint) {
        synchronized(lock) {
            val merged = trimToLimit(readList() + point)
            writeList(merged)
        }
    }

    /* Pontos de um usuário, em ordem cronológica. */
    fun snapshotFor(userId: String): List<StoredPoint> = synchronized(lock) {
        readList().filter { it.userId == userId }
    }

    fun pendingCountFor(userId: String): Int = synchronized(lock) {
        readList().count { it.userId == userId }
    }

    fun pendingCount(): Int = synchronized(lock) {
        readList().size
    }

    /** Metadados de leitura da fila: tamanho atual e recorded_at do ponto mais recente. */
    fun queueStats(): Pair<Int, String?> = synchronized(lock) {
        val points = readList()
        points.size to points.maxByOrNull { it.recordedAt ?: "" }?.recordedAt
    }

    /** Log de diagnóstico (só leitura): tamanho da fila e último recorded_at. */
    fun logQueueStats() {
        val (size, last) = queueStats()
        Log.d(SYNC_TAG, "queue_size=$size last_recorded_at=$last")
    }

    /* Remove SOMENTE os ids confirmados no servidor. */
    fun removeSynced(ids: Set<String>) {
        synchronized(lock) {
            if (ids.isEmpty()) return
            val remaining = readList().filter { it.id !in ids }
            writeList(remaining)
        }
    }

    /**
     * Move para a quarentena os pontos cujo user_id diverge do usuário atual
     * (irrecuperáveis pela drenagem normal). Nunca apaga: preserva para
     * diagnóstico/relatório. Retorna a quantidade movida.
     */
    fun quarantineForeignPoints(currentUserId: String): Int = synchronized(lock) {
        val points = readList()
        val foreign = points.filter { it.userId != currentUserId }
        if (foreign.isEmpty()) return 0
        val foreignIds = foreign.map { it.id }.toSet()
        val existing = readQuarantine()
        prefs.edit().putString(KEY_QUARANTINE, json.encodeToString(existing + foreign)).commit()
        writeList(points.filter { it.id !in foreignIds })
        Log.w(SYNC_TAG, "quarentena: pontos com user id divergente movidos=${foreign.size}")
        foreign.size
    }

    fun quarantineCount(): Int = synchronized(lock) {
        readQuarantine().size
    }

    private fun readQuarantine(): List<StoredPoint> {
        val raw = prefs.getString(KEY_QUARANTINE, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<StoredPoint>>(raw)
        } catch (e: Exception) {
            Log.w(TAG, "quarentena corrompida: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    fun loadAnchor(familyId: String, userId: String): RouteAnchor? = synchronized(lock) {
        val raw = prefs.getString(KEY_ANCHOR, null) ?: return null
        try {
            json.decodeFromString<RouteAnchor>(raw)
                .takeIf { it.familyId == familyId && it.userId == userId }
        } catch (e: Exception) {
            prefs.edit().remove(KEY_ANCHOR).apply()
            null
        }
    }

    fun saveAnchor(anchor: RouteAnchor) {
        synchronized(lock) {
            prefs.edit().putString(KEY_ANCHOR, json.encodeToString(anchor)).apply()
        }
    }

    fun invalidateAnchor() {
        synchronized(lock) {
            prefs.edit().remove(KEY_ANCHOR).apply()
        }
    }

    /**
     * Trunca a fila de forma determinística ao atingir o limite: preserva o
     * PRIMEIRO e o ÚLTIMO ponto (extremos da janela) e os pontos mais recentes
     * restantes; descarta intermediários na mesma ordem da coleta. Nunca limpa
     * a fila inteira.
     */
    private fun trimToLimit(list: List<StoredPoint>): List<StoredPoint> {
        if (list.isEmpty()) return list
        if (list.size <= MAX_QUEUED_POINTS) return list
        val head = list.first()
        val tail = list.drop(1).takeLast(MAX_QUEUED_POINTS - 2)
        return listOf(head) + tail + listOf(list.last())
    }

    private fun readList(): List<StoredPoint> {
        val raw = prefs.getString(KEY_POINTS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<StoredPoint>>(raw)
        } catch (e: Exception) {
            Log.w(TAG, "fila corrompida: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    /* commit() síncrono: garante a sobrevivência da fila mesmo com morte do processo. */
    private fun writeList(list: List<StoredPoint>) {
        prefs.edit().putString(KEY_POINTS, json.encodeToString(list)).commit()
    }
}

/**
 * Coordenação global (mesmo processo) para que o LocationService e o Worker
 * nunca drenem a fila ao mesmo tempo.
 */
internal object RouteSyncCoordinator {
    private val draining = AtomicBoolean(false)

    /** true se executou ou não havia o que sincronizar; false se já estava em andamento. */
    suspend fun trySync(store: RoutePointStore, repository: com.famtrack.app.data.remote.LocationRepository): Boolean {
        if (!draining.compareAndSet(false, true)) return false
        return try {
            repository.syncRouteQueue(store)
            true
        } finally {
            draining.set(false)
        }
    }
}