package com.famtrack.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.famtrack.app.data.remote.AuthCallbackState
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.feature.sos.SosDeepLink
import com.famtrack.app.service.HealthCheckWorker
import com.famtrack.app.service.LocationService
import com.famtrack.app.ui.FamTrackNavigation
import com.famtrack.app.ui.home.HomeDataRefresh
import com.famtrack.app.ui.theme.FamTrackTheme
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleAuthIntent(intent)
        handleSosExtra(intent)
        // ETAPA 7 — observabilidade: watchdog (religa o serviço) + telemetria.
        HealthCheckWorker.schedule(this)
        setContent {
            FamTrackTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FamTrackNavigation()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthIntent(intent)
        handleSosExtra(intent)
    }

    override fun onResume() {
        super.onResume()
        revalidateTrackingState()
        HomeDataRefresh.publish()
    }

    /**
     * ETAPA 4 — stale/fresh: sempre que o app volta ao foreground (retorno das
     * Configurações do sistema, de background, deep links), revalida o estado
     * do compartilhamento:
     * - permissão de localização concedida + compartilhamento NÃO pausado +
     *   ids persistidos => garante o serviço de tracking rodando (idempotente);
     * - conceder a permissão nas Configurações retoma o tracking sem depender
     *   de reabrir a Home (ETAPA 0 - achado 9).
     * Em qualquer outro cenário não faz nada, preservando a intenção do usuário
     * (pausou, não tem família, sem permissão).
     */
    private fun revalidateTrackingState() {
        if (!LocationService.locationPermissionGranted(this)) return
        val prefs = getSharedPreferences("privacy_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("sharing_paused", false)) return
        val ids = LocationService.lastPersistedIds(this) ?: return
        LocationService.startService(this, ids.first, ids.second)
    }

    /** Consome os extras sos_lat/sos_lng (clique na notificação de SOS). */
    private fun handleSosExtra(intent: Intent?) {
        if (intent?.hasExtra("sos_lat") != true) return
        val lat = intent.getDoubleExtra("sos_lat", Double.NaN)
        val lng = intent.getDoubleExtra("sos_lng", Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return
        SosDeepLink.publish(lat, lng)
    }

    private fun handleAuthIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val scheme = data.scheme ?: return
        val host = data.host ?: return
        if (scheme != "com.famtrack.app" || host != "login-callback") return

        val fragmentParams = data.fragment?.let(::parseFragmentParams) ?: emptyMap()

        val errorCode = data.getQueryParameter("error_code") ?: fragmentParams["error_code"]
        val error = data.getQueryParameter("error") ?: fragmentParams["error"]
        val errorDescription = data.getQueryParameter("error_description") ?: fragmentParams["error_description"]

        if (errorCode != null || error != null) {
            val errorKey = errorCode ?: error
            Log.w("FamTrackAuth", "callback com erro: $errorKey")
            AuthCallbackState.publish(errorKey, errorDescription)
            return
        }

        val code = data.getQueryParameter("code") ?: fragmentParams["code"]
        Log.d("FamTrackAuth", "callback recebido: code=${if (code != null) "presente" else "ausente"}, error=nenhum")

        if (code != null) {
            Log.d("FamTrackAuth", "callback com code -> troca via exchangeCodeForSession")
            lifecycleScope.launch {
                try {
                    SupabaseClient.getInstance().auth.exchangeCodeForSession(code)
                    Log.d("FamTrackAuth", "exchangeCodeForSession ok")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("FamTrackAuth", "exchangeCodeForSession falhou: ${e.message?.take(160)}")
                    AuthCallbackState.publish(e.javaClass.simpleName, e.message)
                }
            }
        } else {
            Log.d("FamTrackAuth", "callback sem code -> handleDeeplinks")
            try {
                SupabaseClient.handleDeeplinks(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("FamTrackAuth", "handleDeeplinks falhou: ${e.message?.take(160)}")
                AuthCallbackState.publish(e.javaClass.simpleName, e.message)
            }
        }
    }

    private fun parseFragmentParams(fragment: String): Map<String, String> =
        fragment.split("&").mapNotNull { pair ->
            val index = pair.indexOf('=')
            if (index <= 0) null else pair.substring(0, index) to pair.substring(index + 1)
        }.toMap()
}