package com.famtrack.app.feature.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.famtrack.app.R
import com.famtrack.app.util.StepPermission
import kotlinx.coroutines.launch

private enum class OnboardingStep {
    EXPLAIN, LOCATION, BACKGROUND, NOTIFICATIONS, ACTIVITY, BATTERY, DONE
}

/**
 * Onboarding de permissões exibido uma única vez após o login.
 * Fluxo: explicação -> localização (FINE/COARSE) -> segundo plano (Android 10+) ->
 * notificações (Android 13+) -> atividade física / passos (Android 10+) ->
 * otimização de bateria. Nunca bloqueia o app: toda etapa pode ser pulada.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(OnboardingStep.EXPLAIN) }
    var hasFine by remember { mutableStateOf(false) }
    var hasBackground by remember { mutableStateOf(false) }
    var hasNotification by remember { mutableStateOf(false) }
    var hasActivity by remember { mutableStateOf(StepPermission.isGranted(context)) }
    // Já pedimos "Atividade física" nesta tela? Depois de uma recusa, "Continuar"
    // apenas avança (o Android pode negar sozinho após duas recusas).
    var activityAsked by remember { mutableStateOf(false) }
    var deniedNote by remember { mutableStateOf<String?>(null) }

    fun advanceTo(next: OnboardingStep) {
        deniedNote = null
        step = next
    }

    // Etapa de passos só existe no Android 10+ e quando ainda falta a permissão.
    fun stepAfterNotifications(): OnboardingStep =
        if (StepPermission.isRuntimePermission && !StepPermission.isGranted(context)) {
            OnboardingStep.ACTIVITY
        } else {
            OnboardingStep.BATTERY
        }

    val fineLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasFine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        deniedNote = if (hasFine) null
        else context.getString(R.string.onboarding_location_denied)
    }

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasBackground = result[Manifest.permission.ACCESS_BACKGROUND_LOCATION] == true
        deniedNote = if (hasBackground) null
        else context.getString(R.string.onboarding_background_needed)
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasNotification = result[Manifest.permission.POST_NOTIFICATIONS] == true
        deniedNote = if (hasNotification) null
        else context.getString(R.string.onboarding_notifications_denied)
    }

    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasActivity = granted
        activityAsked = true
        if (granted) {
            advanceTo(OnboardingStep.BATTERY)
        } else {
            deniedNote = context.getString(R.string.onboarding_activity_denied)
        }
    }

    fun continueButton() {
        when (step) {
            OnboardingStep.EXPLAIN -> advanceTo(OnboardingStep.LOCATION)
            OnboardingStep.LOCATION -> {
                if (hasFine) {
                    advanceTo(OnboardingStep.BACKGROUND)
                } else {
                    fineLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
            OnboardingStep.BACKGROUND -> {
                when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> advanceTo(OnboardingStep.NOTIFICATIONS)
                    hasBackground -> advanceTo(OnboardingStep.NOTIFICATIONS)
                    Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                        backgroundLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    }
                    else -> {
                        BatteryOptimization.openAppSettings(context)
                        // advanceTo() limpa deniedNote: a dica precisa ser definida
                        // DEPOIS, senão nunca era exibida.
                        advanceTo(OnboardingStep.NOTIFICATIONS)
                        deniedNote = context.getString(R.string.onboarding_background_settings_hint)
                    }
                }
            }
            OnboardingStep.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasNotification) {
                    advanceTo(stepAfterNotifications())
                } else {
                    notificationLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }
            OnboardingStep.ACTIVITY -> {
                if (!StepPermission.isRuntimePermission || hasActivity || activityAsked) {
                    advanceTo(OnboardingStep.BATTERY)
                } else {
                    activityLauncher.launch(StepPermission.PERMISSION)
                }
            }
            OnboardingStep.BATTERY, OnboardingStep.DONE -> {
                scope.launch {
                    OnboardingPrefs.setDone(context, true)
                    onFinished()
                }
            }
        }
    }

    val info = BatteryOptimization.manufacturerInfo(context)

    val stepContent = when (step) {
        OnboardingStep.EXPLAIN -> StepContent(
            icon = Icons.Outlined.Info,
            title = context.getString(R.string.onboarding_welcome_title),
            text = context.getString(R.string.onboarding_welcome_text)
        )
        OnboardingStep.LOCATION -> StepContent(
            icon = Icons.Filled.LocationOn,
            title = context.getString(R.string.onboarding_location_title),
            text = context.getString(R.string.onboarding_location_text)
        )
        OnboardingStep.BACKGROUND -> StepContent(
            icon = Icons.Filled.LocationOn,
            title = context.getString(R.string.onboarding_background_title),
            text = context.getString(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    R.string.onboarding_background_text_r
                } else {
                    R.string.onboarding_background_text_q
                }
            )
        )
        OnboardingStep.NOTIFICATIONS -> StepContent(
            icon = Icons.Filled.NotificationsActive,
            title = context.getString(R.string.onboarding_notifications_title),
            text = context.getString(R.string.onboarding_notifications_text)
        )
        OnboardingStep.ACTIVITY -> StepContent(
            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
            title = context.getString(R.string.onboarding_activity_title),
            text = context.getString(R.string.onboarding_activity_text)
        )
        OnboardingStep.BATTERY -> StepContent(
            icon = Icons.Filled.Settings,
            title = context.getString(R.string.onboarding_battery_title),
            text = context.getString(R.string.onboarding_battery_text)
        )
        OnboardingStep.DONE -> StepContent(
            icon = Icons.Outlined.Info,
            title = context.getString(R.string.onboarding_done_title),
            text = context.getString(R.string.onboarding_done_text)
        )
    }

    Scaffold { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.size(96.dp)
                ) {
                    Box(
                        modifier = Modifier.size(96.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = stepContent.icon,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stepContent.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stepContent.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                if (step == OnboardingStep.BATTERY) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.BatteryAlert,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = context.getString(
                                        R.string.onboarding_battery_brand,
                                        info.brandLabel
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = info.instructions,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                deniedNote?.let { note ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = ::continueButton,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = when (step) {
                            OnboardingStep.EXPLAIN -> context.getString(R.string.onboarding_btn_start)
                            OnboardingStep.BATTERY, OnboardingStep.DONE ->
                                context.getString(R.string.onboarding_btn_finish)
                            OnboardingStep.ACTIVITY ->
                                if (activityAsked) context.getString(R.string.onboarding_btn_continue)
                                else context.getString(R.string.onboarding_activity_allow)
                            else -> context.getString(R.string.onboarding_btn_continue)
                        },
                        fontSize = 16.sp
                    )
                }

                when (step) {
                    OnboardingStep.LOCATION -> {
                        if (deniedNote != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    BatteryOptimization.openAppSettings(context)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.onboarding_open_settings_toast),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(context.getString(R.string.onboarding_btn_open_settings))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { advanceTo(OnboardingStep.BACKGROUND) }
                        ) {
                            Text(context.getString(R.string.onboarding_btn_skip))
                        }
                    }
                    OnboardingStep.BACKGROUND -> {
                        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                            if (deniedNote != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { BatteryOptimization.openAppSettings(context) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(context.getString(R.string.onboarding_btn_open_settings))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = { advanceTo(OnboardingStep.NOTIFICATIONS) }
                            ) {
                                Text(context.getString(R.string.onboarding_btn_skip))
                            }
                        }
                    }
                    OnboardingStep.NOTIFICATIONS -> {
                        if (deniedNote != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { BatteryOptimization.openAppSettings(context) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(context.getString(R.string.onboarding_btn_open_settings))
                            }
                            // Após recusar, o Android pode negar sozinho nas próximas
                            // vezes: sem "Pular" o usuário ficava preso nesta etapa.
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = { advanceTo(stepAfterNotifications()) }
                            ) {
                                Text(context.getString(R.string.onboarding_btn_skip))
                            }
                        }
                    }
                    OnboardingStep.ACTIVITY -> {
                        if (!activityAsked) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = { advanceTo(OnboardingStep.BATTERY) }
                            ) {
                                Text(context.getString(R.string.onboarding_btn_skip))
                            }
                        }
                    }
                    OnboardingStep.BATTERY -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { BatteryOptimization.openBatteryOptimizationSettings(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(context.getString(R.string.onboarding_btn_battery_settings))
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

private data class StepContent(
    val icon: ImageVector,
    val title: String,
    val text: String
)