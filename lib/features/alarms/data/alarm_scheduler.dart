import 'package:flutter/services.dart';

import '../domain/sleep_alarm.dart';

class AlarmSchedulerException implements Exception {
  const AlarmSchedulerException(this.message);

  final String message;

  @override
  String toString() => message;
}

class AlarmScheduleResult {
  const AlarmScheduleResult({
    required this.scheduled,
    required this.exact,
    required this.triggerAt,
    required this.message,
  });

  final bool scheduled;
  final bool exact;
  final DateTime? triggerAt;
  final String message;
}

abstract class AlarmScheduler {
  Future<AlarmScheduleResult> schedule(SleepAlarm alarm, DateTime triggerAt);

  Future<void> cancel(String alarmId);

  Future<bool> canScheduleExactAlarms();

  Future<void> requestExactAlarmPermission() async {}
}

class ChannelAlarmScheduler implements AlarmScheduler {
  ChannelAlarmScheduler({MethodChannel? channel})
    : _channel = channel ?? const MethodChannel('com.sleepy.app.sleepy/alarm');

  final MethodChannel _channel;

  @override
  Future<AlarmScheduleResult> schedule(
    SleepAlarm alarm,
    DateTime triggerAt,
  ) async {
    try {
      final raw = await _channel.invokeMapMethod<String, dynamic>(
        'scheduleAlarm',
        <String, Object?>{
          'id': alarm.id,
          'label': alarm.label,
          'hour': alarm.hour,
          'minute': alarm.minute,
          'weekdays': alarm.weekdays.toList()..sort(),
          'sound': alarm.sound.wireName,
          'volume': alarm.volume,
          'triggerAtMillis': triggerAt.millisecondsSinceEpoch,
          'smartEnabled': alarm.smart.enabled,
          'smartStartMinute': alarm.smart.startMinute,
          'smartEndMinute': alarm.smart.endMinute,
        },
      );

      final map = raw ?? const <String, dynamic>{};
      final scheduled = map['scheduled'] == true;
      if (!scheduled) {
        throw AlarmSchedulerException(
          map['message'] as String? ?? 'Не удалось запланировать будильник',
        );
      }

      final triggerMillis = map['triggerAtMillis'];
      return AlarmScheduleResult(
        scheduled: true,
        exact: map['exact'] == true,
        triggerAt: triggerMillis is num && triggerMillis > 0
            ? DateTime.fromMillisecondsSinceEpoch(triggerMillis.toInt())
            : triggerAt,
        message: map['message'] as String? ?? '',
      );
    } on MissingPluginException {
      throw const AlarmSchedulerException('Канал будильника недоступен');
    } on PlatformException catch (exception) {
      throw AlarmSchedulerException(
        exception.message ?? 'Не удалось запланировать будильник',
      );
    }
  }

  @override
  Future<void> cancel(String alarmId) async {
    try {
      await _channel.invokeMethod<void>('cancelAlarm', <String, Object>{
        'id': alarmId,
      });
    } on MissingPluginException {
      throw const AlarmSchedulerException('Канал будильника недоступен');
    } on PlatformException catch (exception) {
      throw AlarmSchedulerException(
        exception.message ?? 'Не удалось отменить будильник',
      );
    }
  }

  @override
  Future<bool> canScheduleExactAlarms() async {
    try {
      return await _channel.invokeMethod<bool>('canScheduleExactAlarms') ??
          false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  @override
  Future<void> requestExactAlarmPermission() async {
    try {
      await _channel.invokeMethod<void>('requestExactAlarmPermission');
    } on MissingPluginException {
      throw const AlarmSchedulerException('Канал будильника недоступен');
    } on PlatformException catch (exception) {
      throw AlarmSchedulerException(
        exception.message ?? 'Не удалось открыть настройки будильника',
      );
    }
  }
}
