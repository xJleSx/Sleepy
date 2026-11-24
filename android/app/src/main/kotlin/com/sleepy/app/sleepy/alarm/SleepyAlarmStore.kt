package com.sleepy.app.sleepy.alarm

import android.content.Context

class SleepyAlarmStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun save(spec: AlarmSpec) {
        preferences.edit().putString(key(spec.id), spec.toJson()).apply()
    }

    fun remove(id: String) {
        preferences.edit().remove(key(id)).apply()
    }

    fun get(id: String): AlarmSpec? {
        return AlarmSpec.fromJson(preferences.getString(key(id), null))
    }

    fun all(): List<AlarmSpec> {
        return preferences.all.values
            .mapNotNull { value -> AlarmSpec.fromJson(value as? String) }
    }

    private fun key(id: String): String = "alarm_$id"

    companion object {
        private const val PREFERENCES_NAME = "sleepy_native_alarms"
    }
}
