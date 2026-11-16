package com.sleepy.app.sleepy.audio

import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioEventDetectorTest {

    private val config = RecordingConfig.DEFAULT

    private var nowMillis = 0L

    private lateinit var listener: RecordingListener
    private lateinit var detector: AudioEventDetector

    @Before
    fun setUp() {
        nowMillis = 0L
        listener = RecordingListener()
        detector = AudioEventDetector(
            config = config,
            listener = listener,
            clock = { nowMillis },
        )
    }

    @Test
    fun silenceProducesNoEvents() {
        feed(SILENCE_DBFS, 5_000)

        assertEquals(0, listener.startedAtMillis.size)
        assertEquals(0, listener.finished.size)
        assertFalse(detector.isEventActive)
    }

    @Test
    fun steadyBackgroundNoiseProducesNoEvents() {
        feed(-30.0, 10_000)

        assertEquals(0, listener.startedAtMillis.size)
        assertEquals(0, listener.finished.size)
        assertEquals(-30.0, detector.noiseFloorDbfs, 1.0)
    }

    @Test
    fun singleShortImpulseIsIgnored() {
        feed(SILENCE_DBFS, 1_000)
        feed(-10.0, config.chunkMs)
        feed(SILENCE_DBFS, 3_000)

        assertEquals(0, listener.startedAtMillis.size)
        assertEquals(0, listener.finished.size)
    }

    @Test
    fun burstShorterThanMinimumDurationIsIgnored() {
        feed(SILENCE_DBFS, 1_000)
        feed(-10.0, 280)
        feed(SILENCE_DBFS, 3_000)

        assertEquals(0, listener.startedAtMillis.size)
        assertEquals(0, listener.finished.size)
    }

    @Test
    fun eventAtMinimumDurationIsDetected() {
        feed(SILENCE_DBFS, 1_000)
        feed(-10.0, 300)
        feed(SILENCE_DBFS, 3_000)

        assertEquals(1, listener.startedAtMillis.size)
        assertEquals(1, listener.finished.size)
        assertEquals(300L, listener.finished.single().durationMs)
    }

    @Test
    fun normalEventIsDetectedWithTimestamps() {
        feed(SILENCE_DBFS, 1_000)
        feed(-10.0, 1_000)
        feed(SILENCE_DBFS, 3_000)

        val event = listener.finished.single()

        assertEquals(1, listener.startedAtMillis.size)
        assertEquals(1_000L, listener.startedAtMillis.single())
        assertEquals(1_000L, event.startedAtMillis)
        assertEquals(2_000L, event.endedAtMillis)
        assertEquals(1_000L, event.durationMs)
        assertEquals(-10.0, event.peakDbfs, 0.2)
        assertEquals(-10.0, event.averageDbfs, 0.2)
        assertFalse(event.truncated)
        assertEquals(16_000L, event.startSample)
        assertEquals(32_000L, event.endSample)
        assertEquals(16_000L, listener.startSamples.single())
        assertFalse(detector.isEventActive)
    }

    @Test
    fun eventsWithinMergeGapAreMerged() {
        feed(SILENCE_DBFS, 1_000)
        feed(-10.0, 500)
        feed(SILENCE_DBFS, 2_000)
        feed(-10.0, 500)
        feed(SILENCE_DBFS, 3_000)

        assertEquals(1, listener.startedAtMillis.size)
        assertEquals(1, listener.finished.size)
        assertEquals(3_000L, listener.finished.single().durationMs)
    }

    @Test
    fun eventsSeparatedByMoreThanMergeGapAreSplit() {
        feed(SILENCE_DBFS, 1_000)
        feed(-10.0, 500)
        feed(SILENCE_DBFS, 2_020)
        feed(-10.0, 500)
        feed(SILENCE_DBFS, 3_000)

        assertEquals(2, listener.startedAtMillis.size)
        assertEquals(2, listener.finished.size)
        assertEquals(500L, listener.finished[0].durationMs)
        assertEquals(500L, listener.finished[1].durationMs)
    }

    @Test
    fun eventLongerThanMaximumIsSplitIntoTimedFragments() {
        feed(SILENCE_DBFS, 1_000)
        feed(-10.0, 70_000)
        feed(SILENCE_DBFS, 3_000)

        assertEquals(2, listener.startedAtMillis.size)
        assertEquals(2, listener.finished.size)
        assertEquals(60_000L, listener.finished[0].durationMs)
        assertTrue(listener.finished[0].truncated)
        assertEquals(10_000L, listener.finished[1].durationMs)
        assertFalse(listener.finished[1].truncated)
        assertEquals(16_000L, listener.finished[0].startSample)
        assertEquals(976_000L, listener.finished[0].endSample)
        assertEquals(listener.finished[0].endSample, listener.finished[1].startSample)
        assertEquals(16_000L, listener.startSamples[0])
        assertEquals(976_000L, listener.startSamples[1])
    }

    @Test
    fun noiseFloorDoesNotRiseDuringActiveEvent() {
        feed(-60.0, 1_000)
        val floorBeforeEvent = detector.noiseFloorDbfs
        feed(-10.0, 5_000)

        assertEquals(floorBeforeEvent, detector.noiseFloorDbfs, 0.0)
        assertEquals(-60.0, floorBeforeEvent, 1.0)
        assertTrue(detector.isEventActive)

        feed(SILENCE_DBFS, 3_000)

        assertEquals(1, listener.finished.size)
    }

    @Test
    fun noiseFloorAdaptsToSustainedBackground() {
        feed(-30.0, 1_000)
        feed(-30.0, 2_000)

        assertEquals(-30.0, detector.noiseFloorDbfs, 0.1)

        feed(-20.0, 1_000)

        assertEquals(0, listener.startedAtMillis.size)
        assertTrue(detector.noiseFloorDbfs > -25.0)
        assertTrue(detector.noiseFloorDbfs < -23.0)

        feed(-10.0, 1_000)
        feed(SILENCE_DBFS, 3_000)

        assertEquals(1, listener.startedAtMillis.size)
        assertEquals(1, listener.finished.size)
    }

    @Test
    fun triggerRespectsAbsoluteFloor() {
        feed(SILENCE_DBFS, 1_000)
        feed(-45.5, 500)

        assertEquals(0, listener.startedAtMillis.size)

        feed(-44.0, 500)
        feed(SILENCE_DBFS, 3_000)

        assertEquals(1, listener.startedAtMillis.size)
        assertEquals(1, listener.finished.size)
    }

    @Test
    fun flushFinishesActiveEvent() {
        feed(SILENCE_DBFS, 1_000)
        feed(-10.0, 1_000)

        assertTrue(detector.isEventActive)

        val event = detector.flush()

        assertNotNull(event)
        assertEquals(1_000L, event!!.durationMs)
        assertEquals(1, listener.finished.size)
        assertFalse(detector.isEventActive)
        assertNull(detector.flush())
    }

    @Test
    fun diagnosticsTrackDetectorStateAndCounters() {
        feed(SILENCE_DBFS, 1_000)

        assertEquals(AudioEventDetector.DetectorState.QUIET, detector.currentState)
        assertEquals(0L, detector.candidateCountTotal)
        assertEquals(0L, detector.confirmedEventCountTotal)

        feed(-10.0, 100)

        assertEquals(AudioEventDetector.DetectorState.CANDIDATE, detector.currentState)
        assertEquals(1L, detector.candidateCountTotal)
        assertEquals(0L, detector.confirmedEventCountTotal)

        feed(-10.0, 220)

        assertEquals(AudioEventDetector.DetectorState.ACTIVE, detector.currentState)
        assertEquals(1L, detector.candidateCountTotal)
        assertEquals(1L, detector.confirmedEventCountTotal)

        feed(SILENCE_DBFS, 3_000)

        assertEquals(AudioEventDetector.DetectorState.QUIET, detector.currentState)
        assertEquals(1L, detector.candidateCountTotal)
        assertEquals(1L, detector.confirmedEventCountTotal)
        assertTrue(detector.currentChunkDbfs <= 0.0)
        assertTrue(detector.noiseFloorDbfs <= 0.0)
    }

    @Test
    fun invalidInputIsRejectedAndDetectorKeepsWorking() {
        feed(SILENCE_DBFS, 1_000)

        val chunk = chunk(1_000)

        assertThrows(IllegalArgumentException::class.java) {
            detector.process(chunk, offset = -1, length = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            detector.process(chunk, offset = 0, length = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            detector.process(chunk, offset = 1, length = chunk.size)
        }
        assertThrows(IllegalArgumentException::class.java) {
            detector.process(chunk, offset = chunk.size + 1, length = 0)
        }

        detector.process(ShortArray(0))
        detector.process(chunk, offset = 0, length = 0)

        assertEquals(0, listener.startedAtMillis.size)

        feed(-10.0, 1_000)
        feed(SILENCE_DBFS, 3_000)

        assertEquals(1, listener.startedAtMillis.size)
        assertEquals(1, listener.finished.size)
    }

    private fun feed(dbfs: Double, durationMs: Int) {
        val chunkCount = durationMs / config.chunkMs
        val amplitude = amplitudeFor(dbfs)

        repeat(chunkCount) {
            detector.process(chunk(amplitude))
            nowMillis += config.chunkMs
        }
    }

    private fun chunk(amplitude: Int): ShortArray =
        ShortArray(config.chunkSamples) { index ->
            if (index and 1 == 0) amplitude.toShort() else (-amplitude).toShort()
        }

    private fun amplitudeFor(dbfs: Double): Int {
        if (dbfs <= RecordingConfig.DEFAULT.absoluteFloorDbfs - 60.0) return 0

        return (FULL_SCALE * 10.0.pow(dbfs / 20.0))
            .roundToInt()
            .coerceIn(0, 32_767)
    }

    private class RecordingListener : AudioEventDetector.Listener {
        val startedAtMillis = mutableListOf<Long>()
        val startSamples = mutableListOf<Long>()
        val finished = mutableListOf<DetectedAudioEvent>()

        override fun onEventStarted(startedAtMillis: Long, startSample: Long) {
            this.startedAtMillis += startedAtMillis
            this.startSamples += startSample
        }

        override fun onEventFinished(event: DetectedAudioEvent) {
            finished += event
        }
    }

    private companion object {
        const val SILENCE_DBFS = -120.0
        const val FULL_SCALE = 32_768.0
    }
}
