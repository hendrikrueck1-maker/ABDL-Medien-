package de.hr.mediathek.data.compress

import org.json.JSONObject

/** Parameter eines COMPRESS-Jobs. [confirmed] = der Nutzer hat Warnungen (Verlust, schon klein) bestätigt. */
data class CompressionParams(val mediaUuid: String, val level: CompressionLevel, val confirmed: Boolean) {
    fun toJson(): String = JSONObject()
        .put("mediaUuid", mediaUuid)
        .put("level", level.name)
        .put("confirmed", confirmed)
        .toString()

    companion object {
        fun parse(json: String): CompressionParams {
            val o = JSONObject(json)
            return CompressionParams(
                mediaUuid = o.getString("mediaUuid"),
                level = runCatching { CompressionLevel.valueOf(o.optString("level")) }.getOrDefault(CompressionLevel.BALANCED),
                confirmed = o.optBoolean("confirmed", false),
            )
        }
    }
}
