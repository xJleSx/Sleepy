package com.sleepy.app.sleepy.audio

import android.content.SharedPreferences

/**
 * Регрессия к расхождению «два файла на диске — `fragment_count = 1`»:
 * счётчик фрагментов описывает файлы, которые переживают перезапуск
 * службы, поэтому старт новой сессии не должен его обнулять.
 */
class RecordingStatusStoreTest {

    private lateinit var preferences: InMemoryPreferences
    private lateinit var store: RecordingStatusStore

    @org.junit.Before
    fun setUp() {
        preferences = InMemoryPreferences()
        store = RecordingStatusStore(preferences)
    }

    @org.junit.Test
    fun newSessionKeepsFragmentCountAndContinuesFromIt() {
        store.startSession("session-1", 1_000L, 0)
        store.recordFragmentSaved("/recordings/event_a.wav", 2_000L)
        org.junit.Assert.assertEquals(1, store.fragmentCount())

        store.startSession("session-2", 3_000L, 1)
        org.junit.Assert.assertEquals(1, store.fragmentCount())

        store.recordFragmentSaved("/recordings/event_b.wav", 4_000L)
        org.junit.Assert.assertEquals(2, store.fragmentCount())
    }

    @org.junit.Test
    fun fragmentCountFollowsFilesOnDisk() {
        store.startSession("session-1", 0L, 5)
        org.junit.Assert.assertEquals(5, store.fragmentCount())

        store.startSession("session-2", 0L, 2)
        org.junit.Assert.assertEquals(2, store.fragmentCount())
    }

    @org.junit.Test
    fun unreadableDirectoryKeepsPreviousFragmentCount() {
        store.startSession("session-1", 0L, 5)
        store.startSession("session-2", 0L, -1)

        org.junit.Assert.assertEquals(5, store.fragmentCount())
    }

    @org.junit.Test
    fun eventCountIsCumulativeAcrossSessions() {
        store.startSession("session-1", 0L, 0)
        store.recordEventDetected(1_000L)
        store.recordEventDetected(2_000L)

        store.startSession("session-2", 0L, 0)
        org.junit.Assert.assertEquals(2, store.eventCount())

        store.recordEventDetected(3_000L)
        org.junit.Assert.assertEquals(3, store.eventCount())
    }

    @org.junit.Test
    fun eventAndFragmentCountersAreIndependent() {
        store.startSession("session-1", 0L, 0)

        store.recordEventDetected(1_000L)
        store.recordFragmentSaved("/recordings/event_a.wav", 1_000L)
        store.recordFragmentSaved("/recordings/event_b.wav", 1_000L)

        org.junit.Assert.assertEquals(1, store.eventCount())
        org.junit.Assert.assertEquals(2, store.fragmentCount())
    }

    @org.junit.Test
    fun lastFragmentAndWarningAreVisibleToInterface() {
        store.startSession("session-1", 0L, 0)
        store.recordFragmentSaved("/recordings/event_a.wav", 1_234L)
        store.reportWarning("Тестовое предупреждение")

        val status = store.readStatus()

        org.junit.Assert.assertEquals(
            "/recordings/event_a.wav",
            status["lastFragmentPath"],
        )
        org.junit.Assert.assertEquals(1_234L, status["lastFragmentAtMillis"])
        org.junit.Assert.assertEquals("Тестовое предупреждение", status["lastWarning"])
        org.junit.Assert.assertEquals("", store.error())
    }

    @org.junit.Test
    fun diagnosticsAreExposedAndSurviveStatusReads() {
        val expected = RecordingDiagnostics(
            samplesRead = 12_345L,
            readErrorCount = 2L,
            lastChunkDbfs = -33.5,
            noiseFloorDbfs = -55.25,
            currentDetectorState = "QUIET",
            candidateCount = 3L,
            confirmedEventCount = 1L,
            writerOpenCount = 4L,
            writerSavedCount = 3L,
            writerFailedCount = 1L,
            lastWriterError = "Не удалось создать WAV-фрагмент",
            lastFragmentPath = "/recordings/event_a.wav",
        )

        store.startSession("session-1", 0L, 0)
        store.updateDiagnostics(expected)

        repeat(3) { store.readStatus() }

        val status = store.readStatus()

        org.junit.Assert.assertEquals(12_345L, status["samplesRead"])
        org.junit.Assert.assertEquals(2L, status["readErrorCount"])
        org.junit.Assert.assertEquals(
            -33.5,
            status["lastChunkDbfs"] as Double,
            0.01,
        )
        org.junit.Assert.assertEquals(
            -55.25,
            status["noiseFloorDbfs"] as Double,
            0.01,
        )
        org.junit.Assert.assertEquals("QUIET", status["currentDetectorState"])
        org.junit.Assert.assertEquals(3L, status["candidateCount"])
        org.junit.Assert.assertEquals(1L, status["confirmedEventCount"])
        org.junit.Assert.assertEquals(4L, status["writerOpenCount"])
        org.junit.Assert.assertEquals(3L, status["writerSavedCount"])
        org.junit.Assert.assertEquals(1L, status["writerFailedCount"])
        org.junit.Assert.assertEquals(
            "Не удалось создать WAV-фрагмент",
            status["lastWriterError"],
        )
        org.junit.Assert.assertEquals(
            "/recordings/event_a.wav",
            status["lastFragmentPath"],
        )
    }

