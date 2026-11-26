package com.sleepy.app.sleepy.audio

import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioWeakCandidateTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val config = RecordingConfig.HIGH

    private var nowMillis = 0L

    @After
    fun noop() {
        nowMillis = 0L
    }

    @Test
    fun highProfileEmitsWeakCandidateBelowNormalTrigger() {
        val listener = DetectorListener()
        val detector = AudioEventDetector(
            config = config,
            listener = listener,
            clock = { nowMillis },
        )

        feed(detector, config, -120.0, 2_000)
        feed(detector, config, -48.0, 500)
        feed(detector, config, -120.0, 3_000)

        assertEquals(0, listener.normal.size)
        val weak = listener.weak.single()
        assertEquals(RecordingEventMetadata.EVENT_CLASS_WEAK, weak.eventClass)
        assertEquals(500L, weak.durationMs)
        assertFalse(weak.truncated)
        assertEquals(1L, detector.weakCandidateCountTotal)
        assertEquals(1L, detector.weakEmittedCountTotal)
    }

    @Test
    fun standardProfileDoesNotCreateWeakCandidates() {
        val listener = DetectorListener()
        val detector = AudioEventDetector(
            config = RecordingConfig.DEFAULT,
            listener = listener,
            clock = { nowMillis },
        )

        feed(detector, RecordingConfig.DEFAULT, -120.0, 2_000)
        feed(detector, RecordingConfig.DEFAULT, -48.0, 500)
        feed(detector, RecordingConfig.DEFAULT, -120.0, 3_000)

        assertTrue(listener.weak.isEmpty())
        assertTrue(listener.normal.isEmpty())
    }

    @Test
    fun weakCandidateShorterThanMinimumIsRejected() {
        val listener = DetectorListener()
        val detector = AudioEventDetector(
            config = config,
            listener = listener,
            clock = { nowMillis },
        )

        feed(detector, config, -120.0, 2_000)
        feed(detector, config, -48.0, 200)
        feed(detector, config, -120.0, 3_000)

        assertTrue(listener.weak.isEmpty())
        assertEquals(1L, detector.weakRejectedCountTotal)
        assertEquals("weak-too-short", detector.lastWeakRejectionReason)
    }

    @Test
    fun continuousWeakNoiseIsCappedAndBlockedUntilQuiet() {
        val listener = DetectorListener()
        val detector = AudioEventDetector(
            config = config,
            listener = listener,
            clock = { nowMillis },
        )

        feed(detector, config, -120.0, 2_000)
        feed(detector, config, -48.0, 20_000)
        feed(detector, config, -120.0, 3_000)

        assertEquals(1, listener.weak.size)
        assertTrue(listener.weak.single().truncated)
        assertEquals(8_000L, listener.weak.single().durationMs)
        assertEquals(1L, detector.weakCandidateCountTotal)
    }

    @Test
    fun strongEventIsNotDuplicatedAsWeakCandidate() {
        val listener = DetectorListener()
        val detector = AudioEventDetector(
            config = config,
            listener = listener,
            clock = { nowMillis },
        )

        feed(detector, config, -120.0, 2_000)
        feed(detector, config, -30.0, 500)
        feed(detector, config, -120.0, 4_000)

        assertEquals(1, listener.normal.size)
        assertTrue(listener.weak.isEmpty())
    }

    @Test
    fun recorderSavesWeakCandidateWithV2Metadata() {
        val directory = temporaryFolder.newFolder("weak-fragments")
        val fragments = mutableListOf<SavedAudioFragment>()
        val recorder = AudioFragmentRecorder(
            config = config,
            directory = directory,
            clock = { nowMillis },
            listener = object : AudioFragmentRecorder.Listener {
                override fun onFragmentSaved(fragment: SavedAudioFragment) {
                    fragments += fragment
                }
            },
        )
        try {
            feed(recorder, config, -120.0, 10_000)
            feed(recorder, config, -48.0, 500)
            feed(recorder, config, -120.0, 3_000)
            recorder.stop()
        } finally {
            recorder.stop()
        }

        val fragment = fragments.single()
        assertEquals(RecordingEventMetadata.EVENT_CLASS_WEAK, fragment.eventClass)
        assertEquals(RecordingEventMetadata.REVIEW_STATUS_UNREVIEWED, fragment.reviewStatus)
        assertEquals("HIGH", fragment.profileId)
        assertFalse(fragment.triggerReasons.isBlank())

        val metadata = requireNotNull(AudioFragmentMetadata.read(fragment.file))
        assertEquals(2, metadata.schemaVersion)
        assertEquals(RecordingEventMetadata.EVENT_CLASS_WEAK, metadata.eventClass)
        assertEquals(RecordingEventMetadata.REVIEW_STATUS_UNREVIEWED, metadata.reviewStatus)
        assertEquals("HIGH", metadata.profileId)
        assertTrue(metadata.peakDbfs != null)
        assertTrue(metadata.averageDbfs != null)
        assertEquals(1L, recorder.diagnostics.weakSavedCount)
    }

    @Test
    fun recorderRejectsWeakCandidatesDuringCooldown() {
        val directory = temporaryFolder.newFolder("weak-cooldown")
        val fragments = mutableListOf<SavedAudioFragment>()
        val recorder = AudioFragmentRecorder(
            config = config,
            directory = directory,
            clock = { nowMillis },
            listener = object : AudioFragmentRecorder.Listener {
                override fun onFragmentSaved(fragment: SavedAudioFragment) {
                    fragments += fragment
                }
            },
        )
        try {
            feed(recorder, config, -120.0, 10_000)
            feed(recorder, config, -48.0, 500)
            feed(recorder, config, -120.0, 3_000)
            feed(recorder, config, -48.0, 500)
            feed(recorder, config, -120.0, 3_000)
            recorder.stop()
        } finally {
            recorder.stop()
        }

        assertEquals(1, fragments.size)
        assertTrue(recorder.diagnostics.weakRejectedCount >= 1)
        assertEquals("weak-cooldown", recorder.diagnostics.lastWeakRejection)
    }

    private class DetectorListener : AudioEventDetector.Listener {
        val normal = mutableListOf<DetectedAudioEvent>()
        val weak = mutableListOf<DetectedAudioEvent>()

        override fun onEventFinished(event: DetectedAudioEvent) {
            normal += event
        }

        override fun onWeakCandidate(event: DetectedAudioEvent) {
            weak += event
        }
    }

    private fun feed(
        detector: AudioEventDetector,
        config: RecordingConfig,
        dbfs: Double,
        durationMs: Int,
    ) {
        val amplitude = amplitudeFor(dbfs)
        repeat(durationMs / config.chunkMs) {
            detector.process(chunk(config, amplitude))
            nowMillis += config.chunkMs
        }
    }

    private fun feed(
        recorder: AudioFragmentRecorder,
        config: RecordingConfig,
        dbfs: Double,
        durationMs: Int,
    ) {
        val amplitude = amplitudeFor(dbfs)
        repeat(durationMs / config.chunkMs) {
            recorder.process(chunk(config, amplitude))
            nowMillis += config.chunkMs
        }
    }

    private fun chunk(config: RecordingConfig, amplitude: Int): ShortArray =
        ShortArray(config.chunkSamples) { index ->
            if (index and 1 == 0) amplitude.toShort() else (-amplitude).toShort()
        }

    private fun amplitudeFor(dbfs: Double): Int {
        if (dbfs <= AudioEventDetector.MIN_DBFS + 1.0) return 0
        return (32_768.0 * 10.0.pow(dbfs / 20.0))
            .roundToInt()
            .coerceIn(0, 32_767)
    }
}
