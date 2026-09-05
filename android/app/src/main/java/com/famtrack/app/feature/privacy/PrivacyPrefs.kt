package com.famtrack.app.feature.privacy

import android.content.Context

/**
 * Flags de privacidade locais. O ponto de envio da localização
 * (LocationService) consulta a chave `sharing_paused` aqui — a gravação
 * imediata local garante a pausa sem depender de rede (F7).
 */
object PrivacyPrefs {

    private const val PREFS_NAME = "privacy_prefs"
    private const val KEY_SHARING_PAUSED = "sharing_paused"
    private const val KEY_SHARE_HEALTH = "share_health"

    fun setSharingPaused(context: Context, paused: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHARING_PAUSED, paused)
            .apply()
    }

    fun isSharingPaused(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHARING_PAUSED, false)
    }

    fun setShareHealth(context: Context, share: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHARE_HEALTH, share)
            .apply()
    }

    fun isSharingHealth(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHARE_HEALTH, false)
    }
}