package com.sleepy.app.sleepy.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioFragmentCatalogTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun missingDirectoryReturnsEmptyList() {
        val directory = File(temporaryFolder.root, "no-such-directory")

        assertTrue(AudioFragmentCatalog.list(directory).isEmpty())
    }

    @Test
    fun emptyDirectoryReturnsEmptyList() {
        val directory = temporaryFolder.newFolder("recordings")

        assertTrue(AudioFragmentCatalog.list(directory).isEmpty())
    }

    @Test
    fun computesDurationFromWavHeader() {
        val directory = temporaryFolder.newFolder("recordings")
        writeWav(
            directory = directory,
            name = "event_20261115_230000_000_001.wav",
            dataBytes = 32_000,
        )

        val fragments = AudioFragmentCatalog.list(directory)

        assertEquals(1, fragments.size)
        assertEquals(1_000L, fragments.single().durationMs)
    }

    @Test
    fun excludesLegacyAndForeignFiles() {
        val directory = temporaryFolder.newFolder("recordings")

        writeWav(directory, "event_20261115_230000_000_001.wav", 32_000)
        writeWav(directory, "sleep_20261115_220000.wav", 32_000)
        writeWav(directory, "notes.txt", 32_000)
        writeWav(directory, "event_broken.wav", 32_000, format = 3)

        val fragments = AudioFragmentCatalog.list(directory)

        assertEquals(
            listOf("event_20261115_230000_000_001.wav"),
            fragments.map { it.fileName },
        )
    }

    @Test
    fun sortsNewestFirstAndContinuationsAfterOrigin() {
        val directory = temporaryFolder.newFolder("recordings")

        writeWav(directory, "event_20261114_010000_000_001.wav", 16_000)
        writeWav(directory, "event_20261115_230000_000_002.wav", 16_000)
        writeWav(directory, "event_20261115_230000_000_001.wav", 16_000)
        writeWav(directory, "event_20261113_230000_000_001.wav", 16_000)

        val names = AudioFragmentCatalog.list(directory).map { it.fileName }

        assertEquals(
            listOf(
                "event_20261115_230000_000_002.wav",
                "event_20261115_230000_000_001.wav",
                "event_20261114_010000_000_001.wav",
                "event_20261113_230000_000_001.wav",
            ),
            names,
        )
    }

    @Test
    fun skipsTooShortAndCorruptFiles() {
        val directory = temporaryFolder.newFolder("recordings")

        File(directory, "event_20261115_230000_000_001.wav")
            .writeBytes(ByteArray(20))
        File(directory, "event_20261115_230000_000_002.wav")
            .writeText("definitely not a wave file, just some arbitrary text payload")

        assertTrue(AudioFragmentCatalog.list(directory).isEmpty())
    }

    @Test
    fun rejectsHeaderOnlyFileWithoutPcmData() {
        val directory = temporaryFolder.newFolder("recordings")
        writeWav(directory, "event_20261115_230000_000_001.wav", dataBytes = 0)

        assertTrue(AudioFragmentCatalog.list(directory).isEmpty())
        assertEquals(0, AudioFragmentCatalog.countValid(directory))
    }

    @Test
    fun rejectsWrongSampleRateChannelsOrBitDepth() {
        val directory = temporaryFolder.newFolder("recordings")

        File(directory, "event_20261115_230000_000_001.wav").writeBytes(
            wavHeader(dataBytes = 32_000, sampleRate = 44_100) + ByteArray(32_000),
        )
        File(directory, "event_20261115_230000_000_002.wav").writeBytes(
            wavHeader(dataBytes = 32_000, channels = 2) + ByteArray(32_000),
        )
        File(directory, "event_20261115_230000_000_003.wav").writeBytes(
            wavHeader(dataBytes = 32_000, bitsPerSample = 8) + ByteArray(32_000),
        )

        assertTrue(AudioFragmentCatalog.list(directory).isEmpty())
        assertEquals(0, AudioFragmentCatalog.countValid(directory))
    }

    @Test
    fun rejectsIncompleteFileWhoseHeaderSizeExceedsData() {
        val directory = temporaryFolder.newFolder("recordings")
        val file = File(directory, "event_20261115_230000_000_001.wav")

        file.writeBytes(wavHeader(dataBytes = 100_000) + ByteArray(5_000))

        assertTrue(AudioFragmentCatalog.list(directory).isEmpty())
        assertEquals(0, AudioFragmentCatalog.countValid(directory))
    }

    @Test
    fun fallsBackToFileModificationTimeForUnusualName() {
        val directory = temporaryFolder.newFolder("recordings")
        val file = writeWav(directory, "event_manual.wav", 32_000)
        val expected = file.lastModified()

        val fragment = AudioFragmentCatalog.list(directory).single()

        assertEquals(expected, fragment.startedAtMillis)
        assertEquals(1_000L, fragment.durationMs)
    }

    @Test
    fun walksChunksBeforeDataChunk() {
        val directory = temporaryFolder.newFolder("recordings")
        val file = File(directory, "event_20261115_230000_000_001.wav")

        val header = wavHeader(dataBytes = 32_000)
        val listChunk = ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("LIST".toByteArray(StandardCharsets.US_ASCII))
            .putInt(8)
            .put("INFO".toByteArray(StandardCharsets.US_ASCII))
            .putInt(0)
            .array()

        val totalSize = 44 + listChunk.size + 32_000
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(4, totalSize - 8)
        file.writeBytes(header.copyOfRange(0, 12) + listChunk + header.copyOfRange(12, 44) + ByteArray(32_000))

        val fragment = AudioFragmentCatalog.list(directory).single()

        assertEquals(1_000L, fragment.durationMs)
    }

    @Test
    fun describesFileWithIdPathAndTimestamp() {
        val directory = temporaryFolder.newFolder("recordings")
        val file = writeWav(directory, "event_20261115_230000_000_001.wav", 32_000)

        val fragment = AudioFragmentCatalog.list(directory).single()

        assertEquals("event_20261115_230000_000_001.wav", fragment.id)
        assertEquals(file.absolutePath, fragment.filePath)
        assertTrue(fragment.startedAtMillis > 0L)
        assertEquals(44L + 32_000L, fragment.sizeBytes)
    }

    @Test
    fun diagnoseKeepsValidEmptyAndCorruptedFilesSeparate() {
        val directory = temporaryFolder.newFolder("recordings")

        writeWav(directory, "event_20261115_230000_000_001.wav", 32_000)
        writeWav(directory, "event_20261115_230000_000_002.wav", dataBytes = 0)
        File(directory, "event_20261115_230000_000_003.wav")
            .writeText("not a wave file")

        val diagnostics = AudioFragmentCatalog.diagnose(directory)

        assertEquals(3, diagnostics.size)
        assertEquals(
            WavFileValidator.Status.VALID,
            diagnostics.first { it.fileName.endsWith("_001.wav") }.status,
        )
        assertEquals(
            WavFileValidator.Status.EMPTY,
            diagnostics.first { it.fileName.endsWith("_002.wav") }.status,
        )
        assertEquals(
            WavFileValidator.Status.CORRUPTED,
            diagnostics.first { it.fileName.endsWith("_003.wav") }.status,
        )
    }

    @Test
    fun diagnoseDoesNotInventDurationForEmptyOrCorruptedFiles() {
        val directory = temporaryFolder.newFolder("recordings")

        writeWav(directory, "event_20261115_230000_000_001.wav", dataBytes = 0)
        File(directory, "event_20261115_230000_000_002.wav")
            .writeBytes(wavHeader(dataBytes = 100_000) + ByteArray(5_000))

        val diagnostics = AudioFragmentCatalog.diagnose(directory)

        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics.all { it.durationMs == null })
    }

    @Test
    fun diagnoseMarksMissingFileAsFailed() {
        val missing = File(temporaryFolder.root, "event_missing.wav")

        val validation = WavFileValidator.diagnose(missing)

        assertEquals(WavFileValidator.Status.FAILED, validation.status)
        assertTrue(validation.reason.isNotBlank())
    }

    @Test
    fun diagnoseExplainsTruncatedDataChunk() {
        val directory = temporaryFolder.newFolder("recordings")
        val file = File(directory, "event_20261115_230000_000_001.wav")

        file.writeBytes(wavHeader(dataBytes = 100_000) + ByteArray(5_000))

        val validation = WavFileValidator.diagnose(file)

        assertEquals(WavFileValidator.Status.CORRUPTED, validation.status)
        assertTrue(validation.reason.contains("data", ignoreCase = true))
    }

    private fun writeWav(
        directory: File,
        name: String,
        dataBytes: Int,
        format: Short = 1,
    ): File {
        val file = File(directory, name)
        file.writeBytes(wavHeader(dataBytes, format) + ByteArray(dataBytes))
        return file
    }

    private fun wavHeader(
        dataBytes: Int,
        format: Short = 1,
        channels: Short = 1,
        sampleRate: Int = 16_000,
        bitsPerSample: Short = 16,
    ): ByteArray {
        return ByteBuffer.allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(StandardCharsets.US_ASCII))
            .putInt(36 + dataBytes)
            .put("WAVE".toByteArray(StandardCharsets.US_ASCII))
            .put("fmt ".toByteArray(StandardCharsets.US_ASCII))
            .putInt(16)
            .putShort(format)
            .putShort(channels)
            .putInt(sampleRate)
            .putInt(sampleRate * channels * bitsPerSample / 8)
            .putShort(((channels * bitsPerSample) / 8).toShort())
            .putShort(bitsPerSample)
            .put("data".toByteArray(StandardCharsets.US_ASCII))
            .putInt(dataBytes)
            .array()
    }
}
