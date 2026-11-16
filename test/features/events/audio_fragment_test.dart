import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/features/events/domain/audio_fragment.dart';
import 'package:sleepy/features/events/domain/recording_status.dart';

void main() {
  group('AudioFragment.parse', () {
    test('reads a complete payload', () {
      final fragment = AudioFragment.parse(<String, Object>{
        'id': 'event_20261115_230000_000_001.wav',
        'fileName': 'event_20261115_230000_000_001.wav',
        'filePath': '/data/user/0/app/files/recordings/event_20261115_230000_000_001.wav',
        'startedAtMillis': 1763240400000,
        'durationMs': 21000,
        'sizeBytes': 672044,
      });

      expect(fragment, isNotNull);
      expect(fragment!.id, 'event_20261115_230000_000_001.wav');
      expect(fragment.duration, const Duration(seconds: 21));
      expect(fragment.sizeBytes, 672044);
      expect(
        fragment.startedAt,
        DateTime.fromMillisecondsSinceEpoch(1763240400000),
      );
    });

    test('rejects incomplete or impossible entries', () {
      expect(AudioFragment.parse(null), isNull);
      expect(AudioFragment.parse('string'), isNull);
      expect(
        AudioFragment.parse(<String, Object>{
          'id': 'a.wav',
          'fileName': 'a.wav',
        }),
        isNull,
      );
      expect(
        AudioFragment.parse(<String, Object>{
          'id': 'a.wav',
          'fileName': 'a.wav',
          'filePath': '/tmp/a.wav',
          'startedAtMillis': 0,
          'durationMs': 1000,
          'sizeBytes': 32044,
        }),
        isNull,
      );
      expect(
        AudioFragment.parse(<String, Object>{
          'id': 'a.wav',
          'fileName': 'a.wav',
          'filePath': '/tmp/a.wav',
          'startedAtMillis': 1000,
          'durationMs': 0,
          'sizeBytes': 32044,
        }),
        isNull,
      );
    });
  });

  group('RecordingStatus.fromMap', () {
    test('reads counters and timestamps', () {
      final status = RecordingStatus.fromMap(<String, dynamic>{
        'isRecording': true,
        'fragmentCount': 4,
        'eventCount': 2,
        'error': '',
        'lastWarning': 'Мало места',
        'interrupted': false,
        'startedAtMillis': 1763240400000,
        'endedAtMillis': 0,
        'lastFragmentAtMillis': 1763240460000,
      });

      expect(status.isRecording, isTrue);
      expect(status.fragmentCount, 4);
      expect(status.eventCount, 2);
      expect(status.hasWarning, isTrue);
      expect(status.hasError, isFalse);
      expect(status.startedAt, isNotNull);
      expect(status.endedAt, isNull);
      expect(status.lastActivityAt, status.lastFragmentAt);
    });

    test('falls back to an empty status', () {
      final empty = RecordingStatus.fromMap(null);

      expect(empty.isRecording, isFalse);
      expect(empty.fragmentCount, 0);
      expect(empty.eventCount, 0);
      expect(empty.error, isEmpty);
      expect(empty.lastActivityAt, isNull);

      final status = RecordingStatus.fromMap(<String, dynamic>{});

      expect(status.isRecording, isFalse);
      expect(status.fragmentCount, 0);
      expect(status.eventCount, 0);
      expect(status.lastActivityAt, isNull);
    });
  });
}
