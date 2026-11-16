package com.sleepy.app.sleepy.audio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordingSessionControllerTest {

    private lateinit var status: FakeStatusSink
    private lateinit var controller: RecordingSessionController<FakeSession>

    private val sessions = CopyOnWriteArrayList<FakeSession>()
    private var createCalls = 0
    private var foregroundStarts = 0
    private var foregroundStops = 0
    private var wakeAcquires = 0
    private var wakeReleases = 0
    private var stopServiceCalls = 0
    private var watchdogCancels = 0
    private var failForeground = false
    private var nextStartResult = true

    @Before
    fun setUp() {
        status = FakeStatusSink()
        createCalls = 0
        foregroundStarts = 0
        foregroundStops = 0
        wakeAcquires = 0
        wakeReleases = 0
        stopServiceCalls = 0
        watchdogCancels = 0
        failForeground = false
        nextStartResult = true

        controller = RecordingSessionController(
            status = status,
            createSession = { onFinished ->
                createCalls += 1
                FakeSession(nextStartResult).also {
                    it.onFinished = onFinished
                    sessions += it
                }
            },
            startForeground = {
                if (failForeground) throw IllegalStateException("Нет foreground")
                foregroundStarts += 1
            },
            stopForeground = { foregroundStops += 1 },
            acquireWakeLock = { wakeAcquires += 1 },
            releaseWakeLock = { wakeReleases += 1 },
            requestStopService = { stopServiceCalls += 1 },
            cancelWatchdog = { watchdogCancels += 1 },
        )
    }

    @Test
    fun repeatedStartWhileRunningDoesNotCreateSecondSession() {
        controller.start()
        controller.start()

        assertEquals(1, createCalls)
        assertEquals(1, foregroundStarts)
        assertEquals(1, sessions.single().startCalls)
        assertTrue(controller.isRunning)
    }

    @Test
    fun stopActiveSessionDoesNotBlockAndRequestsWatchdog() {
        controller.start()

        assertTrue(controller.stop())

        assertEquals(1, sessions.single().stopCalls)
        assertEquals(0, stopServiceCalls)
        assertEquals(0, foregroundStops)
        assertTrue(controller.isRunning)
    }

    @Test
    fun watchdogWhileThreadIsAliveKeepsResourcesAndReschedules() {
        controller.start()
        controller.stop()

        val session = sessions.single()
        session.active = true

        assertTrue(controller.onWatchdog())
        assertEquals(0, wakeReleases)
        assertEquals(0, foregroundStops)
        assertEquals(0, stopServiceCalls)
        assertTrue(status.warnings.single().contains("затягивается"))

        session.active = false
        session.finish()

        assertEquals(1, wakeReleases)
        assertEquals(1, foregroundStops)
        assertEquals(1, stopServiceCalls)
        assertFalse(controller.isRunning)
    }

    @Test
    fun watchdogAfterLimitKeepsResourcesBlockedUntilThreadStops() {
        controller.start()
        controller.stop()

        val session = sessions.single()
        session.active = true

        assertTrue(controller.onWatchdog())
        assertTrue(controller.onWatchdog())
        assertFalse(controller.onWatchdog())

        assertEquals(1, status.interruptions.size)
        assertTrue(status.interruptions.single().contains("не завершился"))
        assertEquals(0, wakeReleases)
        assertEquals(1, foregroundStops)
        assertEquals(1, stopServiceCalls)

        // Пока поток жив, второй запуск недопустим.
        controller.start()
        assertEquals(1, createCalls)
        assertTrue(controller.isRunning)

        session.active = false
        session.finish()

        assertEquals(1, wakeReleases)
        assertFalse(controller.isRunning)
    }

    @Test
    fun lateCallbackFromOldSessionDoesNotTouchNewSession() {
        controller.start()
        val first = sessions.single()
        first.active = false
        controller.onSessionFinished(first)

        controller.start()
        val second = sessions[1]
        assertTrue(controller.isRunning)
        assertEquals(2, createCalls)

        controller.onSessionFinished(first)

        assertTrue(controller.isRunning)
        assertEquals(1, wakeReleases)
        assertEquals(1, foregroundStops)
        assertEquals(1, stopServiceCalls)

        second.finish()
        assertEquals(2, wakeReleases)
        assertFalse(controller.isRunning)
    }

    @Test
    fun sessionCanBeRestartedAfterNormalStop() {
        controller.start()
        val first = sessions.single()
        first.active = false
        controller.onSessionFinished(first)

        nextStartResult = true
        controller.start()

        assertEquals(2, createCalls)
        assertEquals(1, sessions[1].startCalls)
        assertTrue(controller.isRunning)
    }

    @Test
    fun failedStartDoesNotBlockNextAttempt() {
        nextStartResult = false
        controller.start()

        assertEquals(1, createCalls)
        assertEquals(1, foregroundStops)
        assertEquals(1, stopServiceCalls)
        assertFalse(controller.isRunning)

        nextStartResult = true
        controller.start()

        assertEquals(2, createCalls)
        assertTrue(controller.isRunning)
    }

    @Test
    fun foregroundFailureIsReportedAsFatal() {
        failForeground = true

        controller.start()

        assertEquals(0, createCalls)
        assertEquals(1, stopServiceCalls)
        assertTrue(status.fatalErrors.single().contains("foreground"))
        assertFalse(controller.isRunning)
    }

    @Test
    fun stopWithoutRunningSessionMarksStoppedState() {
        status.recording = true

        assertFalse(controller.stop())

        assertEquals(1, status.stoppedCalls.get())
        assertEquals(1, foregroundStops)
        assertEquals(1, stopServiceCalls)
    }

    @Test
    fun destroyStopsSessionAndReleasesResources() {
        controller.start()

        controller.onDestroy()

        assertEquals(1, sessions.single().stopCalls)
        assertEquals(1, wakeReleases)
        assertEquals(0, stopServiceCalls)
        assertFalse(controller.isRunning)
    }

    private class FakeSession(
        private var startResult: Boolean,
    ) : RecordingSessionHandle {

        var onFinished: ((FakeSession) -> Unit)? = null
        var active = false
        var startCalls = 0
        var stopCalls = 0

        override val isActive: Boolean
            get() = active

        override fun start(): Boolean {
            startCalls += 1
            if (startResult) active = true
            return startResult
        }

        override fun stop() {
            stopCalls += 1
        }

        fun finish() {
            onFinished?.invoke(this)
        }
    }

    private class FakeStatusSink : RecordingStatusSink {

        val startSessions = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<Long>()
        val fragments = CopyOnWriteArrayList<String>()
        val warnings = CopyOnWriteArrayList<String>()
        val fatalErrors = CopyOnWriteArrayList<String>()
        val interruptions = CopyOnWriteArrayList<String>()
        val stoppedCalls = AtomicInteger()

        @Volatile
        var recording = false

        override fun isRecording(): Boolean = recording

        override fun startSession(
            sessionId: String,
            startedAtMillis: Long,
            savedFragmentsOnDisk: Int,
        ) {
            startSessions += sessionId
            recording = true
        }

        override fun markStopped() {
            stoppedCalls.incrementAndGet()
            recording = false
        }

        override fun markInterrupted(reason: String) {
            interruptions += reason
            recording = false
        }

        override fun saveFatalError(message: String) {
            fatalErrors += message
            recording = false
        }

        override fun recordEventDetected(eventAtMillis: Long) {
            events += eventAtMillis
        }

        override fun recordFragmentSaved(filePath: String, fragmentAtMillis: Long) {
            fragments += filePath
        }

        override fun reportWarning(message: String) {
            warnings += message
        }

        override fun touchHeartbeat() = Unit
    }
}
