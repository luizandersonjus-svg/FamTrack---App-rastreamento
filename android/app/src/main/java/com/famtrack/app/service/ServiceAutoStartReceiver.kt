package com.famtrack.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Reinicia o rastreamento em background após o boot do aparelho ou após
 * atualização/reinstalação do app (MY_PACKAGE_REPLACED), se o usuário ainda
 * tiver ids persistidos, permissão de localização e compartilhamento ativo.
 * BOOT_COMPLETED é a exceção que permite startForegroundService em segundo plano
 * (Android 12+). Sem UI e sem pedir permissão aqui.
 */
class ServiceAutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val ids = LocationService.lastPersistedIds(context) ?: return
        if (!LocationService.locationPermissionGranted(context)) return
        if (isSharingPaused(context)) {
            Log.i(TAG, "compartilhamento pausado; tracking não retomado")
            return
        }

        try {
            LocationService.startService(context, ids.first, ids.second)
            Log.i(TAG, "tracking retomado após $action")
        } catch (e: SecurityException) {
            Log.w(TAG, "sem permissão para retomar tracking: $action")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "tentativa fora da janela permitida: $action")
        } catch (e: Exception) {
            Log.w(TAG, "falha ao retomar tracking: $action")
        }
    }

    private fun isSharingPaused(context: Context): Boolean {
        return context.getSharedPreferences("privacy_prefs", Context.MODE_PRIVATE)
            .getBoolean("sharing_paused", false)
    }

    companion object {
        private const val TAG = "FamTrackBoot"
    }
}
