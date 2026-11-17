/// Состояние службы записи и накопительная статистика событий.
///
/// [fragmentCount] — сколько WAV-фрагментов реально лежит на диске.
/// [eventCount] — сколько подтверждённых событий накопила служба за всё
/// время: одно событие может состоять из нескольких файлов, поэтому
/// числа не обязаны совпадать.
class RecordingStatus {
  const RecordingStatus({
    required this.isRecording,
    required this.fragmentCount,
    required this.eventCount,
    required this.lastWarning,
    required this.error,
    required this.interrupted,
    required this.startedAt,
    required this.endedAt,
    required this.lastFragmentAt,
    this.lastFragmentPath = '',
    this.samplesRead,
    this.readErrorCount,
    this.lastChunkDbfs,
    this.noiseFloorDbfs,
    this.currentDetectorState = '',
    this.candidateCount,
    this.confirmedEventCount,
    this.writerOpenCount,
    this.writerSavedCount,
    this.writerFailedCount,
    this.lastWriterError = '',
  });

  static const empty = RecordingStatus(
    isRecording: false,
    fragmentCount: 0,
    eventCount: 0,
    lastWarning: '',
    error: '',
    interrupted: false,
    startedAt: null,
    endedAt: null,
    lastFragmentAt: null,
  );

  final bool isRecording;
  final int fragmentCount;
  final int eventCount;
  final String lastWarning;
  final String error;
  final bool interrupted;
  final DateTime? startedAt;
  final DateTime? endedAt;
  final DateTime? lastFragmentAt;

  /// Диагностика текущей или последней завершённой сессии.
  ///
  /// Числовые поля равны `null`, если платформа их не прислала. Это
  /// принципиально для экрана диагностики: отсутствующее значение нельзя
  /// показывать как реальный ноль.
  final String lastFragmentPath;
  final int? samplesRead;
  final int? readErrorCount;
  final double? lastChunkDbfs;
  final double? noiseFloorDbfs;
  final String currentDetectorState;
  final int? candidateCount;
  final int? confirmedEventCount;
  final int? writerOpenCount;
  final int? writerSavedCount;
  final int? writerFailedCount;
  final String lastWriterError;

  bool get hasError => error.isNotEmpty;
  bool get hasWarning => lastWarning.isNotEmpty;

  DateTime? get lastActivityAt => endedAt ?? lastFragmentAt ?? startedAt;

  factory RecordingStatus.fromMap(Map<String, dynamic>? map) {
    if (map == null) return empty;

    return RecordingStatus(
      isRecording: map['isRecording'] == true,
      fragmentCount: _count(map['fragmentCount']) ?? 0,
      eventCount: _count(map['eventCount']) ?? 0,
      lastWarning: _text(map['lastWarning']),
      error: _text(map['error']),
      interrupted: map['interrupted'] == true,
      startedAt: _time(map['startedAtMillis']),
      endedAt: _time(map['endedAtMillis']),
      lastFragmentAt: _time(map['lastFragmentAtMillis']),
      lastFragmentPath: _text(map['lastFragmentPath']),
      samplesRead: _count(map['samplesRead']),
      readErrorCount: _count(map['readErrorCount']),
      lastChunkDbfs: _dbfs(map['lastChunkDbfs']),
      noiseFloorDbfs: _dbfs(map['noiseFloorDbfs']),
      currentDetectorState: _text(map['currentDetectorState']),
      candidateCount: _count(map['candidateCount']),
      confirmedEventCount: _count(map['confirmedEventCount']),
      writerOpenCount: _count(map['writerOpenCount']),
      writerSavedCount: _count(map['writerSavedCount']),
      writerFailedCount: _count(map['writerFailedCount']),
      lastWriterError: _text(map['lastWriterError']),
    );
  }

  static int? _count(Object? value) => value is num ? value.toInt() : null;

  static double? _dbfs(Object? value) => value is num ? value.toDouble() : null;

  static String _text(Object? value) => value is String ? value : '';

  static DateTime? _time(Object? value) {
    if (value is! num) return null;

    final millis = value.toInt();
    if (millis <= 0) return null;

    return DateTime.fromMillisecondsSinceEpoch(millis);
  }
}
