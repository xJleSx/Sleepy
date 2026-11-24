class SmartAlarmWindow {
  const SmartAlarmWindow({
    this.enabled = false,
    this.startMinute = 390,
    this.endMinute = 420,
  });

  final bool enabled;

  /// Минуты от полуночи. Окно задаётся в пределах текущих суток.
  final int startMinute;

  /// Минуты от полуночи. Должно быть позже [startMinute].
  final int endMinute;

  SmartAlarmWindow copyWith({
    bool? enabled,
    int? startMinute,
    int? endMinute,
  }) {
    return SmartAlarmWindow(
      enabled: enabled ?? this.enabled,
      startMinute: startMinute ?? this.startMinute,
      endMinute: endMinute ?? this.endMinute,
    );
  }

  Map<String, Object?> toJson() {
    return <String, Object?>{
      'enabled': enabled,
      'startMinute': startMinute,
      'endMinute': endMinute,
    };
  }

  static SmartAlarmWindow parse(Object? raw) {
    if (raw is! Map) return const SmartAlarmWindow();
    final start = raw['startMinute'];
    final end = raw['endMinute'];

    return SmartAlarmWindow(
      enabled: raw['enabled'] == true,
      startMinute: _validMinute(start) ? start!.toInt() : 390,
      endMinute: _validMinute(end) ? end!.toInt() : 420,
    );
  }

  static bool _validMinute(Object? value) {
    return value is num && value >= 0 && value < 1440;
  }
}
