package com.sleepy.app.sleepy.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

data class AlarmScheduleResult(
    val scheduled: Boolean,
    val exact: Boolean,
    val triggerAtMillis: Long?,
    val message: String,
)

class SleepAlarmScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val store = SleepyAlarmStore(appContext)
    private val calculator = AlarmScheduleCalculator()

    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager?.canScheduleExactAlarms() == true
    }

    fun schedule(spec: AlarmSpec, triggerAtMillis: Long): AlarmScheduleResult {
        val manager = alarmManager
            ?: return AlarmScheduleResult(false, false, triggerAtMillis, "AlarmManager недоступен")

        if (triggerAtMillis <= System.currentTimeMillis()) {
            return AlarmScheduleResult(false, false, triggerAtMillis, "Время уже прошло")
        }

        val pendingIntent = pendingIntent(spec.id, spec.toJson())
        manager.cancel(pendingIntent)

        val exact = canScheduleExactAlarms()
        var exactApplied = exact

        try {
            if (exact) {
                try {
                    manager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(triggerAtMillis, null),
                        pendingIntent,
                    )
                } catch (_: SecurityException) {
                    exactApplied = false
                    setInexact(manager, triggerAtMillis, pendingIntent)
                }
            } else {
                setInexact(manager, triggerAtMillis, pendingIntent)
            }
        } catch (exception: SecurityException) {
            return AlarmScheduleResult(
                false,
                false,
                triggerAtMillis,
                exception.message ?: "Нет разрешения на будильник",
            )
        } catch (exception: Exception) {
            return AlarmScheduleResult(
                false,
                false,
                triggerAtMillis,
                exception.message ?: "Не удалось поставить будильник",
            )
        }

        store.save(spec)
        val message = if (exactApplied) {
            "Будильник поставлен на точное время"
        } else {
            "Нет разрешения на точный будильник: система может сдвинуть сигнал"
        }
        return AlarmScheduleResult(true, exactApplied, triggerAtMillis, message)
    }

    fun cancel(alarmId: String) {
        alarmManager?.cancel(pendingIntent(alarmId))
        store.remove(alarmId)
    }

    fun rescheduleAll(nowMillis: Long = System.currentTimeMillis()) {
        for (spec in store.all()) {
            val next = calculator.nextTrigger(spec, nowMillis)
            if (next == null) {
                cancel(spec.id)
            } else {
                schedule(spec, next)
            }
        }
    }

    fun rescheduleAfterFire(spec: AlarmSpec) {
        if (spec.weekdays.isEmpty()) {
            store.remove(spec.id)
            return
        }

        val next = calculator.nextTrigger(spec, System.currentTimeMillis())
        if (next == null) {
            store.remove(spec.id)
        } else {
            schedule(spec, next)
        }
    }

    @Suppress("DEPRECATION")
    private fun setInexact(
        manager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        } else {
            manager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun pendingIntent(
        alarmId: String,
        specJson: String? = null,
    ): PendingIntent {
        val intent = Intent(appContext, SleepAlarmReceiver::class.java).apply {
            action = SleepAlarmReceiver.ACTION_FIRE
            putExtra(SleepAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            if (specJson != null) {
                putExtra(SleepAlarmReceiver.EXTRA_SPEC, specJson)
            }
        }
        return PendingIntent.getBroadcast(
            appContext,
            alarmId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
