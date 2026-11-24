package com.sleepy.app.sleepy.alarm

import org.json.JSONArray
import org.json.JSONObject

data class AlarmSpec(
    val id: String,
    val label: String,
    val hour: Int,
    val minute: Int,
    val weekdays: Set<Int>,
    val sound: String,
    val volume: Float,
    val smartEnabled: Boolean,
    val smartStartMinute: Int,
    val smartEndMinute: Int,
) {
    fun toJson(): String {
        val weekdaysJson = JSONArray()
        weekdays.sorted().forEach(weekdaysJson::put)
        return JSONObject()
            .put("id", id)
            .put("label", label)
            .put("hour", hour)
            .put("minute", minute)
            .put("weekdays", weekdaysJson)
            .put("sound", sound)
            .put("volume", volume.toDouble())
            .put("smartEnabled", smartEnabled)
            .put("smartStartMinute", smartStartMinute)
            .put("smartEndMinute", smartEndMinute)
            .toString()
    }

    companion object {
        fun fromChannel(raw: Map<*, *>?): AlarmSpec {
            if (raw == null) {
                throw IllegalArgumentException("Параметры будильника не переданы")
            }

            val id = raw.string("id").ifBlank {
                throw IllegalArgumentException("Не указан id будильника")
            }
            val label = raw.string("label").ifBlank { "Будильник" }
            val hour = raw.int("hour", 0, 23)
            val minute = raw.int("minute", 0, 59)

            val weekdays = mutableSetOf<Int>()
            val rawWeekdays = raw["weekdays"]
            if (rawWeekdays is List<*>) {
                for (value in rawWeekdays) {
                    val day = (value as? Number)?.toInt() ?: continue
                    if (day in 1..7) weekdays.add(day)
                }
            }

            val volume = (raw["volume"] as? Number)?.toFloat()?.coerceIn(0f, 1f) ?: 0.8f
            val smartStart = (raw["smartStartMinute"] as? Number)?.toInt()?.coerceIn(0, 1439) ?: 390
            val smartEnd = (raw["smartEndMinute"] as? Number)?.toInt()?.coerceIn(0, 1439) ?: 420

            return AlarmSpec(
                id = id,
                label = label,
                hour = hour,
                minute = minute,
                weekdays = weekdays,
                sound = raw.string("sound").ifBlank { "alarm" },
                volume = volume,
                smartEnabled = raw["smartEnabled"] == true,
                smartStartMinute = smartStart,
                smartEndMinute = smartEnd,
            )
        }

        fun fromJson(raw: String?): AlarmSpec? {
            if (raw.isNullOrBlank()) return null
            return try {
                val json = JSONObject(raw)
                val weekdays = mutableSetOf<Int>()
                val array = json.optJSONArray("weekdays")
                if (array != null) {
                    for (index in 0 until array.length()) {
                        val day = array.optInt(index)
                        if (day in 1..7) weekdays.add(day)
                    }
                }

                val id = json.optString("id")
                if (id.isBlank()) return null

                AlarmSpec(
                    id = id,
                    label = json.optString("label", "Будильник"),
                    hour = json.optInt("hour", 7).coerceIn(0, 23),
                    minute = json.optInt("minute", 0).coerceIn(0, 59),
                    weekdays = weekdays,
                    sound = json.optString("sound", "alarm"),
                    volume = json.optDouble("volume", 0.8).toFloat().coerceIn(0f, 1f),
                    smartEnabled = json.optBoolean("smartEnabled", false),
                    smartStartMinute = json.optInt("smartStartMinute", 390).coerceIn(0, 1439),
                    smartEndMinute = json.optInt("smartEndMinute", 420).coerceIn(0, 1439),
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun Map<*, *>.string(key: String): String = this[key] as? String ?: ""

        private fun Map<*, *>.int(key: String, min: Int, max: Int): Int {
            val value = (this[key] as? Number)?.toInt()
                ?: throw IllegalArgumentException("Поле $key обязательно")
            if (value !in min..max) {
                throw IllegalArgumentException("Поле $key вне допустимого диапазона")
            }
            return value
        }
    }
}
