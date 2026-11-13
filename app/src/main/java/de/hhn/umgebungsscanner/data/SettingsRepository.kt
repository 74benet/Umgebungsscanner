package de.hhn.umgebungsscanner.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.hhn.umgebungsscanner.challenge.ChallengeConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val matrikel: String = ChallengeConfig.DEFAULT_MATRIKEL,
    /** Debug-Schalter: tut so, als hätte das Gerät kein Gyroskop. */
    val simulateMissingGyro: Boolean = false,
)

/** Speichert Matrikelnummer, Debug-Schalter und Bestzeit dauerhaft (übersteht App-Neustart). */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            matrikel = prefs[KEY_MATRIKEL] ?: ChallengeConfig.DEFAULT_MATRIKEL,
            simulateMissingGyro = prefs[KEY_SIMULATE_MISSING_GYRO] ?: false,
        )
    }

    /** Bestzeit in Sekunden je Matrikelnummer (andere Werte = anderes Spiel), null = noch keine. */
    fun bestTime(matrikel: String): Flow<Float?> =
        store.data.map { prefs -> prefs[bestTimeKey(matrikel)] }

    suspend fun setMatrikel(matrikel: String) {
        store.edit { it[KEY_MATRIKEL] = matrikel }
    }

    suspend fun setSimulateMissingGyro(enabled: Boolean) {
        store.edit { it[KEY_SIMULATE_MISSING_GYRO] = enabled }
    }

    /** Speichert die Zeit nur, wenn sie besser ist. Liefert true bei neuer Bestzeit. */
    suspend fun submitTime(matrikel: String, seconds: Float): Boolean {
        var improved = false
        store.edit { prefs ->
            val key = bestTimeKey(matrikel)
            val old = prefs[key]
            if (old == null || seconds < old) {
                prefs[key] = seconds
                improved = true
            }
        }
        return improved
    }

    suspend fun resetBestTime(matrikel: String) {
        store.edit { it.remove(bestTimeKey(matrikel)) }
    }

    private fun bestTimeKey(matrikel: String) = floatPreferencesKey("best_time_$matrikel")

    private companion object {
        val KEY_MATRIKEL = stringPreferencesKey("matrikel")
        val KEY_SIMULATE_MISSING_GYRO = booleanPreferencesKey("simulate_missing_gyro")
    }
}
