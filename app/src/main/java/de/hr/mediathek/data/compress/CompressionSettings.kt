package de.hr.mediathek.data.compress

import android.content.Context
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Geräteeinstellungen der Warteschlange (Schlüssel "compress_*" werden nie exportiert oder importiert). */
@Singleton
class CompressionSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingDao,
) {
    fun observePaused(): Flow<Boolean> = settings.observe(KEY_PAUSED).map { it == "1" }

    /** Standard: auch im Akkubetrieb (nur bei Einstellung \"1\" nur am Ladegerät). */
    fun observeOnlyCharging(): Flow<Boolean> = settings.observe(KEY_ONLY_CHARGING).map { it == "1" }

    /**
     * Standard: Originale liegen 7 Tage im Ordner _alt (Schonfrist) und werden dann gelöscht.
     * Nur wer „Originale behalten“ ausdrücklich eingeschaltet hat ("1"), behält sie dauerhaft.
     */
    fun observeKeepOriginals(): Flow<Boolean> = settings.observe(KEY_KEEP_ORIGINALS).map { it == "1" }

    suspend fun keepOriginals(): Boolean = settings.get(KEY_KEEP_ORIGINALS) == "1"

    /** Die Komprimierung konnte nicht als Vordergrunddienst starten und wartet, bis die App geöffnet wird. */
    fun observeWaitingForApp(): Flow<Boolean> = settings.observe(KEY_WAITING).map { it == "1" }

    suspend fun setWaitingForApp(value: Boolean) = settings.set(SettingEntity(KEY_WAITING, if (value) "1" else "0"))

    suspend fun setKeepOriginals(value: Boolean) =
        settings.set(SettingEntity(KEY_KEEP_ORIGINALS, if (value) "1" else "0"))

    suspend fun setPaused(value: Boolean) = settings.set(SettingEntity(KEY_PAUSED, if (value) "1" else "0"))
    suspend fun setOnlyCharging(value: Boolean) = settings.set(SettingEntity(KEY_ONLY_CHARGING, if (value) "1" else "0"))

    suspend fun mayRunNow(): Boolean {
        val paused = settings.get(KEY_PAUSED) == "1"
        val onlyCharging = settings.get(KEY_ONLY_CHARGING) == "1"
        val charging = context.getSystemService(BatteryManager::class.java)?.isCharging == true
        return CompressionRunPolicy.mayRun(paused, onlyCharging, charging)
    }

    suspend fun onlyCharging(): Boolean = settings.get(KEY_ONLY_CHARGING) == "1"

    private companion object {
        const val KEY_PAUSED = "compress_paused"
        const val KEY_ONLY_CHARGING = "compress_only_charging"
        const val KEY_KEEP_ORIGINALS = "compress_keep_originals"
        const val KEY_WAITING = "compress_waiting_for_app"
    }
}
