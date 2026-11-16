package com.sleepy.app.sleepy.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioCaptureSessionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val config = RecordingConfig.DEFAULT

    private lateinit var directory: File
    private lateinit var status: FakeStatusSink

    private var nowMillis = 1_700_000_000_000L

    private val quietSamples = 10L * config.sampleRate
    private val loudSamples = 1L * config.sampleRate
    private val postRollQuietSamples = 9L * config.sampleRate
    private val totalSamples = quietSamples + loudSamples + postRollQuietSamples

    @Before
    fun setUp() {
        directory = temporaryFolder.newFolder("fragments")
        status = FakeStatusSink()
        nowMillis = 1_700_000_000_000L
    }

    @Test
    fun recorderIsConnectedToAudioStreamAndSavesFragment() {
        val source = newSource()
        val session = newSession(sourceFactory = { source })

        assertTrue(session.start())
        assertTrue(
            "Источник должен отдать все сэмплы",
            source.exhausted.await(10, TimeUnit.SECONDS),
        )

        session.stop()
        assertTrue(session.awaitStopped(10_000))

        assertEquals(1, status.startSessions.size)
        assertTrue(status.startSessions.single().first.isNotBlank())
        assertEquals(nowMillis, status.startSessions.single().second)
        assertEquals(1, status.stopped.get())
        assertTrue(status.interruptions.isEmpty())
        assertTrue(status.errors.isEmpty())
        assertEquals(1, status.events.size)
        assertEquals(1, status.fragments.size)
        assertTrue(status.heartbeats.get() >= 1)

        val diagnostics = status.diagnosticUpdates.last()
        assertEquals(totalSamples, diagnostics.samplesRead)
        assertEquals(0L, diagnostics.readErrorCount)
        assertEquals(1L, diagnostics.candidateCount)
        assertEquals(1L, diagnostics.confirmedEventCount)
        assertEquals(1L, diagnostics.writerOpenCount)
        assertEquals(1L, diagnostics.writerSavedCount)
        assertEquals(0L, diagnostics.writerFailedCount)
        assertEquals("", diagnostics.lastWriterError)
        assertEquals("QUIET", diagnostics.currentDetectorState)
        assertTrue(diagnostics.lastFragmentPath.endsWith(".wav"))
        assertTrue(diagnostics.lastFragmentPath.contains("event_"))
        assertTrue(diagnostics.lastChunkDbfs <= 0.0)
        assertTrue(diagnostics.noiseFloorDbfs <= 0.0)
        assertTrue(
            "Диагностика должна обновляться пакетно, а не на каждый чанк: " +
                status.diagnosticUpdates.size,
            status.diagnosticUpdates.size in 2..12,
        )

        val files = wavFiles()
        assertEquals(1, files.size)

        val fragment = files.single()
        assertTrue(fragment.name.startsWith("event_"))
        // pre-roll 8 с + событие 1 с + post-roll 8 с = 17 с.
        assertEquals(44L + 17L * config.sampleRate * config.bytesPerSample, fragment.length())

        assertFalse(session.isRunning)
        assertEquals(1, source.releaseCalls)
        assertTrue(source.stopCalls >= 1)
    }

    @Test
    fun quietStreamDoesNotProduceRecordings() {
        val source = FakeAudioSource(
            totalSamples = 20L * config.sampleRate,
        )
        val session = newSession(sourceFactory = { source })

        assertTrue(session.start())
        assertTrue(source.exhausted.await(10, TimeUnit.SECONDS))
        session.stop()
        assertTrue(session.awaitStopped(10_000))

        assertTrue(status.events.isEmpty())
        assertTrue(status.fragments.isEmpty())
        assertTrue(wavFiles().isEmpty())
        assertEquals(1, status.stopped.get())
    }

    @Test
    fun startAndStopUpdateSessionStatus() {
        val source = FakeAudioSource(
            totalSamples = 0L,
        )
        val session = newSession(sourceFactory = { source })

        assertTrue(session.start())
        assertTrue(session.isRunning)
        assertEquals(1, source.startCalls)
        assertEquals(1, status.startSessions.size)

        session.stop()
        assertTrue(session.awaitStopped(10_000))

        assertFalse(session.isRunning)
        assertEquals(1, status.stopped.get())
        assertTrue(status.interruptions.isEmpty())
        assertEquals(1, source.releaseCalls)
        assertTrue(source.stopCalls >= 1)
    }

    @Test
    fun secondStartWhileRunningIsRejected() {
        val source = FakeAudioSource(
            totalSamples = 0L,
        )
        val session = newSession(sourceFactory = { source })

        assertTrue(session.start())
        assertFalse(session.start())
        assertEquals(1, source.startCalls)
        assertEquals(1, status.startSessions.size)

        session.stop()
        assertTrue(session.awaitStopped(10_000))
    }

    @Test
    fun sessionCanBeRestartedAfterStop() {
        val sources = CopyOnWriteArrayList<FakeAudioSource>()

        val session = newSession(
            sourceFactory = {
                FakeAudioSource(
                    totalSamples = 0L,
                ).also { sources += it }
            },
        )

        assertTrue(session.start())
        session.stop()
        assertTrue(session.awaitStopped(10_000))

        assertTrue(session.start())
        session.stop()
        assertTrue(session.awaitStopped(10_000))

        assertEquals(2, sources.size)
        assertEquals(2, status.startSessions.size)
        assertEquals(2, status.stopped.get())
        // У каждого запуска свой идентификатор сессии.
        assertEquals(2, status.startSessions.map { it.first }.toSet().size)
        sources.forEach { assertEquals(1, it.releaseCalls) }
    }

    @Test
    fun sessionCanBeRestartedAfterReadError() {
        val sources = listOf(
            FakeAudioSource(totalSamples = 0L, readFailure = -3),
            FakeAudioSource(totalSamples = 0L),
        )
        var index = 0

        val session = newSession(sourceFactory = { sources[index++] })

        assertTrue(session.start())
        assertTrue(session.awaitStopped(5_000))
        assertFalse(session.isRunning)
        assertEquals(1, status.interruptions.size)

        assertTrue(session.start())
        session.stop()
        assertTrue(session.awaitStopped(10_000))

        assertEquals(2, status.startSessions.size)
        assertFalse(session.isRunning)
    }

    @Test
    fun startSessionReportsOnlyValidFragmentsAlreadyOnDisk() {
        writeValidWav(directory, "event_20260101_000000_000_001.wav")
        writeValidWav(directory, "event_20260101_000000_000_002.wav")
        // Только заголовок: реальных PCM-данных нет.
        File(directory, "event_20260101_000000_000_003.wav").writeBytes(ByteArray(44))
        File(directory, "notes.txt").writeText("не фрагмент")

        val source = FakeAudioSource(totalSamples = 0L)
        val session = newSession(sourceFactory = { source })

        assertTrue(session.start())
        session.stop()
        assertTrue(session.awaitStopped(10_000))

        assertEquals(2, status.startSessions.single().third)
    }

    @Test
    fun fragmentCountMatchesFilesAcrossSessions() {
        val sources = CopyOnWriteArrayList<FakeAudioSource>()
        val session = newSession(
            sourceFactory = {
                newSource().also { sources += it }
            },
        )

        assertTrue(session.start())
        assertTrue(sources[0].exhausted.await(10, TimeUnit.SECONDS))
        session.stop()
        assertTrue(session.awaitStopped(10_000))

        assertEquals(1, status.fragments.size)
        assertEquals(1, status.fragmentCount)

        // Регрессия: файл прошлой сессии остаётся на диске, и новая сессия
        // не должна начинать счётчик фрагментов с нуля.
        assertTrue(session.start())
        assertTrue(sources[1].exhausted.await(10, TimeUnit.SECONDS))
        session.stop()
        assertTrue(session.awaitStopped(10_000))

        assertEquals(2, status.startSessions.size)
        assertEquals(2, wavFiles().size)
        assertEquals(wavFiles().size, status.fragmentCount)
    }

    @Test
    fun truncatedSplitIsOneEventAndTwoFragments() {
        val source = FakeAudioSource(
            totalSamples = quietSamples + 61L * config.sampleRate + postRollQuietSamples,
            loudFromSample = quietSamples,
            loudToSample = quietSamples + 61L * config.sampleRate,
        )
        val session = newSession(
            sourceFactory = { source },
            recorderFactory = { listener ->
                AudioFragmentRecorder(
                    config = config,
                    directory = directory,
                    clock = { nowMillis },
                    listener = listener,
                    // 61 с звука + 8 с pre-roll + 8 с post-roll: тест
                    // подаёт данные быстрее реального времени, пул должен
                    // вмещать весь фрагмент.
                    writerQueueBlocks = 1_024,
                )
            },
        )

        assertTrue(session.start())
        assertTrue(source.exhausted.await(10, TimeUnit.SECONDS))
        session.stop()
        assertTrue(session.awaitStopped(10_000))

        assertEquals(1, status.events.size)
        assertEquals(
            "files=${wavFiles().map { it.name }} fragments=${status.fragments} " +
                "warnings=${status.warnings}",
            2,
            status.fragments.size,
        )
        assertEquals(2, wavFiles().size)
        assertEquals(2, status.fragmentCount)
    }

    @Test
    fun finishCallbackReceivesTheFinishedSession() {
        val finished = CopyOnWriteArrayList<AudioCaptureSession>()

        val session = newSession(
            sourceFactory = {
                FakeAudioSource(
                    totalSamples = 0L,
                )
            },
            onSessionFinished = { finished += it },
        )

        assertTrue(session.start())
        session.stop()
        assertTrue(session.awaitStopped(10_000))

        assertEquals(1, finished.size)
        assertSame(session, finished.single())
    }

    @Test
    fun sourceFactoryFailureIsReportedAsFatalError() {
        val session = newSession(
            sourceFactory = { throw IllegalStateException("Нет микрофона") },
        )

        assertFalse(session.start())
        assertFalse(session.isRunning)

        assertEquals("Нет микрофона", status.errors.single())
        assertTrue(status.startSessions.isEmpty())
        assertTrue(status.interruptions.isEmpty())
    }

    @Test
    fun sourceStartFailureReleasesAudioSource() {
        val source = FakeAudioSource(
            totalSamples = 0L,
            startFailure = true,
        )
        val session = newSession(sourceFactory = { source })

        assertFalse(session.start())
        assertFalse(session.isRunning)

        assertEquals(1, source.stopCalls)
        assertEquals(1, source.releaseCalls)
        assertTrue(status.errors.single().contains("запуска"))
        assertTrue(status.startSessions.isEmpty())
    }

    @Test
    fun recorderFactoryFailureReleasesAudioSource() {
        val source = FakeAudioSource(
            totalSamples = 0L,
        )
        val session = newSession(
            sourceFactory = { source },
            recorderFactory = { throw IllegalStateException("Нет места") },
        )

        assertFalse(session.start())
        assertFalse(session.isRunning)

        assertEquals(1, source.releaseCalls)
        assertTrue(status.errors.single().contains("Нет места"))
    }

    @Test
    fun audioReadErrorInterruptsSessionAndReleasesResources() {
        val source = FakeAudioSource(
            totalSamples = 0L,
            readFailure = -3,
        )
        val session = newSession(sourceFactory = { source })

        assertTrue(session.start())
        assertTrue(session.awaitStopped(5_000))

        assertFalse(session.isRunning)
        assertTrue(status.interruptions.single().contains("Ошибка чтения"))
        assertEquals(0, status.stopped.get())
        assertEquals(1, source.releaseCalls)

        val diagnostics = status.diagnosticUpdates.last()
        assertEquals(0L, diagnostics.samplesRead)
        assertEquals(1L, diagnostics.readErrorCount)
        assertEquals(0L, diagnostics.writerOpenCount)
    }

    @Test
    fun writerErrorIsReportedAsWarningAndSessionContinues() {
        val source = newSource()
        val session = newSession(
            sourceFactory = { source },
            recorderFactory = { listener ->
                AudioFragmentRecorder(
                    config = config,
                    directory = directory,
                    clock = { nowMillis },
                    listener = listener,
                    openFile = { file -> FailingRandomAccessFile(file) },
                )
            },
        )

        assertTrue(session.start())
        assertTrue(source.exhausted.await(10, TimeUnit.SECONDS))
        session.stop()
        assertTrue(session.awaitStopped(10_000))

        assertTrue(status.warnings.any { it.contains("запис", ignoreCase = true) })
        assertTrue(status.fragments.isEmpty())
        assertTrue(wavFiles().isEmpty())
        assertEquals(1, status.events.size)
        assertEquals(1, status.stopped.get())

        val diagnostics = status.diagnosticUpdates.last()
        assertEquals(1L, diagnostics.writerOpenCount)
        assertEquals(0L, diagnostics.writerSavedCount)
        assertEquals(1L, diagnostics.writerFailedCount)
        assertTrue(diagnostics.lastWriterError.contains("запис"))
        assertEquals("", diagnostics.lastFragmentPath)
    }

    @Test
    fun writerQueueOverflowIsReportedAndFragmentDropped() {
        val source = newSource()
        val session = newSession(
            sourceFactory = { source },
            recorderFactory = { listener ->
                AudioFragmentRecorder(
                    config = config,
                    directory = directory,
                    clock = { nowMillis },
                    listener = listener,
                    openFile = { file -> SlowRandomAccessFile(file, delayMs = 250) },
                    writerQueueBlocks = 1,
                )
            },
        )

        assertTrue(session.start())
        assertTrue(source.exhausted.await(10, TimeUnit.SECONDS))
        session.stop()
        assertTrue(session.awaitStopped(10_000))

        assertTrue(
            status.warnings.any {
                it.contains("буфер", ignoreCase = true) ||
                    it.contains("переполн", ignoreCase = true)
            },
        )
        assertTrue(status.fragments.isEmpty())
        assertTrue(wavFiles().isEmpty())
    }

    @Test
    fun secondSessionObjectIsRejectedWhileFirstIsActive() {
        val firstSource = BlockingAudioSource()
        val first = newSession(sourceFactory = { firstSource })

        assertTrue(first.start())

        val second = newSession(
            sourceFactory = { FakeAudioSource(totalSamples = 0L) },
        )

        assertFalse(second.start())
        assertTrue(status.warnings.any { it.contains("Предыдущая") })
        assertEquals(1, status.startSessions.size)

        first.stop()
        assertTrue(first.awaitStopped(10_000))

        val third = newSession(
            sourceFactory = { FakeAudioSource(totalSamples = 0L) },
        )

        assertTrue(third.start())
        third.stop()
        assertTrue(third.awaitStopped(10_000))
        assertEquals(2, status.startSessions.size)
    }

    private fun newSource(): FakeAudioSource = FakeAudioSource(
        totalSamples = totalSamples,
        loudFromSample = quietSamples,
        loudToSample = quietSamples + loudSamples,
    )

    private var sessionSequence = 0

    private fun newSession(
        sourceFactory: () -> AudioSource,
        recorderFactory: ((AudioFragmentRecorder.Listener) -> AudioFragmentRecorder)? = null,
        onSessionFinished: ((AudioCaptureSession) -> Unit)? = null,
    ): AudioCaptureSession {
        sessionSequence += 1
        var sessionIds = 0

        val factory = recorderFactory ?: { listener ->
            AudioFragmentRecorder(
                config = config,
                directory = directory,
                clock = { nowMillis },
                listener = listener,
            )
        }

        return AudioCaptureSession(
            config = config,
            directory = directory,
            status = status,
            sourceFactory = sourceFactory,
            recorderFactory = factory,
            clock = { nowMillis },
            sessionIdFactory = { "session-$sessionSequence-${++sessionIds}" },
            onSessionFinished = onSessionFinished,
        )
    }

    private fun wavFiles(): List<File> =
        directory.listFiles { file -> file.extension == "wav" }?.toList().orEmpty()

    private fun writeValidWav(
        directory: File,
        name: String,
        dataBytes: Int = 32_000,
    ) {
        val header = ByteArray(44)

        AudioFragmentWriter.putAscii(header, 0, "RIFF")
        AudioFragmentWriter.putIntLe(header, 4, 36 + dataBytes)
        AudioFragmentWriter.putAscii(header, 8, "WAVE")
        AudioFragmentWriter.putAscii(header, 12, "fmt ")
        AudioFragmentWriter.putIntLe(header, 16, 16)
        AudioFragmentWriter.putShortLe(header, 20, 1)
        AudioFragmentWriter.putShortLe(header, 22, 1)
        AudioFragmentWriter.putIntLe(header, 24, 16_000)
        AudioFragmentWriter.putIntLe(header, 28, 32_000)
        AudioFragmentWriter.putShortLe(header, 32, 2)
        AudioFragmentWriter.putShortLe(header, 34, 16)
        AudioFragmentWriter.putAscii(header, 36, "data")
        AudioFragmentWriter.putIntLe(header, 40, dataBytes)

        File(directory, name).writeBytes(header + ByteArray(dataBytes))
    }

    private class FakeStatusSink : RecordingStatusSink {

        val startSessions = CopyOnWriteArrayList<Triple<String, Long, Int>>()
        val events = CopyOnWriteArrayList<Long>()
        val fragments = CopyOnWriteArrayList<String>()
        val warnings = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        val interruptions = CopyOnWriteArrayList<String>()
        val stopped = AtomicInteger()
        val heartbeats = AtomicInteger()
        val diagnosticUpdates = CopyOnWriteArrayList<RecordingDiagnostics>()

        /**
         * Повторяет политику [RecordingStatusStore]: счётчик фрагментов
         * сверяется с диском при старте и растёт при сохранении.
         */
        @Volatile
        var fragmentCount = 0

        @Volatile
        var recording = false

        override fun startSession(
            sessionId: String,
            startedAtMillis: Long,
            savedFragmentsOnDisk: Int,
        ) {
            startSessions += Triple(sessionId, startedAtMillis, savedFragmentsOnDisk)
            recording = true
            if (savedFragmentsOnDisk >= 0) {
                fragmentCount = savedFragmentsOnDisk
            }
        }

        override fun markStopped() {
            stopped.incrementAndGet()
            recording = false
        }

        override fun markInterrupted(reason: String) {
            interruptions += reason
            recording = false
        }

        override fun saveFatalError(message: String) {
            errors += message
            recording = false
        }

        override fun recordEventDetected(eventAtMillis: Long) {
            events += eventAtMillis
        }

        override fun recordFragmentSaved(filePath: String, fragmentAtMillis: Long) {
            synchronized(this) {
                fragments += filePath
                fragmentCount += 1
            }
        }

        override fun reportWarning(message: String) {
            warnings += message
        }

        override fun touchHeartbeat() {
            heartbeats.incrementAndGet()
        }

        override fun updateDiagnostics(diagnostics: RecordingDiagnostics) {
            diagnosticUpdates += diagnostics
        }

        override fun isRecording(): Boolean = recording
    }

    private class FakeAudioSource(
        private val totalSamples: Long,
        private val loudFromSample: Long = Long.MAX_VALUE,
        private val loudToSample: Long = 0L,
        private val readFailure: Int? = null,
        private val startFailure: Boolean = false,
    ) : AudioSource {

        private val lock = Object()

        private var stopped = false

        @Volatile
        var startCalls = 0

        @Volatile
        var stopCalls = 0

        @Volatile
        var releaseCalls = 0

        @Volatile
        var emittedSamples = 0L

        val exhausted = CountDownLatch(1)

        override fun start() {
            startCalls += 1
            if (startFailure) {
                throw IllegalStateException("Смоделированная ошибка запуска")
            }
        }

        override fun read(destination: ShortArray): Int {
            readFailure?.let { return it }

            synchronized(lock) {
                if (stopped) return 0

                val remaining = totalSamples - emittedSamples
                if (remaining <= 0L) {
                    exhausted.countDown()
                    lock.wait(5)
                    return 0
                }

                val count = minOf(destination.size.toLong(), remaining).toInt()
                var index = 0
                while (index < count) {
                    val absolute = emittedSamples + index
                    destination[index] = if (
                        absolute >= loudFromSample && absolute < loudToSample
                    ) {
                        (((absolute % 13) - 6) * 300).toShort()
                    } else {
                        ((absolute % 7) - 3).toShort()
                    }
                    index += 1
                }

                emittedSamples += count
                if (emittedSamples >= totalSamples) {
                    exhausted.countDown()
                }

                return count
            }
        }

        override fun stop() {
            stopCalls += 1
            synchronized(lock) {
                stopped = true
                lock.notifyAll()
            }
        }

        override fun release() {
            releaseCalls += 1
        }
    }

    private class BlockingAudioSource : AudioSource {

        private val lock = Object()

        @Volatile
        private var stopped = false

        override fun start() = Unit

        override fun read(destination: ShortArray): Int {
            synchronized(lock) {
                while (!stopped) {
                    lock.wait(50)
                }
            }
            return 0
        }

        override fun stop() {
            synchronized(lock) {
                stopped = true
                lock.notifyAll()
            }
        }

        override fun release() = Unit
    }

    private class FailingRandomAccessFile(file: File) : RandomAccessFile(file, "rw") {
        override fun write(b: ByteArray, off: Int, len: Int) {
            throw IOException("Смоделированная ошибка записи")
        }
    }

    private class SlowRandomAccessFile(
        file: File,
        private val delayMs: Long,
    ) : RandomAccessFile(file, "rw") {
        override fun write(b: ByteArray, off: Int, len: Int) {
            Thread.sleep(delayMs)
            super.write(b, off, len)
        }
    }
}
