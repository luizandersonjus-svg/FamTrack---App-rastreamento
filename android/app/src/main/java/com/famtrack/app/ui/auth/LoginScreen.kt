package com.famtrack.app.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.famtrack.app.R
import com.famtrack.app.data.remote.AuthCallbackState
import com.famtrack.app.data.remote.AuthRepository
import com.famtrack.app.data.remote.GoogleCredentialHelper
import com.famtrack.app.data.remote.GoogleIdResult
import com.famtrack.app.ui.theme.Blue500
import com.famtrack.app.ui.theme.Blue700
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    onNavigateToRegister: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    var googleWaiting by remember { mutableStateOf(false) }
    var googleSigningIn by remember { mutableStateOf(false) }
    var googleShowBrowserFallback by remember { mutableStateOf(false) }
    var navigateHandled by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val authRepository = remember { AuthRepository() }

    val sessionStatus by authRepository.sessionStatus.collectAsState()

    LaunchedEffect(sessionStatus) {
        Log.d("FamTrackAuth", "sessionStatus: ${sessionStatus::class.simpleName}")
        if (sessionStatus is SessionStatus.Authenticated && !navigateHandled) {
            navigateHandled = true
            onLoginSuccess()
        }
    }

    val callbackError by AuthCallbackState.error.collectAsState()

    LaunchedEffect(callbackError) {
        val error = callbackError ?: return@LaunchedEffect
        errorMessage = mapGoogleError(context, error.code, error.description)
        googleWaiting = false
        AuthCallbackState.clear()
    }

    LaunchedEffect(googleWaiting) {
        if (googleWaiting) {
            delay(3 * 60_000L)
            if (googleWaiting && !navigateHandled) {
                errorMessage = context.getString(R.string.login_google_not_completed)
                googleWaiting = false
            }
        }
    }

    LifecycleResumeEffect(googleWaiting) {
        if (googleWaiting) {
            val windowJob = scope.launch {
                delay(25_000)
                if (googleWaiting && !navigateHandled) {
                    errorMessage = context.getString(R.string.login_google_not_completed)
                    googleWaiting = false
                }
            }
            onPauseOrDispose { windowJob.cancel() }
        } else {
            onPauseOrDispose { }
        }
    }

    val googleOAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { }

    val startWebOAuth: () -> Unit = {
        googleSigningIn = false
        googleShowBrowserFallback = false
        try {
            val url = authRepository.getGoogleOAuthUrl()
            Log.d("FamTrackAuth", "OAuth URL gerada")
            googleWaiting = true
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            googleOAuthLauncher.launch(intent)
        } catch (e: Exception) {
            errorMessage = mapGoogleError(context, null, e.message)
            googleWaiting = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(56.dp))

            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Blue500, Blue700)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "F",
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "FamTrack",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Acompanhe sua família em tempo real",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        leadingIcon = {
                            Icon(Icons.Default.Email, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp)),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Senha") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null)
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp)),
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = errorMessage!!,
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                errorMessage = null
                                try {
                                    authRepository.signInWithEmail(email, password)
                                    navigateHandled = true
                                    onLoginSuccess()
                                } catch (e: Exception) {
                                    val msg = e.message ?: ""
                                    errorMessage = when {
                                        msg.contains("Invalid login credentials") -> "Email ou senha incorretos"
                                        msg.contains("Email not confirmed") -> "Confirme seu email antes de entrar"
                                        msg.contains("network") -> "Sem conexao com a internet"
                                        else -> "Erro ao entrar. Tente novamente."
                                    }
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isLoading && email.isNotBlank() && password.isNotBlank()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Entrar", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            errorMessage = null
                            googleShowBrowserFallback = false
                            val activity = context.findActivity()
                            if (activity == null) {
                                Log.w("FamTrackAuth", "context nao e Activity; usando fallback web OAuth")
                                startWebOAuth()
                                return@OutlinedButton
                            }
                            googleSigningIn = true
                            scope.launch {
                                val webClientId = context.getString(R.string.default_web_client_id)
                                when (val result = GoogleCredentialHelper.getGoogleIdToken(activity, webClientId)) {
                                    is GoogleIdResult.Success -> {
                                        googleSigningIn = false
                                        try {
                                            authRepository.signInWithGoogle(result.idToken, result.rawNonce)
                                            navigateHandled = true
                                            onLoginSuccess()
                                        } catch (e: Exception) {
                                            errorMessage = mapGoogleError(context, null, e.message)
                                        }
                                    }

                                    GoogleIdResult.Cancelled -> {
                                        Log.d("FamTrackAuth", "google signing cancelado pelo usuario")
                                        googleSigningIn = false
                                    }

                                    GoogleIdResult.NoAccount -> {
                                        errorMessage = context.getString(R.string.login_google_err_no_account)
                                        googleSigningIn = false
                                        googleShowBrowserFallback = true
                                    }

                                    GoogleIdResult.Unavailable -> {
                                        Log.d("FamTrackAuth", "credential manager indisponivel -> fallback web OAuth")
                                        startWebOAuth()
                                    }

                                    is GoogleIdResult.Failed -> {
                                        errorMessage = mapGoogleError(context, null, result.message)
                                        googleSigningIn = false
                                        googleShowBrowserFallback = true
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isLoading && !googleWaiting && !googleSigningIn
                    ) {
                        if (googleSigningIn) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = context.getString(R.string.login_google_connecting),
                                    fontSize = 16.sp
                                )
                            }
                        } else {
                            Text("Entrar com Google", fontSize = 16.sp)
                        }
                    }

                    if (googleShowBrowserFallback) {
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(
                            onClick = { startWebOAuth() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(context.getString(R.string.login_google_use_browser))
                        }
                    }

                    if (googleWaiting) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = context.getString(R.string.login_google_waiting),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    googleWaiting = false
                                    errorMessage = null
                                }
                            ) {
                                Text(context.getString(R.string.login_google_cancel))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Nao tem conta? ",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onNavigateToRegister,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Cadastre-se", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun mapGoogleError(context: Context, code: String?, description: String?): String {
    val combined = listOfNotNull(code, description).joinToString(" ").lowercase()
    return when {
        combined.contains("audience") ||
            combined.contains("aud mismatch") ||
            combined.contains("invalid_aud") ->
            context.getString(R.string.login_google_err_client_id)

        combined.contains("nonce") ->
            context.getString(R.string.login_google_err_nonce)

        combined.contains("provider is not enabled") || combined.contains("unsupported provider") ->
            context.getString(R.string.login_google_err_provider_disabled)

        combined.contains("redirec") ->
            context.getString(R.string.login_google_err_redirect)

        combined.contains("access_denied") ->
            context.getString(R.string.login_google_err_denied)

        combined.contains("signups not allowed") ->
            context.getString(R.string.login_google_err_signup)

        combined.contains("invalid_grant") ||
            combined.contains("code verifier") ||
            combined.contains("pkce") ||
            combined.contains("flow state") ->
            context.getString(R.string.login_google_err_pkce)

        combined.contains("unknownhost") ||
            combined.contains("timeout") ||
            combined.contains("failed to connect") ||
            combined.contains("unable to resolve") ||
            combined.contains("no address associated") ||
            combined.contains("network") ->
            context.getString(R.string.login_google_err_network)

        else -> {
            val generic = context.getString(R.string.login_google_err_generic)
            val detail = description?.trim()?.take(120)
            if (!detail.isNullOrBlank()) "$generic\n$detail" else generic
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
