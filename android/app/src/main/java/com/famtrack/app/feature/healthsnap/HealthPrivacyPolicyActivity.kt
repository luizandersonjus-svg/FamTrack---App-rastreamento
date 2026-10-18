package com.famtrack.app.feature.healthsnap

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.famtrack.app.R
import com.famtrack.app.ui.theme.FamTrackTheme

/**
 * Política de privacidade dos dados de saúde.
 *
 * O Health Connect EXIGE esta tela para conceder permissões de saúde:
 * - Android 13 ou anterior: aberta pela ação
 *   `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` (intent-filter no manifest);
 * - Android 14+: aberta pelo `activity-alias` com
 *   `android.intent.action.VIEW_PERMISSION_USAGE` + categoria `HEALTH_PERMISSIONS`.
 * Sem essas entradas, o pedido de permissão do Health Connect volta vazio
 * na hora (o app nem aparece na lista do Health Connect).
 *
 * Também é aberta pelo link "Como usamos seus dados de saúde" em
 * Configurações > Dispositivos.
 */
class HealthPrivacyPolicyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FamTrackTheme {
                HealthPrivacyPolicyScreen(onClose = { finish() })
            }
        }
    }

    companion object {
        fun open(context: Context) {
            val intent = Intent(context, HealthPrivacyPolicyActivity::class.java)
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /**
         * Abre o Health Connect (onde o usuário revoga o acesso do FamTrack).
         * Android 14+: o Health Connect faz parte do sistema e usa outra ação.
         */
        fun openHealthConnect(context: Context): Boolean {
            val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                "android.health.connect.action.HEALTH_HOME_SETTINGS"
            } else {
                "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"
            }
            return try {
                val intent = Intent(action)
                if (context !is android.app.Activity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}

@Composable
fun HealthPrivacyPolicyScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_privacy_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.health_privacy_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.health_privacy_intro),
                style = MaterialTheme.typography.bodyLarge
            )
            PolicySection(R.string.health_privacy_what_title, R.string.health_privacy_what_body)
            PolicySection(R.string.health_privacy_why_title, R.string.health_privacy_why_body)
            PolicySection(R.string.health_privacy_who_title, R.string.health_privacy_who_body)
            PolicySection(R.string.health_privacy_off_title, R.string.health_privacy_off_body)
            PolicySection(R.string.health_privacy_retention_title, R.string.health_privacy_retention_body)
            PolicySection(R.string.health_privacy_steps_title, R.string.health_privacy_steps_body)

            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    if (!HealthPrivacyPolicyActivity.openHealthConnect(context)) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.health_privacy_open_hc_error),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.health_privacy_open_hc))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PolicySection(@StringRes title: Int, @StringRes body: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
