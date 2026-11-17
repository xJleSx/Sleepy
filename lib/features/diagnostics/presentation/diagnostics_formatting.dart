import '../../events/domain/recording_status.dart';
import '../../events/presentation/event_formatting.dart';
import '../domain/diagnostic_audio_file.dart';

const String missingValue = '—';

/// dBFS с явной пометкой отсутствия полезного сигнала.
///
/// `-120` в приложении — нижняя граница детектора, а не обычная тишина
/// комнаты. Показывать её как рядовое значение нельзя.
String formatDbfs(double? value) {
  if (value == null) return missingValue;
  if (value <= -119.999) return 'нет сигнала';

  final text = value.toStringAsFixed(1).replaceFirst('-', '−');
  return '$text дБFS';
}

String formatSessionState(RecordingStatus status) {
  if (status.isRecording) return 'Запись активна';
  if (status.hasError) return 'Ошибка записи';
  if (status.interrupted) return 'Запись прервана';
  return 'Запись остановлена';
}

Duration? sessionDuration(RecordingStatus status, DateTime now) {
  final startedAt = status.startedAt;
  if (startedAt == null) return null;

  if (status.isRecording) {
    final elapsed = now.difference(startedAt);
    return elapsed.isNegative ? Duration.zero : elapsed;
  }

  final endedAt = status.endedAt;
  if (endedAt == null) return null;

  final duration = endedAt.difference(startedAt);
  return duration.isNegative ? Duration.zero : duration;
}

String formatSessionDuration(RecordingStatus status, DateTime now) {
  final duration = sessionDuration(status, now);
  return duration == null ? missingValue : formatElapsed(duration);
}

String formatDetectorState(String? state) {
  if (state == null || state.isEmpty) return missingValue;

  switch (state) {
    case 'WARMING_UP':
      return 'WARMING_UP · прогрев';
    case 'QUIET':
      return 'QUIET · тишина';
    case 'CANDIDATE':
      return 'CANDIDATE · кандидат';
    case 'ACTIVE':
      return 'ACTIVE · событие';
    default:
      return state;
  }
}

String formatWavStatus(WavFileStatus status) {
  switch (status) {
    case WavFileStatus.valid:
      return 'Корректный';
    case WavFileStatus.empty:
      return 'Пустой';
    case WavFileStatus.corrupted:
      return 'Повреждённый';
    case WavFileStatus.failed:
      return 'Не прошёл проверку';
  }
}

String formatFileDate(DateTime? time) =>
    time == null ? missingValue : formatEventTimestamp(time);

String formatFileDuration(Duration? duration) =>
    duration == null ? missingValue : formatDuration(duration, roundUp: true);

String formatFileSize(int? bytes) =>
    bytes == null ? missingValue : formatBytes(bytes);

String formatOptionalCount(int? value) => value?.toString() ?? missingValue;

String fileNameFromPath(String? path) {
  if (path == null || path.isEmpty) return missingValue;

  final parts = path.split(RegExp(r'[/\\]'));
  final name = parts.isEmpty ? path : parts.last;
  return name.isEmpty ? missingValue : name;
}
