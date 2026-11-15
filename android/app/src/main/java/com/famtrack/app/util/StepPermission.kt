package com.famtrack.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Permissão "Atividade física" (ACTIVITY_RECOGNITION), usada pelo sensor de
 * passos do próprio celular (TYPE_STEP_COUNTER) no LocationService.
 *
 * Só é uma permissão de tempo de execução a partir do Android 10 (API 29);
 * antes disso o sensor de passos funciona sem pedir nada.
 */
object StepPermission {

    /** True quando o Android exige pedir a permissão ao usuário (API 29+). */
    val isRuntimePermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Nome da permissão a pedir (só use quando [isRuntimePermission] for true). */
    val PERMISSION: String = Manifest.permission.ACTIVITY_RECOGNITION

    /** True se a contagem de passos pode funcionar neste aparelho agora. */
    fun isGranted(context: Context): Boolean {
        if (!isRuntimePermission) return true
        return ContextCompat.checkSelfPermission(
            context,
            PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
