import 'package:flutter/foundation.dart';

import '../data/alarm_repository.dart';
import '../data/alarm_scheduler.dart';
import '../domain/alarm_time_calculator.dart';
import '../domain/sleep_alarm.dart';

class AlarmController extends ChangeNotifier {
  AlarmController({
    required this._repository,
    required this._scheduler,
    DateTime Function()? now,
    this._calculator = const AlarmTimeCalculator(),
  }) : _now = now ?? DateTime.now;

  final AlarmRepository _repository;
  final AlarmScheduler _scheduler;
  final DateTime Function() _now;
  final AlarmTimeCalculator _calculator;

  List<SleepAlarm> _alarms = const <SleepAlarm>[];
  final Map<String, String> _scheduleNotes = <String, String>{};
  bool _loading = false;
  bool _loaded = false;
  String? _error;
  bool _exactAllowed = false;

  List<SleepAlarm> get alarms => _alarms;
  bool get isLoading => _loading;
  bool get isLoaded => _loaded;
  String? get error => _error;
  bool get exactAllowed => _exactAllowed;

  String? scheduleNote(String alarmId) => _scheduleNotes[alarmId];

  DateTime? nextTriggerFor(SleepAlarm alarm) =>
      _calculator.nextTrigger(alarm, _now());

  Future<void> load({bool force = false}) async {
    if (_loading || (_loaded && !force)) return;

    _loading = true;
    _error = null;
    notifyListeners();

    try {
      _alarms = await _repository.load();
      _exactAllowed = await _scheduler.canScheduleExactAlarms();
      await _disableExpiredOneTimeAlarms();
      _loaded = true;
      await _rescheduleEnabled();
    } on AlarmRepositoryException catch (exception) {
      _error = exception.message;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<String?> saveAlarm(SleepAlarm alarm) async {
    final next = <SleepAlarm>[
      ..._alarms.where((item) => item.id != alarm.id),
      alarm,
    ]..sort(_byTime);

    await _persist(next);
    final note = await _applySchedule(alarm);
    notifyListeners();
    return note;
  }

  Future<void> setEnabled(SleepAlarm alarm, bool enabled) async {
    await saveAlarm(
      alarm.copyWith(
        enabled: enabled,
        clearScheduledFor: !enabled,
      ),
    );
  }

  Future<void> deleteAlarm(String alarmId) async {
    final next = _alarms.where((item) => item.id != alarmId).toList();
    await _persist(next);

    try {
      await _scheduler.cancel(alarmId);
      _scheduleNotes.remove(alarmId);
    } on AlarmSchedulerException catch (exception) {
      _scheduleNotes[alarmId] = exception.message;
    }

    notifyListeners();
  }

  Future<void> reload() => load(force: true);

  Future<void> requestExactAlarmPermission() async {
    try {
      await _scheduler.requestExactAlarmPermission();
    } on AlarmSchedulerException catch (exception) {
      _error = exception.message;
      notifyListeners();
      rethrow;
    }
  }

  Future<void> _rescheduleEnabled() async {
    final updated = <SleepAlarm>[];

    for (final alarm in _alarms) {
      if (!alarm.enabled) {
        updated.add(alarm);
        continue;
      }

      final trigger = _calculator.nextTrigger(alarm, _now());
      if (trigger == null) {
        updated.add(alarm.copyWith(enabled: false));
        _scheduleNotes[alarm.id] = 'Не удалось вычислить время';
        continue;
      }

      try {
        final result = await _scheduler.schedule(alarm, trigger);
        updated.add(alarm.copyWith(scheduledFor: result.triggerAt));
        _scheduleNotes[alarm.id] = result.exact
            ? ''
            : 'Android не разрешил точный будильник: время может сдвинуться.';
        _exactAllowed = result.exact;
      } on AlarmSchedulerException catch (exception) {
        updated.add(alarm);
        _scheduleNotes[alarm.id] = exception.message;
      }
    }

    if (!listEquals(updated, _alarms)) {
      _alarms = List<SleepAlarm>.unmodifiable(updated);
      await _persist(_alarms);
    }
  }

  Future<String?> _applySchedule(SleepAlarm alarm) async {
    if (!alarm.enabled) {
      try {
        await _scheduler.cancel(alarm.id);
        _scheduleNotes.remove(alarm.id);
      } on AlarmSchedulerException catch (exception) {
        _scheduleNotes[alarm.id] = exception.message;
      }
      return _scheduleNotes[alarm.id];
    }

    final trigger = _calculator.nextTrigger(alarm, _now());
    if (trigger == null) {
      const note = 'Не удалось вычислить время будильника';
      _scheduleNotes[alarm.id] = note;
      return note;
    }

    try {
      final result = await _scheduler.schedule(alarm, trigger);
      _exactAllowed = result.exact;
      final note = result.exact
          ? ''
          : 'Android не разрешил точный будильник: время может сдвинуться.';
      _scheduleNotes[alarm.id] = note;

      final scheduledAlarm = alarm.copyWith(scheduledFor: result.triggerAt);
      final next = <SleepAlarm>[
        ..._alarms.where((item) => item.id != alarm.id),
        scheduledAlarm,
      ]..sort(_byTime);
      await _persist(next);
      return note;
    } on AlarmSchedulerException catch (exception) {
      _scheduleNotes[alarm.id] = exception.message;
      return exception.message;
    }
  }

  Future<void> _persist(List<SleepAlarm> alarms) async {
    final immutable = List<SleepAlarm>.unmodifiable(alarms);
    try {
      await _repository.save(immutable);
      _alarms = immutable;
      _error = null;
    } on AlarmRepositoryException catch (exception) {
      _error = exception.message;
      rethrow;
    }
  }

  Future<void> _disableExpiredOneTimeAlarms() async {
    final now = _now();
    final changed = <SleepAlarm>[];
    final expiredIds = <String>[];

    for (final alarm in _alarms) {
      final scheduled = alarm.scheduledFor;
      final expired =
          alarm.enabled &&
          alarm.isOneTime &&
          scheduled != null &&
          !scheduled.isAfter(now);

      if (expired) {
        expiredIds.add(alarm.id);
        changed.add(alarm.copyWith(enabled: false, clearScheduledFor: true));
      } else {
        changed.add(alarm);
      }
    }

    if (expiredIds.isEmpty) return;

    _alarms = List<SleepAlarm>.unmodifiable(changed);
    for (final alarmId in expiredIds) {
      try {
        await _scheduler.cancel(alarmId);
        _scheduleNotes.remove(alarmId);
      } on AlarmSchedulerException catch (exception) {
        _scheduleNotes[alarmId] = exception.message;
      }
    }
    await _persist(_alarms);
  }

  static int _byTime(SleepAlarm left, SleepAlarm right) {
    final leftMinute = left.hour * 60 + left.minute;
    final rightMinute = right.hour * 60 + right.minute;
    final byTime = leftMinute.compareTo(rightMinute);
    if (byTime != 0) return byTime;
    return left.label.compareTo(right.label);
  }
}
