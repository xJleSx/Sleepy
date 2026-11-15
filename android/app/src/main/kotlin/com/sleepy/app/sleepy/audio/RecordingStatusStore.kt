package com.sleepy.app.sleepy.audio

import android.content.Context
import android.content.SharedPreferences

/**
 * Единая точка чтения и записи состояния мониторинга.
 *
 * Android-служба пишет состояние, Flutter читает его через канал.
 * Ключи `is_recording`, `file_path`, `started_at` и `error` сохранены
 * для обратной совместимости с текущим интерфейсом.
 */
class RecordingStatusStore(context: Context) {

    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun startSession(sessionId: String, startedAtMillis: Long) {
        preferences.edit()
            .putBoolean(KEY_IS_RECORDING, true)
            .putBoolean(KEY_INTERRUPTED, false)
            .putString(KEY_SESSION_ID, sessionId)
            .putLong(KEY_STARTED_AT, startedAtMillis)
            .putLong(KEY_ENDED_AT, 0L)
            .putLong(KEY_HEARTBEAT_AT, startedAtMillis)
            .putString(KEY_FILE_PATH, "")
            .putString(KEY_LAST_EVENT_PATH, "")
            .putLong(KEY_LAST_EVENT_AT, 0L)
            .putInt(KEY_EVENT_COUNT, 0)
            .putString(KEY_ERROR, "")
            .apply()
    }

    fun markStopped() {
        val now = System.currentTimeMillis()

        preferences.edit()
            .putBoolean(KEY_IS_RECORDING, false)
            .putBoolean(KEY_INTERRUPTED, false)
            .putLong(KEY_ENDED_AT, now)
            .putLong(KEY_HEARTBEAT_AT, now)
            .apply()
    }

    fun markInterrupted(reason: String) {
        val now = System.currentTimeMillis()

        preferences.edit()
            .putBoolean(KEY_IS_RECORDING, false)
            .putBoolean(KEY_INTERRUPTED, true)
            .putLong(KEY_ENDED_AT, now)
            .putLong(KEY_HEARTBEAT_AT, now)
            .putString(KEY_ERROR, reason)
            .apply()
    }

    @Synchronized
    fun recordEvent(filePath: String, eventAtMillis: Long) {
        val eventCount = preferences.getInt(KEY_EVENT_COUNT, 0) + 1

        preferences.edit()
            .putInt(KEY_EVENT_COUNT, eventCount)
            .putString(KEY_LAST_EVENT_PATH, filePath)
            .putLong(KEY_LAST_EVENT_AT, eventAtMillis)
            .putString(KEY_FILE_PATH, filePath)
            .apply()
    }

    /**
     * Ошибка, которая не прерывает сессию: например, не удалось записать
     * один фрагмент из-за нехватки места.
     */
    fun reportWarning(message: String) {
        preferences.edit()
            .putString(KEY_ERROR, message)
            .apply()
    }

    /**
     * Ошибка, после которой запись остановлена.
     */
    fun saveFatalError(message: String) {
        val now = System.currentTimeMillis()

        preferences.edit()
            .putBoolean(KEY_IS_RECORDING, false)
            .putLong(KEY_ENDED_AT, now)
            .putLong(KEY_HEARTBEAT_AT, now)
            .putString(KEY_ERROR, message)
            .apply()
    }

    fun clearError() {
        preferences.edit()
            .putString(KEY_ERROR, "")
            .apply()
    }

    fun touchHeartbeat() {
        preferences.edit()
            .putLong(KEY_HEARTBEAT_AT, System.currentTimeMillis())
            .apply()
    }

    fun isRecording(): Boolean = preferences.getBoolean(KEY_IS_RECORDING, false)

    fun sessionId(): String = preferences.getString(KEY_SESSION_ID, "").orEmpty()

    fun startedAtMillis(): Long = preferences.getLong(KEY_STARTED_AT, 0L)

    fun eventCount(): Int = preferences.getInt(KEY_EVENT_COUNT, 0)

    fun lastEventPath(): String = preferences.getString(KEY_LAST_EVENT_PATH, "").orEmpty()

    fun error(): String = preferences.getString(KEY_ERROR, "").orEmpty()

    fun readStatus(): Map<String, Any> {
        val now = System.currentTimeMillis()
        val isRecording = preferences.getBoolean(KEY_IS_RECORDING, false)
        val heartbeatAt = preferences.getLong(KEY_HEARTBEAT_AT, 0L)

        val sessionLooksAlive = isRecording &&
            heartbeatAt > 0L &&
            now - heartbeatAt <= HEARTBEAT_TIMEOUT_MS

        return hashMapOf(
            "isRecording" to sessionLooksAlive,
            "filePath" to preferences.getString(KEY_FILE_PATH, "").orEmpty(),
            "startedAtMillis" to preferences.getLong(KEY_STARTED_AT, 0L),
            "error" to preferences.getString(KEY_ERROR, "").orEmpty(),
            "sessionId" to preferences.getString(KEY_SESSION_ID, "").orEmpty(),
            "eventCount" to preferences.getInt(KEY_EVENT_COUNT, 0),
            "lastEventPath" to preferences.getString(KEY_LAST_EVENT_PATH, "").orEmpty(),
            "lastEventAtMillis" to preferences.getLong(KEY_LAST_EVENT_AT, 0L),
            "interrupted" to preferences.getBoolean(KEY_INTERRUPTED, false),
            "endedAtMillis" to preferences.getLong(KEY_ENDED_AT, 0L),
            "heartbeatAtMillis" to heartbeatAt,
        )
    }

    companion object {
        const val PREFS_NAME = "sleepy_recording"

        const val KEY_IS_RECORDING = "is_recording"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_ENDED_AT = "ended_at"
        const val KEY_ERROR = "error"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_EVENT_COUNT = "event_count"
        const val KEY_LAST_EVENT_PATH = "last_event_path"
        const val KEY_LAST_EVENT_AT = "last_event_at"
        const val KEY_INTERRUPTED = "interrupted"
        const val KEY_HEARTBEAT_AT = "heartbeat_at"

        /**
         * Если служба не обновляла heartbeat дольше этого времени,
         * считаем, что сессия прервана системой.
         */
        const val HEARTBEAT_TIMEOUT_MS = 15_000L
    }
}
