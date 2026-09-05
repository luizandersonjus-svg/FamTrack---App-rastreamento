package com.famtrack.app.data.remote

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HeartRateSummary(
    val media: Int,
    val min: Int,
    val max: Int,
    val amostras: Int
)

class HealthConnectRepository(private val context: Context) {

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    suspend fun hasHeartRatePermission(): Boolean = try {
        val granted = HealthConnectClient.getOrCreate(context)
            .permissionController
            .getGrantedPermissions()
        granted.contains(HealthPermission.getReadPermission(HeartRateRecord::class))
    } catch (e: Exception) {
        false
    }

    /** Le os batimentos registrados de HOJE (meia-noite ate agora). Retorna null se nao houver dados. */
    suspend fun readTodayHeartRate(): HeartRateSummary? {
        if (!isAvailable()) return null
        val client = HealthConnectClient.getOrCreate(context)
        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            return null
        }
        if (!granted.contains(HealthPermission.getReadPermission(HeartRateRecord::class))) {
            return null
        }
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val end = Instant.now()
        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            val bpms = response.records
                .flatMap { record -> record.samples }
                .map { sample -> sample.beatsPerMinute.toInt() }
            if (bpms.isEmpty()) return null
            HeartRateSummary(
                media = bpms.average().toInt(),
                min = bpms.minOrNull() ?: 0,
                max = bpms.maxOrNull() ?: 0,
                amostras = bpms.size
            )
        } catch (e: Exception) {
            null
        }
    }
}