package com.famtrack.app.feature.common

import android.content.Context
import android.util.Log
import com.famtrack.app.R
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import java.io.IOException

/**
 * Converte exceções do Supabase/rede em mensagens amigáveis para a UI.
 *
 * IMPORTANTE: NUNCA exibir `e.message`/`e.toString()` cru. A mensagem de um
 * [RestException] monta o texto com a URL completa e TODOS os headers da
 * requisição (incluindo o header Authorization/Bearer token). O log também é
 * sanitizado: apenas tipo/código e trecho curto do corpo, sem headers/URL/token.
 *
 * Regionalização por SQLSTATE do PostgREST:
 * - 23514 (check_violation) com "places_type_check" -> tipo não suportado
 * - 42501 (insufficient_privilege, inclusive RLS)  -> permissão negada
 * - Falha de rede / IOException                       -> mensagem de conexão
 */
object ErrorMessages {

    private const val TAG = "FamTrackPlaces"

    private const val TYPE_CHECK_VIOLATION = "places_type_check"
    private const val PERMISSION_DENIED = "42501"

    /**
     * Mensagem amigável para exibição na UI. `defaultRes` (id de string) é
     * usada quando nenhuma regra específica casa (ex.: save vs delete vs load).
     */
    fun friendly(context: Context, e: Throwable, defaultRes: Int): String = when {
        isNetworkFailure(e) -> {
            logSafe(e, "falha de rede")
            context.getString(R.string.login_google_err_network)
        }
        e is PostgrestRestException -> {
            val body = e.error ?: ""
            when {
                body.contains(TYPE_CHECK_VIOLATION) -> {
                    logSafe(e, "check places_type_check (code=${e.code})")
                    context.getString(R.string.place_err_type_unsupported)
                }
                e.code == PERMISSION_DENIED -> {
                    logSafe(e, "permissão/RLS negada (code=$PERMISSION_DENIED)")
                    context.getString(R.string.common_err_permission)
                }
                else -> {
                    logSafe(e, "erro Postgrest code=${e.code} body=${body.take(140)}")
                    context.getString(defaultRes)
                }
            }
        }
        e is RestException -> {
            logSafe(e, "erro HTTP status=${e.statusCode}")
            context.getString(defaultRes)
        }
        else -> {
            logSafe(e, "erro inesperado")
            context.getString(defaultRes)
        }
    }

    /** Log curto e seguro (só tipo + detalhe breve; sem headers/URL/token). */
    fun logSafe(e: Throwable, detail: String) {
        Log.e(TAG, "$detail :: ${e::class.simpleName}")
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
}