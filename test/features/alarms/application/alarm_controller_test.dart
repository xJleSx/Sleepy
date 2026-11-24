import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/features/alarms/application/alarm_controller.dart';
import 'package:sleepy/features/alarms/data/alarm_repository.dart';
import 'package:sleepy/features/alarms/data/alarm_scheduler.dart';
import 'package:sleepy/features/alarms/domain/alarm_sound.dart';
import 'package:sleepy/features/alarms/domain/sleep_alarm.dart';
import 'package:sleepy/features/alarms/domain/smart_alarm_window.dart';

void main() {
  final now = DateTime(2026, 11, 24, 6);

  test('loads, schedules and persists enabled alarms', () async {
    final repository = FakeAlarmRepository(<SleepAlarm>[_alarm()]);
    final scheduler = FakeAlarmScheduler(exact: true);
    final controller = AlarmController(
      repository: repository,
      scheduler: scheduler,
      now: () => now,
    );

    await controller.load();

    expect(controller.isLoaded, isTrue);
    expect(controller.exactAllowed, isTrue);
    expect(controller.error, isNull);
    expect(scheduler.scheduled, hasLength(1));
    expect(controller.alarms.single.scheduledFor, DateTime(2026, 11, 24, 7));
    expect(repository.saved.single.scheduledFor, DateTime(2026, 11, 24, 7));
  });

  test('saves and cancels alarms through the scheduler', () async {
    final repository = FakeAlarmRepository(const <SleepAlarm>[]);
    final scheduler = FakeAlarmScheduler(exact: false);
    final controller = AlarmController(
      repository: repository,
      scheduler: scheduler,
      now: () => now,
    );

    await controller.load();
    final alarm = _alarm();
    final note = await controller.saveAlarm(alarm);

    expect(note, contains('сдвинуть'));
    expect(controller.exactAllowed, isFalse);
    expect(repository.saved, hasLength(1));

    await controller.setEnabled(alarm, false);

    expect(scheduler.cancelled, contains(alarm.id));
    expect(controller.alarms.single.enabled, isFalse);
  });

  test('disables and cancels an expired one-time alarm', () async {
    final expired = SleepAlarm(
      id: 'one_time',
      label: 'Один раз',
      hour: 7,
      minute: 0,
      weekdays: const <int>{},
      enabled: true,
      sound: AlarmSound.systemAlarm,
      volume: 0.8,
      smart: const SmartAlarmWindow(),
      scheduledFor: now.subtract(const Duration(hours: 1)),
    );
    final repository = FakeAlarmRepository(<SleepAlarm>[expired]);
    final scheduler = FakeAlarmScheduler(exact: true);
    final controller = AlarmController(
      repository: repository,
      scheduler: scheduler,
      now: () => now,
    );

    await controller.load();

    expect(controller.alarms.single.enabled, isFalse);
    expect(controller.alarms.single.scheduledFor, isNull);
    expect(scheduler.cancelled, contains('one_time'));
    expect(repository.saved.single.enabled, isFalse);
  });

  test('surfaces repository errors without inventing alarms', () async {
    final controller = AlarmController(
      repository: FakeAlarmRepository(
        const <SleepAlarm>[],
        error: const AlarmRepositoryException('Файл повреждён'),
      ),
      scheduler: FakeAlarmScheduler(exact: true),
      now: () => now,
    );

    await controller.load();

    expect(controller.error, 'Файл повреждён');
    expect(controller.alarms, isEmpty);
  });
}

SleepAlarm _alarm() {
  return SleepAlarm(
    id: 'alarm_1',
    label: 'На работу',
    hour: 7,
    minute: 0,
    weekdays: const <int>{1, 2, 3, 4, 5},
    enabled: true,
    sound: AlarmSound.systemAlarm,
    volume: 0.8,
    smart: const SmartAlarmWindow(),
  );
}

class FakeAlarmRepository implements AlarmRepository {
  FakeAlarmRepository(this.initial, {this.error});

  final List<SleepAlarm> initial;
  final AlarmRepositoryException? error;
  List<SleepAlarm> saved = <SleepAlarm>[];

  @override
  Future<List<SleepAlarm>> load() async {
    if (error != null) throw error!;
    return List<SleepAlarm>.of(initial);
  }

  @override
  Future<void> save(List<SleepAlarm> alarms) async {
    saved = List<SleepAlarm>.of(alarms);
  }
}

class FakeAlarmScheduler implements AlarmScheduler {
  FakeAlarmScheduler({required this.exact});

  final bool exact;
  final List<SleepAlarm> scheduled = <SleepAlarm>[];
  final List<String> cancelled = <String>[];

  @override
  Future<bool> canScheduleExactAlarms() async => exact;

  @override
  Future<void> requestExactAlarmPermission() async {}

  @override
  Future<void> cancel(String alarmId) async {
    cancelled.add(alarmId);
  }

  @override
  Future<AlarmScheduleResult> schedule(
    SleepAlarm alarm,
    DateTime triggerAt,
  ) async {
    scheduled.add(alarm);
    return AlarmScheduleResult(
      scheduled: true,
      exact: exact,
      triggerAt: triggerAt,
      message: exact ? '' : 'Android не разрешил точный будильник',
    );
  }
}
