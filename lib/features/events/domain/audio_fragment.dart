/// Сохранённый WAV-фрагмент события, полученный из каталога записей.
class AudioFragment {
  const AudioFragment({
    required this.id,
    required this.fileName,
    required this.filePath,
    required this.startedAt,
    required this.duration,
    required this.sizeBytes,
    this.contentStartSample,
    this.contentEndSample,
    this.detectorStartSample,
    this.detectorEndSample,
    this.truncated = false,
    this.profileId = 'STANDARD',
    this.eventClass = 'EVENT',
    this.reviewStatus = 'UNREVIEWED',
    this.triggerReasons = '',
    this.peakDbfs,
    this.averageDbfs,
  });

  /// Уникальный идентификатор внутри каталога: имя файла.
  final String id;

  final String fileName;

  /// Абсолютный путь для воспроизведения.
  final String filePath;

  /// Время начала события (по имени файла).
  final DateTime startedAt;

  /// Длительность по заголовку WAV, а не по размеру файла.
  final Duration duration;

  final int sizeBytes;

  /// Точные границы содержимого в сэмплах. `null`, если sidecar отсутствует.
  final int? contentStartSample;

  final int? contentEndSample;

  final int? detectorStartSample;

  final int? detectorEndSample;

  final bool truncated;
  final String profileId;
  final String eventClass;
  final String reviewStatus;
  final String triggerReasons;
  final double? peakDbfs;
  final double? averageDbfs;

  bool get isWeakCandidate => eventClass == 'WEAK_CANDIDATE';

  bool get needsReview => reviewStatus == 'UNREVIEWED';

  bool get hasContentBoundaries =>
      contentStartSample != null &&
      contentEndSample != null &&
      contentEndSample! > contentStartSample!;

  /// Разбирает ответ канала. Битые записи возвращают `null` и молча
  /// отбрасываются вызывающим кодом.
  static AudioFragment? parse(Object? raw) {
    if (raw is! Map) return null;

    final id = raw['id'];
    final fileName = raw['fileName'];
    final filePath = raw['filePath'];
    final startedAtMillis = raw['startedAtMillis'];
    final durationMs = raw['durationMs'];
    final sizeBytes = raw['sizeBytes'];
    final contentStartSample = _optionalSample(raw['contentStartSample']);
    final contentEndSample = _optionalSample(raw['contentEndSample']);
    final detectorStartSample = _optionalSample(raw['detectorStartSample']);
    final detectorEndSample = _optionalSample(raw['detectorEndSample']);
    final truncated = raw['truncated'];
    final profileId = _text(raw['profileId'], fallback: 'STANDARD');
    final eventClass = _text(raw['eventClass'], fallback: 'EVENT');
    final reviewStatus = _text(raw['reviewStatus'], fallback: 'UNREVIEWED');
    final triggerReasons = _text(raw['triggerReasons']);
    final peakDbfs = _optionalDouble(raw['peakDbfs']);
    final averageDbfs = _optionalDouble(raw['averageDbfs']);

    if (id is! String || id.isEmpty) return null;
    if (fileName is! String || fileName.isEmpty) return null;
    if (filePath is! String || filePath.isEmpty) return null;
    if (startedAtMillis is! num || startedAtMillis <= 0) return null;
    if (durationMs is! num || durationMs <= 0) return null;
    if (sizeBytes is! num || sizeBytes <= 0) return null;
    final validBoundaries =
        contentStartSample != null &&
        contentEndSample != null &&
        contentEndSample > contentStartSample;

    return AudioFragment(
      id: id,
      fileName: fileName,
      filePath: filePath,
      startedAt: DateTime.fromMillisecondsSinceEpoch(startedAtMillis.toInt()),
      duration: Duration(milliseconds: durationMs.toInt()),
      sizeBytes: sizeBytes.toInt(),
      contentStartSample: validBoundaries ? contentStartSample : null,
      contentEndSample: validBoundaries ? contentEndSample : null,
      detectorStartSample: validBoundaries ? detectorStartSample : null,
      detectorEndSample: validBoundaries ? detectorEndSample : null,
      truncated: validBoundaries && truncated is bool ? truncated : false,
      profileId: profileId,
      eventClass: eventClass,
      reviewStatus: reviewStatus,
      triggerReasons: triggerReasons,
      peakDbfs: peakDbfs,
      averageDbfs: averageDbfs,
    );
  }

  static int? _optionalSample(Object? raw) {
    if (raw is! num) return null;
    final value = raw.toInt();
    return value >= 0 ? value : null;
  }

  static double? _optionalDouble(Object? raw) {
    if (raw is! num) return null;
    return raw.toDouble();
  }

  static String _text(Object? raw, {String fallback = ''}) {
    return raw is String && raw.isNotEmpty ? raw : fallback;
  }
}
