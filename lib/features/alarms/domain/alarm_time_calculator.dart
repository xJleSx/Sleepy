import 'sleep_alarm.dart';

class AlarmTimeCalculator {
  const AlarmTimeCalculator();

  DateTime? nextTrigger(SleepAlarm alarm, DateTime now) {
    for (var offset = 0; offset <= 8; offset++) {
      final date = DateTime(
        now.year,
        now.month,
        now.day,
        alarm.hour,
        alarm.minute,
      ).add(Duration(days: offset));

      if (!date.isAfter(now)) continue;
      if (alarm.weekdays.isNotEmpty &&
          !alarm.weekdays.contains(date.weekday)) {
        continue;
      }

      return date;
    }

    return null;
  }

  DateTime nextTriggerOrThrow(SleepAlarm alarm, DateTime now) {
    final value = nextTrigger(alarm, now);
    if (value == null) {
      throw StateError('Не удалось вычислить время будильника');
    }
    return value;
  }
}
