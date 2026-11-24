package com.sleepy.app.sleepy.alarm

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmScheduleCalculatorTest {
    private val calculator = AlarmScheduleCalculator()

    @Test
    fun schedulesLaterTodayWhenTimeHasNotPassed() {
        val now = millis(2026, Calendar.NOVEMBER, 24, 6, 0)
        val spec = spec(hour = 7, minute = 30, weekdays = setOf(1, 2, 3, 4, 5, 6, 7))

        val next = calculator.nextTrigger(spec, now)

        assertParts(next, 2026, Calendar.NOVEMBER, 24, 7, 30)
    }

    @Test
    fun movesToNextSelectedWeekday() {
        val now = millis(2026, Calendar.NOVEMBER, 24, 10, 0)
        val spec = spec(hour = 7, minute = 30, weekdays = setOf(1))

        val next = calculator.nextTrigger(spec, now)

        assertParts(next, 2026, Calendar.NOVEMBER, 30, 7, 30)
    }

    @Test
    fun returnsNullWhenNoWeekdayCanBeReached() {
        val spec = spec(hour = 7, minute = 30, weekdays = setOf(8))

        assertNull(calculator.nextTrigger(spec, millis(2026, Calendar.NOVEMBER, 24, 6, 0)))
    }

    private fun spec(hour: Int, minute: Int, weekdays: Set<Int>): AlarmSpec {
        return AlarmSpec(
            id = "test",
            label = "Test",
            hour = hour,
            minute = minute,
            weekdays = weekdays,
            sound = "alarm",
            volume = 0.8f,
            smartEnabled = false,
            smartStartMinute = 390,
            smartEndMinute = 420,
        )
    }

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long {
        val calendar = Calendar.getInstance()
        calendar.set(year, month, day, hour, minute, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun assertParts(
        actual: Long?,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ) {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = requireNotNull(actual)
        assertEquals(year, calendar.get(Calendar.YEAR))
        assertEquals(month, calendar.get(Calendar.MONTH))
        assertEquals(day, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(hour, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(minute, calendar.get(Calendar.MINUTE))
    }
}
