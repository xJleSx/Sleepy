/// Сохранённый WAV-фрагмент события, полученный из каталога записей.
class AudioFragment {
  const AudioFragment({
    required this.id,
    required this.fileName,
    required this.filePath,
    required this.startedAt,
    required this.duration,
    required this.sizeBytes,
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

    if (id is! String || id.isEmpty) return null;
    if (fileName is! String || fileName.isEmpty) return null;
    if (filePath is! String || filePath.isEmpty) return null;
    if (startedAtMillis is! num || startedAtMillis <= 0) return null;
    if (durationMs is! num || durationMs <= 0) return null;
    if (sizeBytes is! num || sizeBytes <= 0) return null;

    return AudioFragment(
      id: id,
      fileName: fileName,
      filePath: filePath,
      startedAt: DateTime.fromMillisecondsSinceEpoch(startedAtMillis.toInt()),
      duration: Duration(milliseconds: durationMs.toInt()),
      sizeBytes: sizeBytes.toInt(),
    );
  }
}
