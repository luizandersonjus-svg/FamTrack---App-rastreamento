package com.famtrack.app.data.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.StateFlow

class AuthRepository {

    private val client = SupabaseClient.getInstance()

    suspend fun signInWithEmail(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signUpWithEmail(email: String, password: String) {
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signInWithGoogle(idToken: String, rawNonce: String? = null) {
        client.auth.signInWith(IDToken) {
            this.idToken = idToken
            provider = Google
            if (rawNonce != null) this.nonce = rawNonce
        }
    }

    fun getGoogleOAuthUrl(): String {
        return client.auth.getOAuthUrl(Google, redirectUrl = "com.famtrack.app://login-callback")
    }

    fun getCurrentUser() = client.auth.currentUserOrNull()

    suspend fun getCurrentSession() = client.auth.currentSessionOrNull()

    val sessionStatus: StateFlow<SessionStatus>
        get() = client.auth.sessionStatus

    suspend fun hasStoredSession(): Boolean = client.auth.sessionManager.loadSession() != null

    suspend fun signOut() {
        client.auth.signOut()
    }

    suspend fun resetPassword(email: String) {
        client.auth.resetPasswordForEmail(email)
    }
}
