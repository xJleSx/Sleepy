enum AlarmSound {
  systemAlarm('Системный будильник', 'alarm'),
  systemNotification('Мелодия уведомлений', 'notification');

  const AlarmSound(this.label, this.wireName);

  final String label;
  final String wireName;

  static AlarmSound parse(Object? raw) {
    if (raw == 'notification') return AlarmSound.systemNotification;
    return AlarmSound.systemAlarm;
  }
}
