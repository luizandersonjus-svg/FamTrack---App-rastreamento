package com.famtrack.app.feature.onboarding

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.onboardingDataStore by preferencesDataStore(name = "onboarding")

/**
 * Persiste a flag "onboarding de permissões já exibido" em DataStore.
 */
object OnboardingPrefs {

    private val KEY_DONE = booleanPreferencesKey("done")

    suspend fun isDone(context: Context): Boolean {
        return context.onboardingDataStore.data.first()[KEY_DONE] ?: false
    }

    suspend fun setDone(context: Context, done: Boolean) {
        context.onboardingDataStore.edit { prefs ->
            prefs[KEY_DONE] = done
        }
    }
}