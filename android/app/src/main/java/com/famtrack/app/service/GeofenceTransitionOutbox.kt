package com.famtrack.app.service

import android.content.Context
import android.util.Log
import com.famtrack.app.R
import com.famtrack.app.data.remote.FamilyRepository
import com.famtrack.app.data.remote.NotificationRepository
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.feature.activity.ActivityRepository
import com.famtrack.app.feature.activity.Event
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Transição de geofence (ENTER/EXIT) do PRÓPRIO usuário, confirmada pelo
 * LocationService e ainda não totalmente enviada ao backend.
 *
 * `id` é gerado no aparelho e vira o `events.id`: se a resposta do insert se
 * perder (rede caiu depois do commit), o reenvio bate na PK (23505) e é
 * tratado como "já gravado" — o evento nunca duplica.
 */
@Serializable
internal data class PendingGeofenceTransition(
    val id: String,
    val familyId: String,
    val memberId: String,
    val geofenceId: String,
    val placeId: String? = null,
    val geofenceName: String,
    val enter: Boolean,
    val lat: Double,
    val lng: Double,
    val occurredAtMs: Long,
    val eventDone: Boolean = false,
    val notifyDone: Boolean = false,
    val eventAttempts: Int = 0,
    val notifyAttempts: Int = 0
)

/**
 * Fila durável (SharedPreferences) das transições de geofence do próprio
 * usuário. Fonte única de verdade: só o aparelho de quem se moveu detecta a
 * transição (por fix, com histerese) e só ele grava:
 *  - 1 linha em `events` (ENTER/EXIT, member_id = auth.uid(), exigido pelo RLS);
 *  - 1 chamada a `insert_family_notifications` (1 notificação por membro,
 *    exceto o remetente).
 *
 * Por que fila: o estado dentro/fora é persistido no momento da detecção (é a
 * verdade local, evita reemitir a mesma transição a cada fix), e o envio pode
 * falhar por falta de rede. Sem a fila, uma chegada detectada offline seria
 * perdida; com "só persiste o estado após enviar", cada fix offline tentaria
 * de novo e, se a pessoa saísse antes da rede voltar, nem a chegada nem a
 * saída seriam registradas. Aqui o item fica na fila até cada parte (evento e
 * notificação) ser confirmada separadamente, então o que já foi gravado não é
 * reenviado.
 *
 * Esvaziada pelo LocationService (após cada transição e, com throttle, nos
 * fixes seguintes) e pelo GeofenceWorker (periódico, com rede).
 */
