import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/features/diagnostics/domain/diagnostic_audio_file.dart';
import 'package:sleepy/features/diagnostics/domain/diagnostic_report.dart';
import 'package:sleepy/features/events/domain/recording_status.dart';

void main() {
  final generatedAt = DateTime(2026, 11, 17, 1, 30);
  final files = <DiagnosticAudioFile>[
    DiagnosticAudioFile(
      fileName: 'event_001.wav',
      startedAt: generatedAt,
      duration: const Duration(seconds: 21),
      sizeBytes: 672044,
      status: WavFileStatus.valid,
      reason: '',
    ),
    DiagnosticAudioFile(
      fileName: 'event_002.wav',
      startedAt: null,
      duration: null,
      sizeBytes: null,
      status: WavFileStatus.empty,
      reason: 'Нет аудиоданных',
    ),
    DiagnosticAudioFile(
      fileName: 'event_003.wav',
      startedAt: null,
      duration: null,
      sizeBytes: 44,
      status: WavFileStatus.corrupted,
      reason: 'Обрезанный data-чанк',
    ),
    DiagnosticAudioFile(
      fileName: 'event_004.wav',
      startedAt: null,
      duration: null,
      sizeBytes: null,
      status: WavFileStatus.failed,
      reason: 'Нет доступа',
    ),
  ];

  test('counts playable and problem files separately', () {
    final report = DiagnosticReport(
      appVersion: '1.0.0+1',
      generatedAt: generatedAt,
      lastUpdatedAt: generatedAt,
      status: RecordingStatus.empty,
      files: files,
    );

    expect(report.totalFiles, 4);
    expect(report.validFileCount, 1);
    expect(report.emptyFileCount, 1);
    expect(report.corruptedFileCount, 1);
    expect(report.failedFileCount, 1);
    expect(report.availableWavCount, 1);
  });

  test('empty report has no available files', () {
    final report = DiagnosticReport(
      appVersion: '1.0.0+1',
      generatedAt: generatedAt,
      lastUpdatedAt: null,
      status: RecordingStatus.empty,
      files: const <DiagnosticAudioFile>[],
    );

    expect(report.totalFiles, 0);
    expect(report.availableWavCount, 0);
    expect(report.lastUpdatedAt, isNull);
  });
}
