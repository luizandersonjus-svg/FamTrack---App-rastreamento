package com.famtrack.app.feature.onboarding

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.famtrack.app.R

/**
 * Ajuda do passo final do onboarding: otimização de bateria.
 * Reconhece os fabricantes mais comuns e retorna instruções específicas,
 * além de abrir a tela de desativação da otimização.
 */
object BatteryOptimization {

    data class Result(val brandLabel: String, val instructions: String)

    fun manufacturerInfo(context: Context): Result {
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        return when {
            manufacturer.contains("samsung") -> Result(
                "Samsung",
                context.getString(R.string.onboarding_battery_samsung)
            )
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> Result(
                "Xiaomi/Redmi",
                context.getString(R.string.onboarding_battery_xiaomi)
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> Result(
                "Huawei/Honor",
                context.getString(R.string.onboarding_battery_huawei)
            )
            manufacturer.contains("motorola") -> Result(
                "Motorola",
                context.getString(R.string.onboarding_battery_motorola)
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> Result(
                "OPPO/Realme",
                context.getString(R.string.onboarding_battery_oppo)
            )
            else -> Result(
                "seu aparelho",
                context.getString(R.string.onboarding_battery_generic)
            )
        }
    }

    fun openBatteryOptimizationSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS)
                )
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    fun openAppSettings(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null)
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}