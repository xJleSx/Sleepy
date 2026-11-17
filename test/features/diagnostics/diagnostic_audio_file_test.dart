import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/features/diagnostics/domain/diagnostic_audio_file.dart';

void main() {
  group('DiagnosticAudioFile.parse', () {
    test('reads a valid file with duration', () {
      final file = DiagnosticAudioFile.parse(<String, Object>{
        'fileName': 'event_20261115_230000_000_001.wav',
        'startedAtMillis': 1763240400000,
        'durationMs': 21000,
        'sizeBytes': 672044,
        'status': 'VALID',
        'reason': '',
      });

      expect(file, isNotNull);
      expect(file!.fileName, 'event_20261115_230000_000_001.wav');
      expect(file.startedAt, isNotNull);
      expect(file.duration, const Duration(seconds: 21));
      expect(file.sizeBytes, 672044);
      expect(file.status, WavFileStatus.valid);
      expect(file.isPlayable, isTrue);
    });

    test('does not turn missing duration or size into zero', () {
      final file = DiagnosticAudioFile.parse(<String, Object>{
        'fileName': 'event_empty.wav',
        'status': 'EMPTY',
        'reason': 'Нет аудиоданных',
      });

      expect(file, isNotNull);
      expect(file!.startedAt, isNull);
      expect(file.duration, isNull);
      expect(file.sizeBytes, isNull);
      expect(file.status, WavFileStatus.empty);
      expect(file.isPlayable, isFalse);
    });

    test('parses all validation statuses and falls back safely', () {
      expect(
        DiagnosticAudioFile.parse(<String, Object>{
          'fileName': 'a.wav',
          'status': 'CORRUPTED',
        })!.status,
        WavFileStatus.corrupted,
      );
      expect(
        DiagnosticAudioFile.parse(<String, Object>{
          'fileName': 'a.wav',
          'status': 'FAILED',
        })!.status,
        WavFileStatus.failed,
      );
      expect(
        DiagnosticAudioFile.parse(<String, Object>{
          'fileName': 'a.wav',
          'status': 'UNKNOWN',
        })!.status,
        WavFileStatus.failed,
      );
    });

    test('rejects entries without a file name or status', () {
      expect(DiagnosticAudioFile.parse(null), isNull);
      expect(DiagnosticAudioFile.parse('bad'), isNull);
      expect(
        DiagnosticAudioFile.parse(<String, Object>{'status': 'VALID'}),
        isNull,
      );
      expect(
        DiagnosticAudioFile.parse(<String, Object>{'fileName': 'a.wav'}),
        isNull,
      );
      expect(
        DiagnosticAudioFile.parse(<String, Object>{
          'fileName': '',
          'status': 'VALID',
        }),
        isNull,
      );
    });
  });
}
