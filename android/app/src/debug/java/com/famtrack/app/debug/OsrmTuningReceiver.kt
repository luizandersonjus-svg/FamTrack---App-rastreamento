package com.famtrack.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.famtrack.app.ui.history.OsrmTuning

/**
 * Harness DE DEBUG (CORR-0) para comparar limites de concorrência e retries do
 * cliente OSRM sem recompilar. Só existe em builds de debug.
 *
 * Uso via adb:
 *   adb shell am broadcast -a com.famtrack.app.debug.OSRM_TUNING \
 *       --ei max_concurrency 3        (ou 6, 10)
 *       --ei max_retries 2
 *
 * A taxa de MATCHED por configuração aparece nos logs (tag FamTrackRouteMatching):
 *   adb logcat -d -s FamTrackRouteMatching
 */
class OsrmTuningReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val concurrency = intent.getIntExtra("max_concurrency", -1)
        if (concurrency > 0) {
            OsrmTuning.maxConcurrency = concurrency.coerceIn(1, 20)
        }
        val retries = intent.getIntExtra("max_retries", -1)
        if (retries >= 0) {
            OsrmTuning.maxRetries = retries.coerceIn(0, 5)
        }
        Log.w(TAG, "tuning: concurrency=${OsrmTuning.maxConcurrency} retries=${OsrmTuning.maxRetries}")
    }

    private companion object {
        const val TAG = "FamTrackOsrmTuning"
    }
}