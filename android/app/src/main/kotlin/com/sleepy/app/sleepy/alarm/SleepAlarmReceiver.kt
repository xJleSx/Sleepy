package com.sleepy.app.sleepy.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class SleepAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return

        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID).orEmpty()
        val spec = AlarmSpec.fromJson(intent.getStringExtra(EXTRA_SPEC))
            ?: SleepyAlarmStore(context).get(alarmId)
            ?: return

        val playback = Intent(context, AlarmPlaybackService::class.java).apply {
            action = AlarmPlaybackService.ACTION_START
            putExtra(EXTRA_SPEC, spec.toJson())
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(playback)
            } else {
                context.startService(playback)
            }
        } catch (_: Exception) {
        }

        SleepAlarmScheduler(context).rescheduleAfterFire(spec)
    }

    companion object {
        const val ACTION_FIRE = "com.sleepy.app.sleepy.alarm.FIRE"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_SPEC = "alarm_spec"
    }
}
