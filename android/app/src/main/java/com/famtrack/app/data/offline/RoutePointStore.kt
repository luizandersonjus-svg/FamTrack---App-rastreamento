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
private const val MIGRATION_PREFS = "route_offline_migration"
private const val KEY_POINTS_MIGRATED = "points_migrated"
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

/** Converte o DTO serializável para a entidade Room (ETAPA 5). */
internal fun StoredPoint.toEntity(position: Long): StoredPointEntity =
    StoredPointEntity(
        id = id,
        familyId = familyId,
        userId = userId,
        latitude = latitude,
        longitude = longitude,
        recordedAt = recordedAt,
        accuracy = accuracy,
        speed = speed,
        bearing = bearing,
        batteryLevel = batteryLevel,
        provider = provider,
        position = position
    )

/** Converte a entidade Room de volta para o DTO serializável. */
internal fun StoredPointEntity.toStoredPoint(): StoredPoint =
    StoredPoint(
        id = id,
        familyId = familyId,
        userId = userId,
        latitude = latitude,
        longitude = longitude,
        recordedAt = recordedAt,
        accuracy = accuracy,
        speed = speed,
        bearing = bearing,
        batteryLevel = batteryLevel,
        provider = provider
    )

/**
 * Fila offline durável e limitada de pontos de rota (ETAPA 5).
 *
 * Persistência: a FILA de pontos migrou de SharedPreferences/JSON para Room
 * (SQLite tipado, tolerante a escrita concorrente). A âncora de amostragem e a
 * quarentena continuam em SharedPreferences por serem metadados de uma única
 * linha/rastreio, não componentes da fila.
 *
 * Migração única: na primeira utilização, os pontos ainda gravados no formato
 * legado (prefs JSON) são importados para o Room e a chave legada é removida.
 * Se a importação falhar, a fila simplesmente recomeça vazia (sem perda de
 * crash — religar não duplica).
 *
 * Escrita e leitura devem ocorrer fora da thread principal (as chamadas vêm de
 * coroutines IO do LocationService e do Worker). A fila sobrevive a morte do
 * processo, recriação do serviço, perda/refora da internet e bloqueio de tela.
 */
internal class RoutePointStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val migrated = AtomicBoolean(false)

    private val dao by lazy {
        RouteTrackDatabase.getInstance(context.applicationContext).routePointDao()
    }

    /** Importa (uma única vez) a fila legada de SharedPreferences para o Room. */
    private fun ensureReady() {
        if (migrated.get()) return
        synchronized(lock) {
            if (migrated.get()) return
            val migrationPrefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
            if (!migrationPrefs.getBoolean(KEY_POINTS_MIGRATED, false)) {
                val raw = prefs.getString(KEY_POINTS, null)
                if (raw != null) {
                    try {
                        val points = json.decodeFromString<List<StoredPoint>>(raw)
                        dao.insertAll(points.mapIndexed { index, p -> p.toEntity(index.toLong()) })
                        Log.w(SYNC_TAG, "migração: pontos legados importados=${points.size}")
                    } catch (e: Exception) {
                        Log.w(TAG, "migração: fila legada ilegível; recomeçando vazia: ${e.javaClass.simpleName}")
                    }
                    prefs.edit().remove(KEY_POINTS).apply()
                }
                migrationPrefs.edit().putBoolean(KEY_POINTS_MIGRATED, true).commit()
            }
            migrated.set(true)
        }
    }

    fun enqueue(point: StoredPoint) {
        ensureReady()
        synchronized(lock) {
            val existing = dao.all()
            val merged = trimToLimit(existing + point.toEntity(existing.size.toLong()))
            dao.insertAll(merged)
        }
    }

    /* Pontos de um usuário, em ordem cronológica. */
    fun snapshotFor(userId: String): List<StoredPoint> = synchronized(lock) {
        ensureReady()
        dao.all().filter { it.userId == userId }.map { it.toStoredPoint() }
    }

    fun pendingCountFor(userId: String): Int = synchronized(lock) {
        ensureReady()
        dao.all().count { it.userId == userId }
    }

    fun pendingCount(): Int = synchronized(lock) {
        ensureReady()
        dao.all().size
    }

    /** Metadados de leitura da fila: tamanho atual e recorded_at do ponto mais recente. */
    fun queueStats(): Pair<Int, String?> = synchronized(lock) {
        ensureReady()
        val points = dao.all()
        points.size to points.maxByOrNull { it.recordedAt ?: "" }?.recordedAt
    }

    /** Log de diagnóstico (só leitura): tamanho da fila e último recorded_at. */
    fun logQueueStats() {
        val (size, last) = queueStats()
        Log.d(SYNC_TAG, "queue_size=$size last_recorded_at=$last")
    }

    /* Remove SOMENTE os ids confirmados no servidor. */
    fun removeSynced(ids: Set<String>) {
        if (ids.isEmpty()) return
        ensureReady()
        synchronized(lock) {
            dao.deleteByIds(ids.toList())
        }
    }

    /**
     * Move para a quarentena os pontos cujo user_id diverge do usuário atual
     * (irrecuperáveis pela drenagem normal). Nunca apaga: preserva para
     * diagnóstico/relatório. Retorna a quantidade movida.
     */
    fun quarantineForeignPoints(currentUserId: String): Int = synchronized(lock) {
        ensureReady()
        val points = dao.all().map { it.toStoredPoint() }
        val foreign = points.filter { it.userId != currentUserId }
        if (foreign.isEmpty()) return 0
        val foreignIds = foreign.map { it.id }.toSet()
        val existing = readQuarantine()
        prefs.edit().putString(KEY_QUARANTINE, json.encodeToString(existing + foreign)).commit()
        val remaining = points.filter { it.id !in foreignIds }
        dao.clear()
        dao.insertAll(remaining.mapIndexed { index, p -> p.toEntity(index.toLong()) })
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
    private fun trimToLimit(list: List<StoredPointEntity>): List<StoredPointEntity> {
        if (list.isEmpty()) return list
        if (list.size <= MAX_QUEUED_POINTS) return list
        val head = list.first()
        val tail = list.drop(1).takeLast(MAX_QUEUED_POINTS - 2)
        return listOf(head) + tail + listOf(list.last())
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