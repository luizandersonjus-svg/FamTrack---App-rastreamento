package com.famtrack.app.data.remote

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.security.MessageDigest
import java.security.SecureRandom

sealed interface GoogleIdResult {
    data class Success(val idToken: String, val rawNonce: String) : GoogleIdResult
    data object Cancelled : GoogleIdResult
    data object NoAccount : GoogleIdResult
    data object Unavailable : GoogleIdResult
    data class Failed(val message: String?) : GoogleIdResult
}

object GoogleCredentialHelper {

    private const val TAG = "FamTrackAuth"

    suspend fun getGoogleIdToken(activityContext: Context, webClientId: String): GoogleIdResult {
        val rawNonce = generateNonce()
        val hashedNonce = sha256Hex(rawNonce)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .setNonce(hashedNonce)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
        return try {
            val credential = CredentialManager.create(activityContext)
                .getCredential(activityContext, request)
                .credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                try {
                    val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    Log.d(TAG, "credential manager: sucesso")
                    GoogleIdResult.Success(idToken, rawNonce)
                } catch (e: GoogleIdTokenParsingException) {
                    Log.d(TAG, "credential manager: falha parsing (${e.javaClass.simpleName})")
                    GoogleIdResult.Failed(e.message)
                }
            } else {
                Log.d(TAG, "credential manager: credencial inesperada")
                GoogleIdResult.Failed("Credencial inesperada")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.d(TAG, "credential manager: cancelado")
            GoogleIdResult.Cancelled
        } catch (e: NoCredentialException) {
            Log.d(TAG, "credential manager: sem conta")
            GoogleIdResult.NoAccount
        } catch (e: GetCredentialProviderConfigurationException) {
            Log.d(TAG, "credential manager: indisponivel")
            GoogleIdResult.Unavailable
        } catch (e: GetCredentialException) {
            Log.d(TAG, "credential manager: falha (${e.javaClass.simpleName})")
            GoogleIdResult.Failed(e.errorMessage?.toString() ?: e.message)
        } catch (e: Exception) {
            Log.d(TAG, "credential manager: falha (${e.javaClass.simpleName})")
            GoogleIdResult.Failed(e.message)
        }
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytesToHex(bytes)
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytesToHex(digest)
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}