package com.sleepy.app.sleepy.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingProfileTest {

    @Test
    fun standardPresetMatchesCurrentDefaults() {
        val standard = RecordingConfig.forProfile(RecordingProfile.STANDARD)

        assertEquals(RecordingConfig.DEFAULT, standard)
        assertEquals(4_000, standard.preRollMs)
        assertEquals(4_000, standard.postRollMs)
        assertEquals(300, standard.minEventMs)
        assertEquals(2_000, standard.mergeGapMs)
        assertEquals(12.0, standard.thresholdAboveNoiseDb, 0.0)
        assertEquals(-45.0, standard.absoluteFloorDbfs, 0.0)
        assertFalse(standard.weakEnabled)
    }

    @Test
    fun highProfileAddsLongerCaptureAndWeakCandidates() {
        val high = RecordingConfig.forProfile(RecordingProfile.HIGH)

        assertEquals(4_000, high.preRollMs)
        assertEquals(4_000, high.postRollMs)
        assertEquals(200, high.minEventMs)
        assertEquals(3_000, high.mergeGapMs)
        assertTrue(high.weakEnabled)
        assertEquals(7.0, high.weakThresholdAboveNoiseDb, 0.0)
        assertEquals(-52.0, high.weakAbsoluteFloorDbfs, 0.0)
        assertEquals(250, high.weakMinEventMs)
        assertEquals(8, high.weakMaxEventSeconds)
        assertEquals(200, high.weakMaxCandidatesPerSession)
    }

    @Test
    fun veryHighProfileIsMoreSensitiveThanHigh() {
        val high = RecordingConfig.forProfile(RecordingProfile.HIGH)
        val veryHigh = RecordingConfig.forProfile(RecordingProfile.VERY_HIGH)

        assertTrue(veryHigh.thresholdAboveNoiseDb < high.thresholdAboveNoiseDb)
        assertTrue(veryHigh.absoluteFloorDbfs < high.absoluteFloorDbfs)
        assertTrue(veryHigh.weakThresholdAboveNoiseDb < high.weakThresholdAboveNoiseDb)
        assertTrue(veryHigh.weakAbsoluteFloorDbfs < high.weakAbsoluteFloorDbfs)
        assertEquals(4_000, veryHigh.preRollMs)
        assertEquals(4_000, veryHigh.postRollMs)
        assertEquals(150, veryHigh.minEventMs)
        assertEquals(200, veryHigh.weakMinEventMs)
        assertEquals(250, veryHigh.weakMaxCandidatesPerSession)
    }

    @Test
    fun unknownStoredProfileFallsBackToStandard() {
        assertEquals(RecordingProfile.STANDARD, RecordingProfile.fromStorage(null))
        assertEquals(RecordingProfile.STANDARD, RecordingProfile.fromStorage("UNKNOWN"))
        assertEquals(RecordingProfile.HIGH, RecordingProfile.fromStorage("HIGH"))
    }
}
