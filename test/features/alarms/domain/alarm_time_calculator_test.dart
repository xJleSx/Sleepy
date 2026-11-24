import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/features/alarms/domain/alarm_sound.dart';
import 'package:sleepy/features/alarms/domain/alarm_time_calculator.dart';
import 'package:sleepy/features/alarms/domain/sleep_alarm.dart';
import 'package:sleepy/features/alarms/domain/smart_alarm_window.dart';

void main() {
  const calculator = AlarmTimeCalculator();

  test('finds a later time today for a daily alarm', () {
    final alarm = _alarm(
      hour: 7,
      minute: 30,
      weekdays: const <int>{1, 2, 3, 4, 5, 6, 7},
    );

    final next = calculator.nextTrigger(alarm, DateTime(2026, 11, 24, 6));

    expect(next, DateTime(2026, 11, 24, 7, 30));
  });

  test('finds the next selected weekday', () {
    final alarm = _alarm(hour: 7, minute: 30, weekdays: const <int>{1});

    final next = calculator.nextTrigger(alarm, DateTime(2026, 11, 24, 10));

    expect(next, DateTime(2026, 11, 30, 7, 30));
  });

  test('uses tomorrow when today has already passed', () {
    final alarm = _alarm(hour: 7, minute: 30, weekdays: const <int>{});

    final next = calculator.nextTrigger(alarm, DateTime(2026, 11, 24, 8));

    expect(next, DateTime(2026, 11, 25, 7, 30));
  });

  test('skips to the next week after a missed weekday', () {
    final alarm = _alarm(hour: 7, minute: 0, weekdays: const <int>{1});

    final next = calculator.nextTrigger(alarm, DateTime(2026, 11, 30, 8));

    expect(next, DateTime(2026, 12, 7, 7));
  });
}

SleepAlarm _alarm({
  required int hour,
  required int minute,
  required Set<int> weekdays,
}) {
  return SleepAlarm(
    id: 'alarm',
    label: 'Будильник',
    hour: hour,
    minute: minute,
    weekdays: weekdays,
    enabled: true,
    sound: AlarmSound.systemAlarm,
    volume: 0.8,
    smart: const SmartAlarmWindow(),
  );
}
