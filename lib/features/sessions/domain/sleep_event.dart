class SleepEvent {
  const SleepEvent({
    required this.id,
    required this.fileName,
    required this.filePath,
    required this.startedAt,
    required this.duration,
    required this.sizeBytes,
    this.truncated = false,
  });

  final String id;
  final String fileName;
  final String filePath;
  final DateTime startedAt;
  final Duration duration;
  final int sizeBytes;
  final bool truncated;

  DateTime get endedAt => startedAt.add(duration);

  Map<String, Object?> toJson() {
    return <String, Object?>{
      'id': id,
      'fileName': fileName,
      'filePath': filePath,
      'startedAtMillis': startedAt.millisecondsSinceEpoch,
      'durationMs': duration.inMilliseconds,
      'sizeBytes': sizeBytes,
      'truncated': truncated,
    };
  }

  static SleepEvent? tryParse(Object? raw) {
    if (raw is! Map) return null;

    final id = raw['id'];
    final fileName = raw['fileName'];
    final filePath = raw['filePath'];
    final startedAtMillis = raw['startedAtMillis'];
    final durationMs = raw['durationMs'];
    final sizeBytes = raw['sizeBytes'];
    final truncated = raw['truncated'];

    if (id is! String || id.isEmpty) return null;
    if (fileName is! String || fileName.isEmpty) return null;
    if (filePath is! String || filePath.isEmpty) return null;
    if (startedAtMillis is! num || startedAtMillis <= 0) return null;
    if (durationMs is! num || durationMs <= 0) return null;
    if (sizeBytes is! num || sizeBytes < 0) return null;

    return SleepEvent(
      id: id,
      fileName: fileName,
      filePath: filePath,
      startedAt: DateTime.fromMillisecondsSinceEpoch(startedAtMillis.toInt()),
      duration: Duration(milliseconds: durationMs.toInt()),
      sizeBytes: sizeBytes.toInt(),
      truncated: truncated == true,
    );
  }
}
