package com.sleepy.app.sleepy.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavFileValidatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsValidPcm16MonoFile() {
        val directory = temporaryFolder.newFolder("valid")
        val file = writeWav(directory, "event_20260101_000000_000_001.wav", 32_000)

        val validation = WavFileValidator.diagnose(file)

        assertEquals(WavFileValidator.Status.VALID, validation.status)
        assertNotNull(validation.info)
        assertEquals(32_000L, validation.info!!.dataBytes)
        assertEquals(1_000L, validation.info.durationMs)
        assertEquals(44L + 32_000L, validation.info.fileSize)
        assertNull(WavFileValidator.diagnose(File(directory, "missing.wav")).info)
    }

    @Test
    fun rejectsRiffSizeThatDoesNotMatchFileLength() {
        val directory = temporaryFolder.newFolder("riff")
        val file = writeWav(
            directory = directory,
            name = "event_20260101_000000_000_001.wav",
            dataBytes = 32_000,
            riffSizeAdjustment = 1,
        )

        val validation = WavFileValidator.diagnose(file)

        assertEquals(WavFileValidator.Status.CORRUPTED, validation.status)
        assertTrue(validation.reason.contains("RIFF", ignoreCase = true))
    }

    @Test
    fun rejectsDataThatIsNotWholePcmSamples() {
        val directory = temporaryFolder.newFolder("odd")
        val file = writeWav(
            directory = directory,
            name = "event_20260101_000000_000_001.wav",
            dataBytes = 3,
        )

        val validation = WavFileValidator.diagnose(file)

        assertEquals(WavFileValidator.Status.CORRUPTED, validation.status)
        assertTrue(validation.reason.contains("выровнен", ignoreCase = true))
    }

    @Test
    fun rejectsTrailingBytesAfterDataChunk() {
        val directory = temporaryFolder.newFolder("trailing")
        val file = writeWav(
            directory = directory,
            name = "event_20260101_000000_000_001.wav",
            dataBytes = 32_000,
            extraTailBytes = 4,
        )

        val validation = WavFileValidator.diagnose(file)

        assertEquals(WavFileValidator.Status.CORRUPTED, validation.status)
        assertTrue(validation.reason.contains("data", ignoreCase = true))
    }

    @Test
    fun acceptsDataAfterListChunkWhenRiffSizeIsCorrect() {
        val directory = temporaryFolder.newFolder("chunks")
        val file = File(directory, "event_20260101_000000_000_001.wav")
        val header = wavHeader(32_000)
        val listChunk = ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("LIST".toByteArray(StandardCharsets.US_ASCII))
            .putInt(8)
            .put("INFO".toByteArray(StandardCharsets.US_ASCII))
            .putInt(0)
            .array()
        val totalSize = header.size + listChunk.size + 32_000
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(4, totalSize - 8)

        file.writeBytes(
            header.copyOfRange(0, 12) +
                listChunk +
                header.copyOfRange(12, 44) +
                ByteArray(32_000),
        )

        val validation = WavFileValidator.diagnose(file)

        assertEquals(WavFileValidator.Status.VALID, validation.status)
        assertEquals(32_000L, validation.info!!.dataBytes)
    }

    private fun writeWav(
        directory: File,
        name: String,
        dataBytes: Int,
        riffSizeAdjustment: Int = 0,
        extraTailBytes: Int = 0,
    ): File {
        val header = wavHeader(dataBytes)
        ByteBuffer.wrap(header)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4, 36 + dataBytes + extraTailBytes + riffSizeAdjustment)

        val file = File(directory, name)
        file.writeBytes(header + ByteArray(dataBytes) + ByteArray(extraTailBytes))
        return file
    }

    private fun wavHeader(dataBytes: Int): ByteArray =
        ByteBuffer.allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(StandardCharsets.US_ASCII))
            .putInt(36 + dataBytes)
            .put("WAVE".toByteArray(StandardCharsets.US_ASCII))
            .put("fmt ".toByteArray(StandardCharsets.US_ASCII))
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(16_000)
            .putInt(32_000)
            .putShort(2)
            .putShort(16)
            .put("data".toByteArray(StandardCharsets.US_ASCII))
            .putInt(dataBytes)
            .array()
}
