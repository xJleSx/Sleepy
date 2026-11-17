import '../../events/domain/recording_status.dart';
import 'diagnostic_audio_file.dart';

/// Снимок диагностики для отображения и текстового отчёта.
class DiagnosticReport {
  const DiagnosticReport({
    required this.appVersion,
    required this.generatedAt,
    required this.status,
    required this.files,
    this.lastUpdatedAt,
  });

  final String appVersion;
  final DateTime generatedAt;
  final RecordingStatus status;
  final List<DiagnosticAudioFile> files;

  /// Время последнего успешного обновления статуса, если оно известно.
  final DateTime? lastUpdatedAt;

  int get totalFiles => files.length;

  int get validFileCount =>
      files.where((file) => file.status == WavFileStatus.valid).length;

  int get emptyFileCount =>
      files.where((file) => file.status == WavFileStatus.empty).length;

  int get corruptedFileCount =>
      files.where((file) => file.status == WavFileStatus.corrupted).length;

  int get failedFileCount =>
      files.where((file) => file.status == WavFileStatus.failed).length;

  /// WAV, которые можно открыть и прослушать.
  int get availableWavCount => files.where((file) => file.isPlayable).length;
}
