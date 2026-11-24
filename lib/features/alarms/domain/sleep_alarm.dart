import 'alarm_sound.dart';
import 'smart_alarm_window.dart';

class SleepAlarm {
  const SleepAlarm({
    required this.id,
    required this.label,
    required this.hour,
    required this.minute,
    required this.weekdays,
    required this.enabled,
    required this.sound,
    required this.volume,
    required this.smart,
    this.scheduledFor,
  });

  final String id;
  final String label;
  final int hour;
  final int minute;

  /// 1 = понедельник ... 7 = воскресенье.
  /// Пустой набор означает однократный будильник.
  final Set<int> weekdays;
  final bool enabled;
  final AlarmSound sound;
  final double volume;
  final SmartAlarmWindow smart;
  final DateTime? scheduledFor;

  bool get isOneTime => weekdays.isEmpty;

  bool get isSmart => smart.enabled;

  SleepAlarm copyWith({
    String? label,
    int? hour,
    int? minute,
    Set<int>? weekdays,
    bool? enabled,
    AlarmSound? sound,
    double? volume,
    SmartAlarmWindow? smart,
    DateTime? scheduledFor,
    bool clearScheduledFor = false,
  }) {
    return SleepAlarm(
      id: id,
      label: label ?? this.label,
      hour: hour ?? this.hour,
      minute: minute ?? this.minute,
      weekdays: Set<int>.unmodifiable(weekdays ?? this.weekdays),
      enabled: enabled ?? this.enabled,
      sound: sound ?? this.sound,
      volume: volume ?? this.volume,
      smart: smart ?? this.smart,
      scheduledFor: clearScheduledFor
          ? null
          : (scheduledFor ?? this.scheduledFor),
    );
  }

  Map<String, Object?> toJson() {
    return <String, Object?>{
      'id': id,
      'label': label,
      'hour': hour,
      'minute': minute,
      'weekdays': weekdays.toList()..sort(),
      'enabled': enabled,
      'sound': sound.wireName,
      'volume': volume,
      'smart': smart.toJson(),
      'scheduledForMillis': scheduledFor?.millisecondsSinceEpoch,
    };
  }

  static SleepAlarm? tryParse(Object? raw) {
    if (raw is! Map) return null;

    final id = raw['id'];
    final label = raw['label'];
    final hour = raw['hour'];
    final minute = raw['minute'];
    final rawWeekdays = raw['weekdays'];
    final volume = raw['volume'];
    final rawScheduledFor = raw['scheduledForMillis'];

    if (id is! String || id.isEmpty) return null;
    if (label is! String || label.isEmpty) return null;
    if (hour is! num || hour < 0 || hour > 23) return null;
    if (minute is! num || minute < 0 || minute > 59) return null;
    if (rawWeekdays is! List) return null;

    final weekdays = <int>{};
    for (final value in rawWeekdays) {
      if (value is num && value >= 1 && value <= 7) {
        weekdays.add(value.toInt());
      }
    }

    return SleepAlarm(
      id: id,
      label: label,
      hour: hour.toInt(),
      minute: minute.toInt(),
      weekdays: Set<int>.unmodifiable(weekdays),
      enabled: raw['enabled'] == true,
      sound: AlarmSound.parse(raw['sound']),
      volume: volume is num ? volume.toDouble().clamp(0.0, 1.0) : 0.8,
      smart: SmartAlarmWindow.parse(raw['smart']),
      scheduledFor: rawScheduledFor is num && rawScheduledFor > 0
          ? DateTime.fromMillisecondsSinceEpoch(rawScheduledFor.toInt())
          : null,
    );
  }
}
