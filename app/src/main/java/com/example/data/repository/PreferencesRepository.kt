package com.example.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.golocalPrefs: DataStore<Preferences> by preferencesDataStore(name = "golocal_client_prefs")

data class ClientPreferences(
    val language: AppLanguage = AppLanguage.EN,
    val darkThemeMode: String = "system", // system, light, dark
    val lowNetworkMode: Boolean = false,
    val draftPickupId: String = "",
    val draftDestinationId: String = "",
    val draftCategory: String = "toto",
    val customerOnboarded: Boolean = false,
    val riderOnboarded: Boolean = false,
    val testRiderIntent: Boolean = false
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
    private val keyTestRiderIntent = booleanPreferencesKey("test_rider_intent")
    private val keyDeviceInstallationId = stringPreferencesKey("device_installation_id")
    private val keyActiveAppVariants = stringSetPreferencesKey("active_app_variants")

    val preferencesFlow: Flow<ClientPreferences> = context.golocalPrefs.data.map { prefs ->
        ClientPreferences(
            language = AppLanguage.fromCode(prefs[keyLang]),
            darkThemeMode = prefs[keyTheme] ?: "system",
            lowNetworkMode = prefs[keyLowNet] ?: false,
            draftPickupId = prefs[keyDraftPickup] ?: "",
            draftDestinationId = prefs[keyDraftDest] ?: "",
            draftCategory = prefs[keyDraftCat] ?: "toto",
            customerOnboarded = prefs[keyCustomerOnboarded] ?: false,
            riderOnboarded = prefs[keyRiderOnboarded] ?: false,
            testRiderIntent = prefs[keyTestRiderIntent] ?: false
        )
    }

    suspend fun getOrCreateDeviceId(): String {
        val current = context.golocalPrefs.data.first()[keyDeviceInstallationId].orEmpty()
        if (current.isNotBlank()) return current
        val generated = UUID.randomUUID().toString().replace("-", "").take(16)
        var saved = generated
        context.golocalPrefs.edit { prefs ->
            val existing = prefs[keyDeviceInstallationId].orEmpty()
            if (existing.isNotBlank()) {
                saved = existing
            } else {
                prefs[keyDeviceInstallationId] = generated
                saved = generated
            }
        }
        return saved
    }

    suspend fun recordActiveAppVariant(variant: String) {
        val clean = variant.trim().lowercase().takeIf { it in setOf("customer", "rider", "admin") } ?: return
        context.golocalPrefs.edit { prefs ->
            val current = prefs[keyActiveAppVariants].orEmpty()
            prefs[keyActiveAppVariants] = current + clean
        }
    }

    suspend fun getActiveAppVariants(): Set<String> {
        return context.golocalPrefs.data.first()[keyActiveAppVariants].orEmpty()
    }

    suspend fun clearActiveAppVariants() {
        context.golocalPrefs.edit { prefs ->
            prefs.remove(keyActiveAppVariants)
        }
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

    suspend fun setTestRiderIntent(enabled: Boolean) {
        context.golocalPrefs.edit { it[keyTestRiderIntent] = enabled }
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
