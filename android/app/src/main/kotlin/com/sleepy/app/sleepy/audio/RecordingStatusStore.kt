package com.sleepy.app.sleepy.audio

import android.content.Context
import android.content.SharedPreferences

/**
 * Единая точка чтения и записи состояния мониторинга.
 *
 * Android-служба пишет состояние, Flutter читает его через канал.
 * Ключи `is_recording`, `file_path`, `started_at` и `error` сохранены
 * для обратной совместимости с текущим интерфейсом.
 *
 * Дополнительно хранятся счётчики обнаруженных событий и сохранённых
 * фрагментов, путь к последнему фрагменту и последнее предупреждение.
 *
 * `fragment_count` — это число WAV-файлов в каталоге записей: при старте
 * сессии он сверяется с диском ([startSession]), а не обнуляется. Файлы
 * переживают сессии, поэтому счётчик тоже обязан их учитывать. Счётчик
 * событий (`event_count`) накопительный: подтверждённое событие может
 * дать несколько фрагментов, но посчитать события заново по диску нельзя.
 *
 * Все записи идут через `apply()`: диск обновляется в фоне, вызывающий
 * поток не ждёт.
 */
class RecordingStatusStore internal constructor(
    private val preferences: SharedPreferences,
) : RecordingStatusSink {

    constructor(context: Context) : this(
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    override fun startSession(
        sessionId: String,
        startedAtMillis: Long,
        savedFragmentsOnDisk: Int,
    ) {
        val editor = preferences.edit()
            .putBoolean(KEY_IS_RECORDING, true)
            .putBoolean(KEY_INTERRUPTED, false)
            .putString(KEY_SESSION_ID, sessionId)
            .putLong(KEY_STARTED_AT, startedAtMillis)
            .putLong(KEY_ENDED_AT, 0L)
            .putLong(KEY_HEARTBEAT_AT, startedAtMillis)
            .putString(KEY_FILE_PATH, "")
            .putString(KEY_LAST_EVENT_PATH, "")
            .putLong(KEY_LAST_EVENT_AT, 0L)
            .putString(KEY_LAST_FRAGMENT_PATH, "")
            .putLong(KEY_LAST_FRAGMENT_AT, 0L)
            .putString(KEY_LAST_WARNING, "")
            .putString(KEY_ERROR, "")
            .putLong(KEY_SAMPLES_READ, 0L)
            .putLong(KEY_READ_ERROR_COUNT, 0L)
            .putFloat(KEY_LAST_CHUNK_DBFS, AudioEventDetector.MIN_DBFS.toFloat())
            .putFloat(KEY_NOISE_FLOOR_DBFS, AudioEventDetector.MIN_DBFS.toFloat())
            .putString(KEY_DETECTOR_STATE, "")
            .putLong(KEY_CANDIDATE_COUNT, 0L)
            .putLong(KEY_CONFIRMED_EVENT_COUNT, 0L)
            .putLong(KEY_WRITER_OPEN_COUNT, 0L)
            .putLong(KEY_WRITER_SAVED_COUNT, 0L)
            .putLong(KEY_WRITER_FAILED_COUNT, 0L)
            .putString(KEY_LAST_WRITER_ERROR, "")
            .putString(KEY_DIAGNOSTIC_PROFILE, selectedProfile().storageValue)
            .putLong(KEY_WEAK_CANDIDATE_COUNT, 0L)
            .putLong(KEY_WEAK_SAVED_COUNT, 0L)
            .putLong(KEY_WEAK_REJECTED_COUNT, 0L)
            .putString(KEY_LAST_WEAK_REJECTION, "")

        if (savedFragmentsOnDisk >= 0) {
            editor.putInt(KEY_FRAGMENT_COUNT, savedFragmentsOnDisk)
        }

        editor.apply()
    }

    override fun markStopped() {
        val now = System.currentTimeMillis()

        preferences.edit()
            .putBoolean(KEY_IS_RECORDING, false)
            .putBoolean(KEY_INTERRUPTED, false)
            .putLong(KEY_ENDED_AT, now)
            .putLong(KEY_HEARTBEAT_AT, now)
            .apply()
    }

    override fun markInterrupted(reason: String) {
        val now = System.currentTimeMillis()

        preferences.edit()
            .putBoolean(KEY_IS_RECORDING, false)
            .putBoolean(KEY_INTERRUPTED, true)
            .putLong(KEY_ENDED_AT, now)
            .putLong(KEY_HEARTBEAT_AT, now)
            .putString(KEY_ERROR, reason)
            .apply()
    }

    /**
     * Событие обнаружено, но фрагмент ещё не сохранён (post-roll пишется
     * несколько секунд). Путь к файлу появится в [recordFragmentSaved].
     */
    @Synchronized
    override fun recordEventDetected(eventAtMillis: Long) {
        val eventCount = preferences.getInt(KEY_EVENT_COUNT, 0) + 1

        preferences.edit()
            .putInt(KEY_EVENT_COUNT, eventCount)
            .putLong(KEY_LAST_EVENT_AT, eventAtMillis)
            .apply()
    }

    /** Фрагмент сохранён: обновляет счётчик и путь для экрана «Главная». */
    @Synchronized
    override fun recordFragmentSaved(filePath: String, fragmentAtMillis: Long) {
        val fragmentCount = preferences.getInt(KEY_FRAGMENT_COUNT, 0) + 1

        preferences.edit()
            .putInt(KEY_FRAGMENT_COUNT, fragmentCount)
            .putString(KEY_LAST_FRAGMENT_PATH, filePath)
            .putLong(KEY_LAST_FRAGMENT_AT, fragmentAtMillis)
            // Совместимость со старым контрактом: filePath и last_event_path.
            .putString(KEY_FILE_PATH, filePath)
            .putString(KEY_LAST_EVENT_PATH, filePath)
            .putLong(KEY_LAST_EVENT_AT, fragmentAtMillis)
            .apply()
    }

    /**
     * Старый метод: увеличивает сразу и счётчик событий, и путь к файлу.
     * Оставлен для совместимости; новая служба использует
     * [recordEventDetected] и [recordFragmentSaved] по отдельности.
     */
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
     *
     * Предупреждение не пишется в `error`: иначе интерфейс показывал бы
     * частичный отказ как полную остановку записи. Критические ошибки
     * выставляются через [saveFatalError] и [markInterrupted].
     */
    override fun reportWarning(message: String) {
        preferences.edit()
            .putString(KEY_LAST_WARNING, message)
            .apply()
    }

    /**
     * Ошибка, после которой запись остановлена.
     */
    override fun saveFatalError(message: String) {
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

    fun selectedProfile(): RecordingProfile = RecordingProfile.fromStorage(
        preferences.getString(KEY_RECORDING_PROFILE, null),
    )

    fun setRecordingProfile(profile: RecordingProfile) {
        preferences.edit()
            .putString(KEY_RECORDING_PROFILE, profile.storageValue)
            .apply()
    }

    override fun touchHeartbeat() {
        preferences.edit()
            .putLong(KEY_HEARTBEAT_AT, System.currentTimeMillis())
            .apply()
    }

    @Synchronized
    override fun updateDiagnostics(diagnostics: RecordingDiagnostics) {
        preferences.edit()
            .putLong(KEY_SAMPLES_READ, diagnostics.samplesRead)
            .putLong(KEY_READ_ERROR_COUNT, diagnostics.readErrorCount)
            .putFloat(KEY_LAST_CHUNK_DBFS, diagnostics.lastChunkDbfs.toFloat())
            .putFloat(KEY_NOISE_FLOOR_DBFS, diagnostics.noiseFloorDbfs.toFloat())
            .putString(KEY_DETECTOR_STATE, diagnostics.currentDetectorState)
            .putLong(KEY_CANDIDATE_COUNT, diagnostics.candidateCount)
            .putLong(KEY_CONFIRMED_EVENT_COUNT, diagnostics.confirmedEventCount)
            .putLong(KEY_WRITER_OPEN_COUNT, diagnostics.writerOpenCount)
            .putLong(KEY_WRITER_SAVED_COUNT, diagnostics.writerSavedCount)
            .putLong(KEY_WRITER_FAILED_COUNT, diagnostics.writerFailedCount)
            .putString(KEY_LAST_WRITER_ERROR, diagnostics.lastWriterError)
            .putString(KEY_LAST_FRAGMENT_PATH, diagnostics.lastFragmentPath)
            .putString(KEY_DIAGNOSTIC_PROFILE, diagnostics.profileId)
            .putLong(KEY_WEAK_CANDIDATE_COUNT, diagnostics.weakCandidateCount)
            .putLong(KEY_WEAK_SAVED_COUNT, diagnostics.weakSavedCount)
            .putLong(KEY_WEAK_REJECTED_COUNT, diagnostics.weakRejectedCount)
            .putString(KEY_LAST_WEAK_REJECTION, diagnostics.lastWeakRejection)
            .apply()
    }

    override fun isRecording(): Boolean =
        preferences.getBoolean(KEY_IS_RECORDING, false)

    fun sessionId(): String = preferences.getString(KEY_SESSION_ID, "").orEmpty()

    fun startedAtMillis(): Long = preferences.getLong(KEY_STARTED_AT, 0L)

    fun eventCount(): Int = preferences.getInt(KEY_EVENT_COUNT, 0)

    fun fragmentCount(): Int = preferences.getInt(KEY_FRAGMENT_COUNT, 0)

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
            "fragmentCount" to preferences.getInt(KEY_FRAGMENT_COUNT, 0),
            "lastFragmentPath" to
                preferences.getString(KEY_LAST_FRAGMENT_PATH, "").orEmpty(),
            "lastFragmentAtMillis" to preferences.getLong(KEY_LAST_FRAGMENT_AT, 0L),
            "lastWarning" to preferences.getString(KEY_LAST_WARNING, "").orEmpty(),
            "interrupted" to preferences.getBoolean(KEY_INTERRUPTED, false),
            "endedAtMillis" to preferences.getLong(KEY_ENDED_AT, 0L),
            "heartbeatAtMillis" to heartbeatAt,
            "samplesRead" to preferences.getLong(KEY_SAMPLES_READ, 0L),
            "readErrorCount" to preferences.getLong(KEY_READ_ERROR_COUNT, 0L),
            "lastChunkDbfs" to preferences
                .getFloat(KEY_LAST_CHUNK_DBFS, AudioEventDetector.MIN_DBFS.toFloat())
                .toDouble(),
            "noiseFloorDbfs" to preferences
                .getFloat(KEY_NOISE_FLOOR_DBFS, AudioEventDetector.MIN_DBFS.toFloat())
                .toDouble(),
            "currentDetectorState" to
                preferences.getString(KEY_DETECTOR_STATE, "").orEmpty(),
            "candidateCount" to preferences.getLong(KEY_CANDIDATE_COUNT, 0L),
            "confirmedEventCount" to
                preferences.getLong(KEY_CONFIRMED_EVENT_COUNT, 0L),
            "writerOpenCount" to preferences.getLong(KEY_WRITER_OPEN_COUNT, 0L),
            "writerSavedCount" to preferences.getLong(KEY_WRITER_SAVED_COUNT, 0L),
            "writerFailedCount" to preferences.getLong(KEY_WRITER_FAILED_COUNT, 0L),
            "lastWriterError" to
                preferences.getString(KEY_LAST_WRITER_ERROR, "").orEmpty(),
            "recordingProfile" to selectedProfile().storageValue,
            "diagnosticProfile" to preferences
                .getString(KEY_DIAGNOSTIC_PROFILE, selectedProfile().storageValue)
                .orEmpty(),
            "weakCandidateCount" to preferences.getLong(KEY_WEAK_CANDIDATE_COUNT, 0L),
            "weakSavedCount" to preferences.getLong(KEY_WEAK_SAVED_COUNT, 0L),
            "weakRejectedCount" to preferences.getLong(KEY_WEAK_REJECTED_COUNT, 0L),
            "lastWeakRejection" to
                preferences.getString(KEY_LAST_WEAK_REJECTION, "").orEmpty(),
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
        const val KEY_FRAGMENT_COUNT = "fragment_count"
        const val KEY_LAST_FRAGMENT_PATH = "last_fragment_path"
        const val KEY_LAST_FRAGMENT_AT = "last_fragment_at"
        const val KEY_LAST_WARNING = "last_warning"
        const val KEY_INTERRUPTED = "interrupted"
        const val KEY_HEARTBEAT_AT = "heartbeat_at"
        const val KEY_SAMPLES_READ = "samples_read"
        const val KEY_READ_ERROR_COUNT = "read_error_count"
        const val KEY_LAST_CHUNK_DBFS = "last_chunk_dbfs"
        const val KEY_NOISE_FLOOR_DBFS = "noise_floor_dbfs"
        const val KEY_DETECTOR_STATE = "detector_state"
        const val KEY_CANDIDATE_COUNT = "candidate_count"
        const val KEY_CONFIRMED_EVENT_COUNT = "confirmed_event_count"
        const val KEY_WRITER_OPEN_COUNT = "writer_open_count"
        const val KEY_WRITER_SAVED_COUNT = "writer_saved_count"
        const val KEY_WRITER_FAILED_COUNT = "writer_failed_count"
        const val KEY_LAST_WRITER_ERROR = "last_writer_error"
        const val KEY_RECORDING_PROFILE = "recording_profile"
        const val KEY_DIAGNOSTIC_PROFILE = "diagnostic_profile"
        const val KEY_WEAK_CANDIDATE_COUNT = "weak_candidate_count"
        const val KEY_WEAK_SAVED_COUNT = "weak_saved_count"
        const val KEY_WEAK_REJECTED_COUNT = "weak_rejected_count"
        const val KEY_LAST_WEAK_REJECTION = "last_weak_rejection"

        /**
         * Если служба не обновляла heartbeat дольше этого времени,
         * считаем, что сессия прервана системой.
         */
        const val HEARTBEAT_TIMEOUT_MS = 15_000L
    }
}
