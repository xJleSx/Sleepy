// Форматирование дат, длительностей и размеров без внешних зависимостей.

String _twoDigits(int value) => value.toString().padLeft(2, '0');

String formatClock(DateTime time) =>
    '${_twoDigits(time.hour)}:${_twoDigits(time.minute)}:${_twoDigits(time.second)}';

/// Дата и время с точностью до секунды: `15.11.2026 · 23:41:07`.
String formatEventTimestamp(DateTime time) =>
    '${_twoDigits(time.day)}.${_twoDigits(time.month)}.${time.year} · ${formatClock(time)}';

/// Дата и время без секунд: `15.11.2026, 23:41`.
String formatDateTime(DateTime time) =>
    '${_twoDigits(time.day)}.${_twoDigits(time.month)}.${time.year}, '
    '${_twoDigits(time.hour)}:${_twoDigits(time.minute)}';

/// Длительность в виде `м:сс` или `ч:мм:сс`.
///
/// Для карточки фрагмента используйте [roundUp], чтобы очень короткое
/// событие не показывалось как `0:00`.
String formatDuration(Duration duration, {bool roundUp = false}) {
  var seconds = roundUp
      ? (duration.inMilliseconds / 1000).ceil()
      : duration.inSeconds;

  if (roundUp && seconds <= 0 && duration > Duration.zero) {
    seconds = 1;
  }

  if (seconds < 0) seconds = 0;

  final hours = seconds ~/ 3600;
  final minutes = (seconds % 3600) ~/ 60;
  final rest = seconds % 60;

  if (hours > 0) {
    return '$hours:${_twoDigits(minutes)}:${_twoDigits(rest)}';
  }

  return '$minutes:${_twoDigits(rest)}';
}

/// Длительность мониторинга и позиция плеера: `чч:мм:сс`.
String formatElapsed(Duration duration) {
  final total = duration.isNegative ? 0 : duration.inSeconds;
  final hours = _twoDigits(total ~/ 3600);
  final minutes = _twoDigits((total % 3600) ~/ 60);
  final seconds = _twoDigits(total % 60);

  return '$hours:$minutes:$seconds';
}

/// Размер файла: `980 Б`, `62 КБ`, `1.4 МБ`.
String formatBytes(int bytes) {
  if (bytes < 1024) return '$bytes Б';

  final kilobytes = bytes / 1024;
  if (kilobytes < 1024) return '${kilobytes.round()} КБ';

  final megabytes = kilobytes / 1024;
  return '${megabytes.toStringAsFixed(1)} МБ';
}
