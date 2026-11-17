/// Результат проверки WAV-файла на стороне Android.
enum WavFileStatus {
  valid,
  empty,
  corrupted,
  failed;

  static WavFileStatus parse(Object? raw) {
    if (raw is! String) return WavFileStatus.failed;

    switch (raw.toUpperCase()) {
      case 'VALID':
        return WavFileStatus.valid;
      case 'EMPTY':
        return WavFileStatus.empty;
      case 'CORRUPTED':
        return WavFileStatus.corrupted;
      case 'FAILED':
        return WavFileStatus.failed;
      default:
        return WavFileStatus.failed;
    }
  }
}

/// Строка диагностического каталога WAV.
///
/// Отсутствующие дата, длительность или размер остаются `null`: экран
/// диагностики не должен выдавать их за реальные нули.
class DiagnosticAudioFile {
  const DiagnosticAudioFile({
    required this.fileName,
    required this.startedAt,
    required this.duration,
    required this.sizeBytes,
    required this.status,
    required this.reason,
  });

  final String fileName;
  final DateTime? startedAt;
  final Duration? duration;
  final int? sizeBytes;
  final WavFileStatus status;
  final String reason;

  bool get isValid => status == WavFileStatus.valid;

  bool get isPlayable => isValid && duration != null;

  static DiagnosticAudioFile? parse(Object? raw) {
    if (raw is! Map) return null;

    final fileName = raw['fileName'];
    if (fileName is! String || fileName.isEmpty) return null;

    final rawStatus = raw['status'];
    if (rawStatus is! String) return null;

    final startedAtMillis = raw['startedAtMillis'];
    final durationMs = raw['durationMs'];
    final sizeBytes = raw['sizeBytes'];
    final reason = raw['reason'];

    return DiagnosticAudioFile(
      fileName: fileName,
      startedAt: startedAtMillis is num && startedAtMillis > 0
          ? DateTime.fromMillisecondsSinceEpoch(startedAtMillis.toInt())
          : null,
      duration: durationMs is num && durationMs > 0
          ? Duration(milliseconds: durationMs.toInt())
          : null,
      sizeBytes: sizeBytes is num && sizeBytes >= 0 ? sizeBytes.toInt() : null,
      status: WavFileStatus.parse(raw['status']),
      reason: reason is String ? reason : '',
    );
  }
}
