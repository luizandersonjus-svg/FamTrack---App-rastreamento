package com.famtrack.app.data.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken

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

    suspend fun signInWithGoogle(idToken: String) {
        client.auth.signInWith(IDToken) {
            this.idToken = idToken
            provider = Google
        }
    }

    fun getGoogleOAuthUrl(): String {
        return client.auth.getOAuthUrl(Google, redirectUrl = "com.famtrack.app://login-callback")
    }

    fun getCurrentUser() = client.auth.currentUserOrNull()

    suspend fun getCurrentSession() = client.auth.currentSessionOrNull()

    suspend fun signOut() {
        client.auth.signOut()
    }

    suspend fun resetPassword(email: String) {
        client.auth.resetPasswordForEmail(email)
    }
}
