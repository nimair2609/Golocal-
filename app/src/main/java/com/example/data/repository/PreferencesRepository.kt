package com.example.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.golocalPrefs: DataStore<Preferences> by preferencesDataStore(name = "golocal_client_prefs")

data class ClientPreferences(
    val language: AppLanguage = AppLanguage.EN,
    val darkThemeMode: String = "system", // system, light, dark
    val lowNetworkMode: Boolean = false,
    val draftPickupId: String = "",
    val draftDestinationId: String = "",
    val draftCategory: String = "toto",
    val customerOnboarded: Boolean = false,
    val riderOnboarded: Boolean = false
)

class PreferencesRepository(private val context: Context) {

    private val keyLang = stringPreferencesKey("preferred_language")
    private val keyTheme = stringPreferencesKey("dark_theme_mode")
    private val keyLowNet = booleanPreferencesKey("low_network_mode")
    private val keyDraftPickup = stringPreferencesKey("draft_pickup_id")
    private val keyDraftDest = stringPreferencesKey("draft_dest_id")
    private val keyDraftCat = stringPreferencesKey("draft_category")
    private val keyCustomerOnboarded = booleanPreferencesKey("customer_onboarded")
    private val keyRiderOnboarded = booleanPreferencesKey("rider_onboarded")

    val preferencesFlow: Flow<ClientPreferences> = context.golocalPrefs.data.map { prefs ->
        ClientPreferences(
            language = AppLanguage.fromCode(prefs[keyLang]),
            darkThemeMode = prefs[keyTheme] ?: "system",
            lowNetworkMode = prefs[keyLowNet] ?: false,
            draftPickupId = prefs[keyDraftPickup] ?: "",
            draftDestinationId = prefs[keyDraftDest] ?: "",
            draftCategory = prefs[keyDraftCat] ?: "toto",
            customerOnboarded = prefs[keyCustomerOnboarded] ?: false,
            riderOnboarded = prefs[keyRiderOnboarded] ?: false
        )
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.golocalPrefs.edit { it[keyLang] = language.code }
    }

    suspend fun setThemeMode(mode: String) {
        context.golocalPrefs.edit { it[keyTheme] = mode }
    }

    suspend fun setLowNetworkMode(enabled: Boolean) {
        context.golocalPrefs.edit { it[keyLowNet] = enabled }
    }

    suspend fun setCustomerOnboarded(done: Boolean) {
        context.golocalPrefs.edit { it[keyCustomerOnboarded] = done }
    }

    suspend fun setRiderOnboarded(done: Boolean) {
        context.golocalPrefs.edit { it[keyRiderOnboarded] = done }
    }

    suspend fun saveDraftBooking(pickupId: String, destId: String, category: String) {
        context.golocalPrefs.edit {
            it[keyDraftPickup] = pickupId
            it[keyDraftDest] = destId
            it[keyDraftCat] = category
        }
    }

    suspend fun clearDraftBooking() {
        context.golocalPrefs.edit {
            it.remove(keyDraftPickup)
            it.remove(keyDraftDest)
            it.remove(keyDraftCat)
        }
    }
}
