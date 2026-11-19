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

    /** Standard: nur beim Laden. */
    fun observeOnlyCharging(): Flow<Boolean> = settings.observe(KEY_ONLY_CHARGING).map { it != "0" }

    /** Standard: Originale bleiben im Ordner _alt und lassen sich weiter abspielen, bis du sie löschst. */
    fun observeKeepOriginals(): Flow<Boolean> = settings.observe(KEY_KEEP_ORIGINALS).map { it != "0" }

    suspend fun keepOriginals(): Boolean = settings.get(KEY_KEEP_ORIGINALS) != "0"

    suspend fun setKeepOriginals(value: Boolean) =
        settings.set(SettingEntity(KEY_KEEP_ORIGINALS, if (value) "1" else "0"))

    suspend fun setPaused(value: Boolean) = settings.set(SettingEntity(KEY_PAUSED, if (value) "1" else "0"))
    suspend fun setOnlyCharging(value: Boolean) = settings.set(SettingEntity(KEY_ONLY_CHARGING, if (value) "1" else "0"))

    suspend fun mayRunNow(): Boolean {
        val paused = settings.get(KEY_PAUSED) == "1"
        val onlyCharging = settings.get(KEY_ONLY_CHARGING) != "0"
        val charging = context.getSystemService(BatteryManager::class.java)?.isCharging == true
        return CompressionRunPolicy.mayRun(paused, onlyCharging, charging)
    }

    suspend fun onlyCharging(): Boolean = settings.get(KEY_ONLY_CHARGING) != "0"

    private companion object {
        const val KEY_PAUSED = "compress_paused"
        const val KEY_ONLY_CHARGING = "compress_only_charging"
        const val KEY_KEEP_ORIGINALS = "compress_keep_originals"
    }
}
