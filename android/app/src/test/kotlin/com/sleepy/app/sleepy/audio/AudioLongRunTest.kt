package com.sleepy.app.sleepy.audio

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioLongRunTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val config = RecordingConfig.DEFAULT
    private val chunkSamples = config.chunkSamples
    private val chunkMillis = config.chunkMs.toLong()

    @Test
    fun ringBufferKeepsSequenceAcrossFourHoursOfWrapping() {
        val capacity = config.ringCapacitySamples
        val buffer = AudioRingBuffer(capacity)
        val totalSamples = 4L * 60L * 60L * config.sampleRate
        val writeChunk = ShortArray(4_096)
        var written = 0L

        while (written < totalSamples) {
            val count = minOf(writeChunk.size.toLong(), totalSamples - written).toInt()
            var index = 0
            while (index < count) {
                writeChunk[index] = ringSampleAt(written + index)
                index += 1
            }

            buffer.write(writeChunk, 0, count)
            written += count

            if (written % (60L * 60L * config.sampleRate) == 0L) {
                assertEquals(written, buffer.totalSamplesWritten)
                assertEquals(capacity, buffer.availableSamples())
                assertEquals(written - capacity, buffer.oldestAvailableSample)
                assertRingRange(buffer, written - 10_000L, 1_000)
            }
        }

        assertEquals(totalSamples, buffer.totalSamplesWritten)
        assertEquals(totalSamples - capacity, buffer.oldestAvailableSample)
        assertRingRange(buffer, totalSamples - capacity, capacity)
    }

    @Test
    fun detectorProcessesFourHoursWithoutCounterDrift() {
        var nowMillis = 0L
        val listener = DetectorListener()
        val detector = AudioEventDetector(
            config = config,
            listener = listener,
            clock = { nowMillis },
        )
        val quiet = chunkFor(loud = false)
        val loud = chunkFor(loud = true)
        val chunksPerMinute = 60_000 / chunkMillis
        val initialChunks = 1_000 / chunkMillis

        repeat(initialChunks.toInt()) {
            detector.process(quiet)
            nowMillis += chunkMillis
        }

        repeat(240) { index ->
            val expectedStartSample = (
                initialChunks + index * chunksPerMinute
                ) * chunkSamples
            val expectedStartMillis = 1_000L + index * 60_000L

            repeat(50) {
                detector.process(loud)
                nowMillis += chunkMillis
            }
            repeat((chunksPerMinute - 50).toInt()) {
                detector.process(quiet)
                nowMillis += chunkMillis
            }

            assertEquals(index + 1, listener.startedAtMillis.size)
            assertEquals(index + 1, listener.finished.size)
            assertEquals((index + 1).toLong(), detector.confirmedEventCountTotal)

            val event = listener.finished[index]
            assertFalse(event.truncated)
            assertEquals(1_000L, event.durationMs)
            assertEquals(expectedStartMillis, event.startedAtMillis)
            assertEquals(expectedStartMillis + 1_000L, event.endedAtMillis)
            assertEquals(expectedStartSample, event.startSample)
            assertEquals(expectedStartSample + config.sampleRate, event.endSample)
        }

        assertEquals(240L, detector.candidateCountTotal)
        assertEquals(240L, detector.confirmedEventCountTotal)
        assertFalse(detector.isEventActive)
        assertEquals(AudioEventDetector.DetectorState.QUIET, detector.currentState)
    }

    @Test
    fun recorderSavesEveryEventAcrossFourHoursAndAllWavsValidate() {
        val directory = temporaryFolder.newFolder("long-run")
        val fragments = CopyOnWriteArrayList<SavedAudioFragment>()
        val events = CopyOnWriteArrayList<DetectedAudioEvent>()
        val warnings = CopyOnWriteArrayList<String>()
        var nowMillis = 1_700_000_000_000L

        val recorder = AudioFragmentRecorder(
            config = config,
            directory = directory,
            clock = { nowMillis },
            listener = object : AudioFragmentRecorder.Listener {
                override fun onFragmentSaved(fragment: SavedAudioFragment) {
                    fragments += fragment
                }

                override fun onEventDetected(event: DetectedAudioEvent) {
                    events += event
                }

                override fun onWarning(message: String) {
                    warnings += message
                }
            },
            writerQueueBlocks = 1_024,
        )

        val quiet = chunkFor(loud = false)
        val loud = chunkFor(loud = true)
        val chunksPerMinute = 60_000 / chunkMillis
        var minute = 0

        while (minute < 240) {
            if (minute % 10 == 5) {
                repeat((2_000 / chunkMillis).toInt()) {
                    recorder.process(loud)
                    nowMillis += chunkMillis
                }
                repeat((58_000 / chunkMillis).toInt()) {
                    recorder.process(quiet)
                    nowMillis += chunkMillis
                }
            } else {
                repeat(chunksPerMinute.toInt()) {
                    recorder.process(quiet)
                    nowMillis += chunkMillis
                }
            }
            minute += 1
        }

        recorder.stop()

        assertEquals(24, events.size)
        assertEquals(24, fragments.size)
        assertEquals(24, recorder.savedFragments)
        assertTrue("Неожиданные предупреждения: $warnings", warnings.isEmpty())

        val diagnostics = recorder.diagnostics
        assertEquals(24L, diagnostics.writerOpenCount)
        assertEquals(24L, diagnostics.writerSavedCount)
        assertEquals(0L, diagnostics.writerFailedCount)
        assertEquals("", diagnostics.lastWriterError)

        val sorted = fragments.sortedBy { it.contentStartSample }
        sorted.zipWithNext().forEach { (first, second) ->
            assertTrue(
                "Фрагменты пересекаются: ${first.file.name} и ${second.file.name}",
                first.contentEndSample <= second.contentStartSample,
            )
        }

        sorted.forEachIndexed { index, fragment ->
            val validation = WavFileValidator.diagnose(fragment.file)
            assertEquals(
                "Файл $index не прошёл проверку: ${validation.reason}",
                WavFileValidator.Status.VALID,
                validation.status,
            )
            assertEquals(18_000L, validation.info!!.durationMs)
            assertEquals(fragment.samples * 2L, validation.info.dataBytes)
            assertEquals(18_000L, fragment.durationMs)
            assertExpectedSamples(fragment)
        }

        val catalog = AudioFragmentCatalog.list(directory)
        assertEquals(24, catalog.size)
        assertEquals(24, catalog.map { it.fileName }.toSet().size)

        val expectedEventStarts = (0 until 24).map { index ->
            (5L + index * 10L) * 60L * config.sampleRate
        }
        assertEquals(expectedEventStarts, sorted.map { it.startSample })
        sorted.forEach { fragment ->
            assertEquals(config.preRollSamples.toLong(), fragment.startSample - fragment.contentStartSample)
            assertEquals(
                config.postRollSamples.toLong(),
                fragment.contentEndSample - fragment.endSample,
            )
        }
    }

    private fun assertRingRange(buffer: AudioRingBuffer, start: Long, count: Int) {
        val actual = buffer.copyAbsolute(start, count)
        assertEquals(count, actual.size)
        var index = 0
        while (index < count) {
            if (actual[index] != ringSampleAt(start + index)) {
                fail("Нарушен порядок в кольцевом буфере на индексе ${start + index}")
            }
            index += 1
        }
    }

    private fun assertExpectedSamples(fragment: SavedAudioFragment) {
        val bytes = fragment.file.readBytes()
        assertEquals(44 + fragment.samples.toInt() * 2, bytes.size)

        var index = 0
        val count = fragment.samples.toInt()
        while (index < count) {
            val low = bytes[44 + index * 2].toInt() and 0xFF
            val high = bytes[45 + index * 2].toInt()
            val actual = ((high shl 8) or low).toShort()
            val absolute = fragment.contentStartSample + index
            if (actual != expectedSampleAt(absolute)) {
                fail(
                    "Файл ${fragment.file.name}: сэмпл $index " +
                        "(абсолютный $absolute) повреждён или сдвинут",
                )
            }
            index += 1
        }
    }

    private fun expectedSampleAt(absoluteSample: Long): Short {
        val minute = absoluteSample / (60L * config.sampleRate)
        val second = (absoluteSample / config.sampleRate) % 60L
        val loud = minute % 10L == 5L && second < 2L
        return sampleAt(absoluteSample, loud)
    }

    private fun chunkFor(loud: Boolean): ShortArray =
        ShortArray(chunkSamples) { index -> sampleAt(index.toLong(), loud) }

    private fun sampleAt(index: Long, loud: Boolean): Short {
        val amplitude = if (loud) 1_800 else 3
        return if (index and 1L == 0L) {
            amplitude.toShort()
        } else {
            (-amplitude).toShort()
        }
    }

    private fun ringSampleAt(index: Long): Short =
        ((index % 20_000L) - 10_000L).toShort()

    private class DetectorListener : AudioEventDetector.Listener {
        val startedAtMillis = mutableListOf<Long>()
        val finished = mutableListOf<DetectedAudioEvent>()

        override fun onEventStarted(startedAtMillis: Long, startSample: Long) {
            this.startedAtMillis += startedAtMillis
        }

        override fun onEventFinished(event: DetectedAudioEvent) {
            finished += event
        }
    }
}
