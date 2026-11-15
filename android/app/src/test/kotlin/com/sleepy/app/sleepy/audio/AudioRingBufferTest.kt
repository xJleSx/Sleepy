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

    private fun createBuffer(capacity: Int, vararg values: Int): AudioRingBuffer {
        val buffer = AudioRingBuffer(capacity)
        buffer.write(values.asShorts())
        return buffer
    }

    private fun IntArray.asShorts(): ShortArray = ShortArray(size) { this[it].toShort() }

    private fun ShortArray.asInts(): IntArray = IntArray(size) { this[it].toInt() }
}