    @org.junit.Test
    fun diagnosticsResetAtNewSessionButNotOnStop() {
        store.startSession("session-1", 0L, 0)
        store.updateDiagnostics(
            RecordingDiagnostics(
                samplesRead = 100L,
                readErrorCount = 1L,
                lastChunkDbfs = -20.0,
                noiseFloorDbfs = -60.0,
                currentDetectorState = "ACTIVE",
                candidateCount = 1L,
                confirmedEventCount = 1L,
                writerOpenCount = 1L,
                writerSavedCount = 1L,
                writerFailedCount = 0L,
                lastWriterError = "",
                lastFragmentPath = "/recordings/event_a.wav",
            ),
        )

        store.markStopped()
        org.junit.Assert.assertEquals(100L, store.readStatus()["samplesRead"])

        store.startSession("session-2", 1_000L, 1)
        val status = store.readStatus()

        org.junit.Assert.assertEquals(0L, status["samplesRead"])
        org.junit.Assert.assertEquals(0L, status["readErrorCount"])
        org.junit.Assert.assertEquals(-120.0, status["lastChunkDbfs"])
        org.junit.Assert.assertEquals(-120.0, status["noiseFloorDbfs"])
        org.junit.Assert.assertEquals("", status["currentDetectorState"])
        org.junit.Assert.assertEquals(0L, status["candidateCount"])
        org.junit.Assert.assertEquals(0L, status["confirmedEventCount"])
        org.junit.Assert.assertEquals(0L, status["writerOpenCount"])
        org.junit.Assert.assertEquals(0L, status["writerSavedCount"])
        org.junit.Assert.assertEquals(0L, status["writerFailedCount"])
        org.junit.Assert.assertEquals("", status["lastWriterError"])
    }

    @org.junit.Test
    fun warningDoesNotLookLikeFatalError() {
        store.startSession("session-1", System.currentTimeMillis(), 0)
        store.reportWarning("Не удалось сохранить фрагмент")
        store.touchHeartbeat()

        val status = store.readStatus()

        org.junit.Assert.assertEquals(true, status["isRecording"])
        org.junit.Assert.assertEquals("", status["error"])
        org.junit.Assert.assertEquals(
            "Не удалось сохранить фрагмент",
            status["lastWarning"],
        )
        org.junit.Assert.assertEquals(false, status["interrupted"])
    }

    @org.junit.Test
    fun fatalErrorIsSeparateFromLastWarning() {
        store.startSession("session-1", System.currentTimeMillis(), 0)
        store.reportWarning("Фрагмент пропущен")
        store.saveFatalError("Микрофон недоступен")

        val status = store.readStatus()

        org.junit.Assert.assertEquals("Микрофон недоступен", status["error"])
        org.junit.Assert.assertEquals("Фрагмент пропущен", status["lastWarning"])
        org.junit.Assert.assertEquals(false, status["isRecording"])
    }

    @org.junit.Test
    fun stopAndInterruptionUpdateFlags() {
        val now = System.currentTimeMillis()

        store.startSession("session-1", now, 0)
        org.junit.Assert.assertEquals(true, store.readStatus()["isRecording"])

        store.markStopped()
        org.junit.Assert.assertEquals(false, store.readStatus()["isRecording"])
        org.junit.Assert.assertEquals(false, store.readStatus()["interrupted"])

        store.startSession("session-2", now, 0)
        store.markInterrupted("Сбой микрофона")

        org.junit.Assert.assertEquals(false, store.readStatus()["isRecording"])
        org.junit.Assert.assertEquals(true, store.readStatus()["interrupted"])
        org.junit.Assert.assertEquals("Сбой микрофона", store.error())
    }

    private class InMemoryPreferences : SharedPreferences {

        private val values = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = synchronized(values) {
            HashMap(values)
        }

        override fun getString(key: String, defValue: String?): String? =
            synchronized(values) { values[key] as String? ?: defValue }

        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = synchronized(values) {
            @Suppress("UNCHECKED_CAST")
            (values[key] as MutableSet<String>?) ?: defValues
        }

        override fun getInt(key: String, defValue: Int): Int =
            synchronized(values) { values[key] as Int? ?: defValue }

        override fun getLong(key: String, defValue: Long): Long =
            synchronized(values) { values[key] as Long? ?: defValue }

        override fun getFloat(key: String, defValue: Float): Float =
            synchronized(values) { values[key] as Float? ?: defValue }

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            synchronized(values) { values[key] as Boolean? ?: defValue }

        override fun contains(key: String): Boolean = synchronized(values) {
            values.containsKey(key)
        }

        override fun edit(): SharedPreferences.Editor = Editor(values)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private class Editor(
            private val values: MutableMap<String, Any?>,
        ) : SharedPreferences.Editor {

            private val changes = HashMap<String, Any?>()
            private val removals = HashSet<String>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                apply { changes[key] = value }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = apply { changes[key] = values }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor =
                apply { changes[key] = value }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                apply { changes[key] = value }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
                apply { changes[key] = value }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                apply { changes[key] = value }

            override fun remove(key: String): SharedPreferences.Editor =
                apply { removals += key }

            override fun clear(): SharedPreferences.Editor = apply { clear = true }

            override fun commit(): Boolean {
                synchronized(values) {
                    if (clear) {
                        values.clear()
                    }
                    removals.forEach { values.remove(it) }
                    values.putAll(changes)
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
