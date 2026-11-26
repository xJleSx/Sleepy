import '../../events/presentation/event_formatting.dart';
import '../domain/diagnostic_audio_file.dart';
import '../domain/diagnostic_report.dart';
import 'diagnostics_formatting.dart';

const int reportFileLimit = 10;

/// Читаемый текстовый отчёт без аудиоданных и абсолютных путей.
String buildDiagnosticReport(DiagnosticReport report) {
  final status = report.status;
  final buffer = StringBuffer();

  buffer.writeln('Sleepy · диагностика аудиозаписи');
  buffer.writeln('Версия приложения: ${report.appVersion}');
  buffer.writeln('Отчёт создан: ${formatDateTime(report.generatedAt)}');
  buffer.writeln(
    'Последнее обновление диагностики: '
    '${report.lastUpdatedAt == null ? missingValue : formatDateTime(report.lastUpdatedAt!)}',
  );
  buffer.writeln();

  buffer.writeln('СЕССИЯ');
  buffer.writeln('Состояние: ${formatSessionState(status)}');
  buffer.writeln(
    'Длительность: ${formatSessionDuration(status, report.generatedAt)}',
  );
  buffer.writeln(
    'Показатели захвата, детектора и writer относятся к этой сессии.',
  );
  buffer.writeln('Последняя ошибка: ${_textOrNone(status.error)}');
  buffer.writeln(
    'Последнее предупреждение: ${_textOrNone(status.lastWarning)}',
  );
  buffer.writeln();

  buffer.writeln('ЗАХВАТ АУДИО · СЕССИЯ');
  buffer.writeln('samplesRead: ${formatOptionalCount(status.samplesRead)}');
  buffer.writeln(
    'readErrorCount: ${formatOptionalCount(status.readErrorCount)}',
  );
  buffer.writeln('lastChunkDbfs: ${formatDbfs(status.lastChunkDbfs)}');
  buffer.writeln();

  buffer.writeln('ДЕТЕКТОР · СЕССИЯ');
  buffer.writeln('recordingProfile: ${status.recordingProfile}');
  buffer.writeln('diagnosticProfile: ${status.diagnosticProfile}');
  buffer.writeln(
    'currentDetectorState: ${formatDetectorState(status.currentDetectorState)}',
  );
  buffer.writeln('noiseFloorDbfs: ${formatDbfs(status.noiseFloorDbfs)}');
  buffer.writeln(
    'candidateCount: ${formatOptionalCount(status.candidateCount)}',
  );
  buffer.writeln(
    'confirmedEventCount: '
    '${formatOptionalCount(status.confirmedEventCount)}',
  );
  buffer.writeln(
    'weakCandidateCount: ${formatOptionalCount(status.weakCandidateCount)}',
  );
  buffer.writeln(
    'weakSavedCount: ${formatOptionalCount(status.weakSavedCount)}',
  );
  buffer.writeln(
    'weakRejectedCount: ${formatOptionalCount(status.weakRejectedCount)}',
  );
  buffer.writeln('lastWeakRejection: ${_textOrNone(status.lastWeakRejection)}');
  buffer.writeln();

  buffer.writeln('ЗАПИСЬ ФАЙЛОВ · СЕССИЯ');
  buffer.writeln(
    'writerOpenCount: ${formatOptionalCount(status.writerOpenCount)}',
  );
  buffer.writeln(
    'writerSavedCount: ${formatOptionalCount(status.writerSavedCount)}',
  );
  buffer.writeln(
    'writerFailedCount: ${formatOptionalCount(status.writerFailedCount)}',
  );
  buffer.writeln('lastWriterError: ${_textOrNone(status.lastWriterError)}');
  buffer.writeln(
    'lastFragmentPath: ${fileNameFromPath(status.lastFragmentPath)}',
  );
  buffer.writeln();

  buffer.writeln('СТАТИСТИКА · НАКОПИТЕЛЬНО');
  buffer.writeln('eventCount: ${status.eventCount}');
  buffer.writeln('fragmentCount: ${status.fragmentCount}');
  buffer.writeln('WAV в каталоге: ${report.totalFiles}');
  buffer.writeln('Доступно WAV: ${report.availableWavCount}');
  buffer.writeln(
    'Корректные: ${report.validFileCount} · '
    'Пустые: ${report.emptyFileCount} · '
    'Повреждённые: ${report.corruptedFileCount} · '
    'Не прошли проверку: ${report.failedFileCount}',
  );
  buffer.writeln();

  buffer.writeln('ПОСЛЕДНИЕ WAV');
  if (report.files.isEmpty) {
    buffer.writeln('Файлов пока нет.');
  } else {
    final sorted = List<DiagnosticAudioFile>.of(report.files)
      ..sort(_newestFirst);
    final shown = sorted.take(reportFileLimit).toList();
    buffer.writeln('Показано ${shown.length} из ${report.files.length}.');
    buffer.writeln();

    for (var index = 0; index < shown.length; index++) {
      final file = shown[index];
      buffer.writeln('${index + 1}. ${file.fileName}');
      buffer.writeln('   Дата: ${formatFileDate(file.startedAt)}');
      buffer.writeln('   Длительность: ${formatFileDuration(file.duration)}');
      buffer.writeln('   Размер: ${formatFileSize(file.sizeBytes)}');
      buffer.writeln('   Проверка: ${formatWavStatus(file.status)}');
      if (file.reason.isNotEmpty) {
        buffer.writeln('   Причина: ${file.reason}');
      }
    }
  }

  return buffer.toString().trimRight();
}

String _textOrNone(String value) => value.isEmpty ? 'нет' : value;

int _newestFirst(DiagnosticAudioFile left, DiagnosticAudioFile right) {
  final leftTime = left.startedAt;
  final rightTime = right.startedAt;

  if (leftTime != null && rightTime != null) {
    final byTime = rightTime.compareTo(leftTime);
    if (byTime != 0) return byTime;
  } else if (leftTime == null && rightTime != null) {
    return 1;
  } else if (leftTime != null && rightTime == null) {
    return -1;
  }

  return right.fileName.compareTo(left.fileName);
}
