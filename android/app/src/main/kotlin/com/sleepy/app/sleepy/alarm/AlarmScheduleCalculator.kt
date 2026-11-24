package com.sleepy.app.sleepy.alarm

import java.util.Calendar

class AlarmScheduleCalculator {
    fun nextTrigger(spec: AlarmSpec, nowMillis: Long): Long? {
        val calendar = Calendar.getInstance()
        for (offset in 0..8) {
            calendar.timeInMillis = nowMillis
            calendar.add(Calendar.DAY_OF_YEAR, offset)
            calendar.set(Calendar.HOUR_OF_DAY, spec.hour)
            calendar.set(Calendar.MINUTE, spec.minute)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)

            val candidate = calendar.timeInMillis
            if (candidate <= nowMillis) continue
            if (spec.weekdays.isNotEmpty() && isoWeekday(calendar) !in spec.weekdays) {
                continue
            }
            return candidate
        }
        return null
    }

    private fun isoWeekday(calendar: Calendar): Int {
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> 7
            else -> calendar.get(Calendar.DAY_OF_WEEK) - 1
        }
    }
}
