import '../domain/sleep_alarm.dart';

const List<String> _shortDays = <String>[
  'Пн',
  'Вт',
  'Ср',
  'Чт',
  'Пт',
  'Сб',
  'Вс',
];

String alarmTimeLabel(SleepAlarm alarm) {
  final hour = alarm.hour.toString().padLeft(2, '0');
  final minute = alarm.minute.toString().padLeft(2, '0');
  return '$hour:$minute';
}

String alarmDaysLabel(SleepAlarm alarm) {
  if (alarm.weekdays.isEmpty) return 'Один раз';

  final days = alarm.weekdays.toList()..sort();
  if (days.length == 7) return 'Каждый день';
  if (days.length == 5 && days.every((day) => day <= 5)) {
    return 'По будням';
  }
  if (days.length == 2 && days.contains(6) && days.contains(7)) {
    return 'По выходным';
  }

  return days.map((day) => _shortDays[day - 1]).join(', ');
}

String minuteOfDayLabel(int minute) {
  final normalized = ((minute % 1440) + 1440) % 1440;
  final hour = (normalized ~/ 60).toString().padLeft(2, '0');
  final rest = (normalized % 60).toString().padLeft(2, '0');
  return '$hour:$rest';
}

String alarmWindowLabel(SleepAlarm alarm) {
  if (!alarm.smart.enabled) return 'Выключено';
  return '${minuteOfDayLabel(alarm.smart.startMinute)}–'
      '${minuteOfDayLabel(alarm.smart.endMinute)}';
}