internal class GeofenceTransitionOutbox(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enqueue(item: PendingGeofenceTransition) {
        synchronized(LOCK) {
            val list = readAll().toMutableList()
            list.add(item)
            // Limite de segurança: preserva as transições mais recentes.
            writeAll(if (list.size > MAX_ITEMS) list.takeLast(MAX_ITEMS) else list)
        }
    }

    fun hasPending(): Boolean {
        val raw = prefs.getString(KEY_ITEMS, null)
        return !raw.isNullOrBlank() && raw != "[]"
    }

    /**
     * Tenta enviar tudo o que está pendente, em ordem. Para no primeiro erro
     * de rede (o restante fica para a próxima tentativa). Nunca lança exceção
     * (exceto cancelamento da coroutine).
     *
     * @return true se a fila ficou vazia.
     */
    suspend fun flush(): Boolean {
        // Evita dois envios simultâneos (serviço e worker) do mesmo item.
        if (!flushMutex.tryLock()) return false
        try {
            return flushLocked()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "falha protegida ao esvaziar fila de geofence (${e.javaClass.simpleName})")
            return false
        } finally {
            flushMutex.unlock()
        }
    }

    private suspend fun flushLocked(): Boolean {
        val pending = synchronized(LOCK) { readAll() }
        if (pending.isEmpty()) return true

        // Sem sessão: mantém a fila (o RLS recusaria qualquer insert).
        val authUserId = SupabaseClient.getInstance().auth.currentUserOrNull()?.id ?: return false
        val now = System.currentTimeMillis()

        for (original in pending) {
            // Item de outra conta (o RLS exige member_id = auth.uid()) ou velho
            // demais para ainda fazer sentido: descarta.
            if (original.memberId != authUserId || now - original.occurredAtMs > MAX_AGE_MS) {
                Log.w(TAG, "descartando transição pendente (conta diferente ou expirada)")
                remove(original.id)
                continue
            }

            var item = original

            if (!item.eventDone) {
                val toSend = item
                item = when (attempt { recordEvent(toSend) }) {
                    Outcome.OK, Outcome.ALREADY_DONE -> item.copy(eventDone = true)
                    Outcome.NETWORK -> {
                        update(item)
                        return false
                    }
                    Outcome.FAILED -> {
                        val attempts = item.eventAttempts + 1
                        if (attempts >= MAX_ATTEMPTS) {
                            // Erro permanente (ex.: CHECK/RLS): não trava a fila.
                            Log.e(TAG, "evento de geofence abandonado após $attempts tentativas")
                            item.copy(eventDone = true, eventAttempts = attempts)
                        } else {
                            item.copy(eventAttempts = attempts)
                        }
                    }
                }
            }

            if (!item.notifyDone) {
                if (now - item.occurredAtMs > NOTIFY_MAX_AGE_MS) {
                    // Aviso muito atrasado confundiria a família; o evento já
                    // registra o fato na tela de Atividade.
                    item = item.copy(notifyDone = true)
                } else {
                    val toNotify = item
                    item = when (attempt { notifyFamily(toNotify, now) }) {
                        Outcome.OK, Outcome.ALREADY_DONE -> item.copy(notifyDone = true)
                        Outcome.NETWORK -> {
                            update(item)
                            return false
                        }
                        Outcome.FAILED -> {
                            val attempts = item.notifyAttempts + 1
                            if (attempts >= MAX_ATTEMPTS) {
                                Log.e(TAG, "notificação de geofence abandonada após $attempts tentativas")
                                item.copy(notifyDone = true, notifyAttempts = attempts)
                            } else {
                                item.copy(notifyAttempts = attempts)
                            }
                        }
                    }
                }
            }

            if (item.eventDone && item.notifyDone) remove(item.id) else update(item)
        }
        return !hasPending()
    }

    private suspend fun recordEvent(item: PendingGeofenceTransition) {
        val repository = ActivityRepository()
        try {
            repository.recordEvent(item.toEvent())
        } catch (e: PostgrestRestException) {
            // place apagado depois do cache de geofences: grava sem o vínculo
            // (events.place_id -> places.id) em vez de perder o evento.
            if (e.code == FK_VIOLATION && item.placeId != null) {
                repository.recordEvent(item.copy(placeId = null).toEvent())
            } else {
                throw e
            }
        }
    }

    private suspend fun notifyFamily(item: PendingGeofenceTransition, now: Long) {
        val name = resolveMemberName(item.familyId, item.memberId)
        val base = appContext.getString(
            if (item.enter) R.string.geofence_alert_enter else R.string.geofence_alert_exit,
            name,
            item.geofenceName
        )
        val message = if (now - item.occurredAtMs > DELAYED_SUFFIX_MS) {
            val time = Instant.ofEpochMilli(item.occurredAtMs)
                .atZone(ZoneId.systemDefault())
                .format(TIME_FORMATTER)
            appContext.getString(R.string.geofence_alert_at_time, base, time)
        } else {
            base
        }
        NotificationRepository().notifyFamily(
            familyId = item.familyId,
            senderUserId = item.memberId,
            title = item.geofenceName,
            message = message,
            type = "geofence"
        )
    }

    /** Nome de exibição de quem se moveu (cacheado; fallback genérico). */
    private suspend fun resolveMemberName(familyId: String, memberId: String): String {
        cachedName?.let { (id, name) -> if (id == memberId) return name }
        val name = try {
            FamilyRepository().getFamilyMemberDisplay(familyId)
                .firstOrNull { it.user_id == memberId }
                ?.display_name
                ?.takeIf { it.isNotBlank() }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            // Sem rede: a notificação também falharia; deixa a fila tentar depois.
            if (isNetworkFailure(e)) throw e
            null
        }
        if (name != null) cachedName = memberId to name
        return name ?: appContext.getString(R.string.geofence_alert_member_fallback)
    }

    private enum class Outcome { OK, ALREADY_DONE, NETWORK, FAILED }

    private suspend fun attempt(block: suspend () -> Unit): Outcome = try {
        block()
        Outcome.OK
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (e: Exception) {
        when {
            e is PostgrestRestException && e.code == UNIQUE_VIOLATION -> Outcome.ALREADY_DONE
            isNetworkFailure(e) -> Outcome.NETWORK
            else -> {
                // Log curto: a mensagem de RestException carrega headers/token.
                val code = (e as? PostgrestRestException)?.code
                Log.w(TAG, "falha ao enviar transição de geofence (${e.javaClass.simpleName} code=$code)")
                Outcome.FAILED
            }
        }
    }

    private fun isNetworkFailure(e: Throwable): Boolean {
        if (e is HttpRequestException) return true
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is IOException) return true
            cause = cause.cause
        }
        return false
    }

    private fun PendingGeofenceTransition.toEvent(): Event = Event(
        id = id,
        family_id = familyId,
        type = if (enter) "ENTER" else "EXIT",
        member_id = memberId,
        place_id = placeId,
        lat = lat,
        lng = lng,
        // Hora real da transição (não a do envio, que pode ter sido atrasado).
        created_at = Instant.ofEpochMilli(occurredAtMs).toString()
    )

    // ---------------------------------------------------------------------
    // Persistência (lista JSON em SharedPreferences, protegida por LOCK)
    // ---------------------------------------------------------------------

    private fun update(item: PendingGeofenceTransition) {
        synchronized(LOCK) {
            writeAll(readAll().map { if (it.id == item.id) item else it })
        }
    }

    private fun remove(id: String) {
        synchronized(LOCK) {
            writeAll(readAll().filterNot { it.id == id })
        }
    }

    private fun readAll(): List<PendingGeofenceTransition> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            json.decodeFromString(LIST_SERIALIZER, raw)
        } catch (e: Exception) {
            Log.w(TAG, "fila de geofence corrompida; descartando (${e.javaClass.simpleName})")
            emptyList()
        }
    }

    private fun writeAll(items: List<PendingGeofenceTransition>) {
        // commit (síncrono): a fila precisa sobreviver a uma morte do processo
        // logo após a detecção. Chamado sempre fora da main thread.
        prefs.edit().putString(KEY_ITEMS, json.encodeToString(LIST_SERIALIZER, items)).commit()
    }

    companion object {
        private const val TAG = "FamTrackGeofence"

        /** Também listado em SessionCleanup (apagado no logout). */
        const val PREFS_NAME = "geofence_outbox"
        private const val KEY_ITEMS = "pending"

        private const val MAX_ITEMS = 100
        private const val MAX_ATTEMPTS = 5
        private const val UNIQUE_VIOLATION = "23505"
        private const val FK_VIOLATION = "23503"

        // Transições com mais de 3 dias são descartadas.
        private const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
        // Notificação só se a transição tiver até 6 h (o evento é gravado mesmo assim).
        private const val NOTIFY_MAX_AGE_MS = 6L * 60 * 60 * 1000
        // Acima de 5 min de atraso, a mensagem informa o horário real.
        private const val DELAYED_SUFFIX_MS = 5L * 60 * 1000

        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val LIST_SERIALIZER = ListSerializer(PendingGeofenceTransition.serializer())
        private val json = Json { ignoreUnknownKeys = true }

        private val LOCK = Any()
        private val flushMutex = Mutex()

        @Volatile
        private var cachedName: Pair<String, String>? = null
    }
}
