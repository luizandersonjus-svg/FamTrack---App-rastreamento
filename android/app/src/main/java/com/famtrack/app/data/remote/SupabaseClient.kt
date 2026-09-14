package com.famtrack.app.data.remote

import android.content.Intent
import com.famtrack.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.plugins.HttpTimeout

object SupabaseClient {

    private var instance: io.github.jan.supabase.SupabaseClient? = null

    /**
     * Criado uma única vez. Além do request timeout padrão do supabase-kt
     * (10s), instala HttpTimeout com connect/socket/request equivalentes ao
     * cliente OSRM, para nenhuma leitura (ex.: route_history) ficar pendurada
     * indefinidamente em rede instável (TRAJ-2).
     */
    @OptIn(SupabaseInternal::class)
    fun getInstance(): io.github.jan.supabase.SupabaseClient {
        return instance ?: synchronized(this) {
            instance ?: createSupabaseClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseKey = BuildConfig.SUPABASE_ANON_KEY
            ) {
                install(Auth) {
                    host = "login-callback"
                    scheme = "com.famtrack.app"
                }
                install(Postgrest)
                install(Realtime)
                httpConfig {
                    install(HttpTimeout) {
                        connectTimeoutMillis = 5_000
                        socketTimeoutMillis = 10_000
                        requestTimeoutMillis = 10_000
                    }
                }
            }.also { instance = it }
        }
    }

    fun handleDeeplinks(intent: Intent?) {
        intent?.let {
            try {
                getInstance().handleDeeplinks(it) { session ->
                    android.util.Log.d("SupabaseClient", "Session imported: ${session.accessToken}")
                }
            } catch (e: Exception) {
                android.util.Log.e("SupabaseClient", "Error handling deeplink", e)
            }
        }
    }
}
