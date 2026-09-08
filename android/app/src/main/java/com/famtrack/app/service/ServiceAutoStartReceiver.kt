package com.famtrack.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Reinicia o rastreamento em background após o boot do aparelho, se o usuário
 * ainda tiver ids persistidos e permissão de localização (exceção permitida
 * para startForegroundService após BOOT_COMPLETED).
 */
class ServiceAutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val ids = LocationService.lastPersistedIds(context) ?: return
        if (!LocationService.locationPermissionGranted(context)) return
        try {
            LocationService.startService(context, ids.first, ids.second)
            Log.i(TAG, "tracking reiniciado após boot")
        } catch (e: Exception) {
            Log.w(TAG, "falha ao reiniciar tracking após boot")
        }
    }

    companion object {
        private const val TAG = "FamTrackBoot"
    }
}