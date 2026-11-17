import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/features/diagnostics/domain/diagnostic_audio_file.dart';
import 'package:sleepy/features/diagnostics/domain/diagnostic_report.dart';
import 'package:sleepy/features/diagnostics/presentation/diagnostic_report_formatter.dart';
import 'package:sleepy/features/events/domain/recording_status.dart';

void main() {
  final startedAt = DateTime(2026, 11, 17, 0, 10);
  final endedAt = DateTime(2026, 11, 17, 0, 31, 5);

  RecordingStatus status({
    bool recording = false,
    String lastFragmentPath = '',
  }) {
    return RecordingStatus(
      isRecording: recording,
      eventCount: 4,
      fragmentCount: 4,
      error: '',
      interrupted: false,
      startedAt: startedAt,
      endedAt: recording ? null : endedAt,
      lastFragmentAt: null,
      samplesRead: 1234567,
      readErrorCount: 0,
      lastChunkDbfs: -54.2,
      noiseFloorDbfs: -120,
      candidateCount: 12,
      confirmedEventCount: 4,
      writerOpenCount: 4,
      writerSavedCount: 3,
      writerFailedCount: 1,
      lastWarning: 'Очередь writer переполнена',
      currentDetectorState: 'QUIET',
      lastFragmentPath: lastFragmentPath,
    );
  }

  test('builds a readable report with all diagnostic sections', () {
    final report = DiagnosticReport(
      appVersion: '1.0.0+1',
      generatedAt: DateTime(2026, 11, 17, 1, 30),
      lastUpdatedAt: DateTime(2026, 11, 17, 1, 29, 58),
      status: status(),
      files: <DiagnosticAudioFile>[
        DiagnosticAudioFile(
          fileName: 'event_001.wav',
          startedAt: startedAt,
          duration: const Duration(seconds: 21),
          sizeBytes: 672044,
          status: WavFileStatus.valid,
          reason: '',
        ),
        DiagnosticAudioFile(
          fileName: 'event_002.wav',
          startedAt: endedAt,
          duration: null,
          sizeBytes: 44,
          status: WavFileStatus.corrupted,
          reason: 'Обрезанный data-чанк',
        ),
      ],
    );

    final text = buildDiagnosticReport(report);

    expect(text, contains('Sleepy · диагностика аудиозаписи'));
    expect(text, contains('Версия приложения: 1.0.0+1'));
    expect(text, contains('Состояние: Запись остановлена'));
    expect(text, contains('Длительность: 00:21:05'));
    expect(text, contains('noiseFloorDbfs: нет сигнала'));
    expect(text, contains('lastChunkDbfs: −54.2 дБFS'));
    expect(text, contains('writerFailedCount: 1'));
    expect(text, contains('Показано 2 из 2'));
    expect(text, contains('event_001.wav'));
    expect(text, contains('Проверка: Повреждённый'));
    expect(text, contains('Причина: Обрезанный data-чанк'));
  });

  test('shows missing diagnostics as absent, not as zero', () {
    final report = DiagnosticReport(
      appVersion: '1.0.0+1',
      generatedAt: DateTime(2026, 11, 17, 1, 30),
      lastUpdatedAt: null,
      status: RecordingStatus.empty,
      files: const <DiagnosticAudioFile>[],
    );

    final text = buildDiagnosticReport(report);

    expect(text, contains('samplesRead: —'));
    expect(text, contains('readErrorCount: —'));
    expect(text, contains('lastChunkDbfs: —'));
    expect(text, contains('noiseFloorDbfs: —'));
    expect(text, contains('Последнее обновление диагностики: —'));
    expect(text, contains('Файлов пока нет.'));
    expect(text, isNot(contains('samplesRead: 0')));
  });

  test('uses a basename instead of an absolute fragment path', () {
    final report = DiagnosticReport(
      appVersion: '1.0.0+1',
      generatedAt: DateTime(2026, 11, 17, 1, 30),
      lastUpdatedAt: null,
      status: status(
        lastFragmentPath:
            '/data/user/0/com.sleepy.app.sleepy/app_flutter/events/'
            'event_001.wav',
      ),
      files: const <DiagnosticAudioFile>[],
    );

    final text = buildDiagnosticReport(report);

    expect(text, contains('lastFragmentPath: event_001.wav'));
    expect(text, isNot(contains('/data/')));
  });

  test('lists no more than ten newest files', () {
    final files = List<DiagnosticAudioFile>.generate(
      12,
      (index) => DiagnosticAudioFile(
        fileName: 'event_${index.toString().padLeft(3, '0')}.wav',
        startedAt: startedAt.add(Duration(minutes: index)),
        duration: const Duration(seconds: 21),
        sizeBytes: 672044,
        status: WavFileStatus.valid,
        reason: '',
      ),
    );
    final report = DiagnosticReport(
      appVersion: '1.0.0+1',
      generatedAt: DateTime(2026, 11, 17, 1, 30),
      lastUpdatedAt: null,
      status: RecordingStatus.empty,
      files: files,
    );

    final text = buildDiagnosticReport(report);

    expect(text, contains('Показано 10 из 12.'));
    expect(text, contains('event_011.wav'));
    expect(text, isNot(contains('event_000.wav')));
  });
}
