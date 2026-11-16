package com.sleepy.app.sleepy.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRingBufferTest {

    @Test
    fun newBufferIsEmpty() {
        val buffer = AudioRingBuffer(16)

        assertEquals(0, buffer.availableSamples())
        assertFalse(buffer.isFull)
        assertTrue(buffer.copyLast(16).isEmpty())
    }

    @Test
    fun partiallyFilledBufferReturnsOnlyWrittenSamples() {
        val buffer = createBuffer(10, 1, 2, 3)

        assertEquals(3, buffer.availableSamples())
        assertFalse(buffer.isFull)
        assertArrayEquals(intArrayOf(1, 2, 3), buffer.copyLast(10).asInts())
    }

    @Test
    fun requestLargerThanAvailableReturnsAvailableSamples() {
        val buffer = createBuffer(8, 1, 2, 3, 4, 5)

        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5), buffer.copyLast(100).asInts())
    }

    @Test
    fun exactFillMarksBufferAsFull() {
        val buffer = createBuffer(5, 1, 2, 3, 4, 5)

        assertEquals(5, buffer.availableSamples())
        assertTrue(buffer.isFull)
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5), buffer.copyLast(5).asInts())
    }

    @Test
    fun overflowKeepsOnlyLatestSamples() {
        val buffer = createBuffer(5, 1, 2, 3, 4, 5, 6, 7)

        assertEquals(5, buffer.availableSamples())
        assertArrayEquals(intArrayOf(3, 4, 5, 6, 7), buffer.copyLast(5).asInts())
        assertArrayEquals(intArrayOf(6, 7), buffer.copyLast(2).asInts())
    }

    @Test
    fun chunkCrossingEndIsStoredInCorrectOrder() {
        val buffer = createBuffer(10, 1, 2, 3, 4, 5, 6, 7)
        buffer.write(shortArrayOf(8, 9, 10, 11, 12))

        assertTrue(buffer.isFull)
        assertArrayEquals(
            intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
            buffer.copyLast(10).asInts(),
        )
        assertArrayEquals(intArrayOf(7, 8, 9, 10, 11, 12), buffer.copyLast(6).asInts())
    }

    @Test
    fun writeExactlyAtEndWrapsWriteIndexToZero() {
        val buffer = createBuffer(10, 1, 2, 3, 4, 5, 6, 7, 8)
        buffer.write(shortArrayOf(9, 10))

        assertTrue(buffer.isFull)
        assertArrayEquals(
            intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
            buffer.copyLast(10).asInts(),
        )
    }

    @Test
    fun chunkBiggerThanCapacityKeepsTail() {
        val buffer = createBuffer(4, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        assertEquals(4, buffer.availableSamples())
        assertArrayEquals(intArrayOf(7, 8, 9, 10), buffer.copyLast(4).asInts())

        buffer.write(shortArrayOf(11, 12))

        assertArrayEquals(intArrayOf(9, 10, 11, 12), buffer.copyLast(4).asInts())
    }

    @Test
    fun writeRespectsOffsetAndLength() {
        val source = shortArrayOf(0, 1, 2, 3, 4, 5)
        val buffer = AudioRingBuffer(10)

        buffer.write(source, offset = 2, length = 3)

        assertArrayEquals(intArrayOf(2, 3, 4), buffer.copyLast(10).asInts())
    }

    @Test
    fun writeWithoutLengthUsesRestOfSource() {
        val source = shortArrayOf(0, 1, 2, 3, 4, 5)
        val buffer = AudioRingBuffer(10)

        buffer.write(source, offset = 2)

        assertArrayEquals(intArrayOf(2, 3, 4, 5), buffer.copyLast(10).asInts())
    }

    @Test
    fun zeroLengthWriteChangesNothing() {
        val buffer = createBuffer(10, 1, 2)
        buffer.write(shortArrayOf())
        buffer.write(shortArrayOf(7, 8), offset = 0, length = 0)

        assertEquals(2, buffer.availableSamples())
        assertArrayEquals(intArrayOf(1, 2), buffer.copyLast(10).asInts())
    }

    @Test
    fun zeroCountCopyReturnsEmptyArray() {
        val buffer = createBuffer(10, 1, 2, 3)

        assertTrue(buffer.copyLast(0).isEmpty())
    }

    @Test
    fun clearResetsBufferAndZeroesData() {
        val buffer = createBuffer(4, 1, 2, 3, 4)
        buffer.clear()

        assertEquals(0, buffer.availableSamples())
        assertFalse(buffer.isFull)
        assertTrue(buffer.copyLast(4).isEmpty())

        buffer.write(shortArrayOf(9))
        assertArrayEquals(intArrayOf(9), buffer.copyLast(4).asInts())
    }

    @Test
    fun constructorRejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException::class.java) { AudioRingBuffer(0) }
        assertThrows(IllegalArgumentException::class.java) { AudioRingBuffer(-1) }
    }

    @Test
    fun writeRejectsInvalidBounds() {
        val buffer = AudioRingBuffer(10)
        val source = shortArrayOf(1, 2, 3)

        assertThrows(IllegalArgumentException::class.java) {
            buffer.write(source, offset = -1, length = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            buffer.write(source, offset = 0, length = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            buffer.write(source, offset = 1, length = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            buffer.write(source, offset = 0, length = 4)
        }
    }

    @Test
    fun copyLastRejectsNegativeCount() {
        val buffer = AudioRingBuffer(4)

        assertThrows(IllegalArgumentException::class.java) { buffer.copyLast(-1) }
    }

    @Test
    fun totalSamplesWrittenCountsEvictedSamples() {
        val buffer = createBuffer(5, 1, 2, 3, 4, 5, 6, 7)

        assertEquals(7L, buffer.totalSamplesWritten)
        assertEquals(2L, buffer.oldestAvailableSample)
        assertEquals(5, buffer.availableSamples())
    }

    @Test
    fun copyAbsoluteReturnsRangeInChronologicalOrder() {
        val buffer = createBuffer(5, 1, 2, 3, 4, 5, 6, 7)

        assertArrayEquals(intArrayOf(3, 4, 5), buffer.copyAbsolute(2, 3).asInts())
        assertArrayEquals(intArrayOf(6, 7), buffer.copyAbsolute(5, 10).asInts())
        assertArrayEquals(intArrayOf(7), buffer.copyAbsolute(6, 1).asInts())
    }

    @Test
    fun copyAbsoluteClampsToAvailableHistory() {
        val buffer = createBuffer(5, 1, 2, 3, 4, 5, 6, 7)

        assertArrayEquals(intArrayOf(3, 4), buffer.copyAbsolute(1, 3).asInts())
        assertTrue(buffer.copyAbsolute(7, 1).isEmpty())
        assertTrue(buffer.copyAbsolute(100, 5).isEmpty())
    }

    @Test
    fun copyAbsoluteHandlesChunkBiggerThanCapacity() {
        val buffer = createBuffer(4, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        assertEquals(10L, buffer.totalSamplesWritten)
        assertArrayEquals(intArrayOf(7, 8, 9, 10), buffer.copyAbsolute(6, 4).asInts())
    }

    @Test
    fun copyAbsoluteToRespectsDestinationOffset() {
        val buffer = createBuffer(5, 1, 2, 3, 4, 5, 6, 7)
        val destination = ShortArray(5) { -1 }

        val copied = buffer.copyAbsoluteTo(3, destination, destinationOffset = 1, count = 2)

        assertEquals(2, copied)
        assertArrayEquals(
            shortArrayOf(-1, 4, 5, -1, -1),
            destination,
        )
    }

    @Test
    fun copyAbsoluteToReturnsOnlyReallyCopiedSamples() {
        val buffer = createBuffer(5, 1, 2, 3, 4, 5, 6, 7)
        val destination = ShortArray(4)

        // Индексы 0..1 уже вытеснены: копируются только 3 и 4.
        assertEquals(2, buffer.copyAbsoluteTo(0, destination, 0, 4))
        assertArrayEquals(shortArrayOf(3, 4, 0, 0), destination)

        // За концом записанных данных копируется только доступное.
        assertEquals(2, buffer.copyAbsoluteTo(5, destination, 0, 4))

        // Начиная с последнего записанного сэмпла копировать нечего.
        assertEquals(0, buffer.copyAbsoluteTo(7, destination, 0, 2))
        assertEquals(7L, buffer.totalSamplesWritten)
        assertEquals(2L, buffer.oldestAvailableSample)
    }

    @Test
    fun copyAbsoluteRejectsInvalidArguments() {
        val buffer = createBuffer(5, 1, 2, 3)
        val destination = ShortArray(4)

        assertThrows(IllegalArgumentException::class.java) {
            buffer.copyAbsolute(-1, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            buffer.copyAbsolute(0, -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            buffer.copyAbsoluteTo(-1, destination)
        }
        assertThrows(IllegalArgumentException::class.java) {
            buffer.copyAbsoluteTo(0, destination, destinationOffset = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            buffer.copyAbsoluteTo(0, destination, count = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            buffer.copyAbsoluteTo(0, destination, destinationOffset = 2, count = 3)
        }
    }

    private fun createBuffer(capacity: Int, vararg values: Int): AudioRingBuffer {
        val buffer = AudioRingBuffer(capacity)
        buffer.write(values.asShorts())
        return buffer
    }

    private fun IntArray.asShorts(): ShortArray = ShortArray(size) { this[it].toShort() }

    private fun ShortArray.asInts(): IntArray = IntArray(size) { this[it].toInt() }
}
