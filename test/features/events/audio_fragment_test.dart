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

    test('reads exact sidecar boundaries', () {
      final fragment = AudioFragment.parse(<String, Object>{
        'id': 'event_20261115_230000_000_001.wav',
        'fileName': 'event_20261115_230000_000_001.wav',
        'filePath': '/tmp/event_20261115_230000_000_001.wav',
        'startedAtMillis': 1763240400000,
        'durationMs': 21000,
        'sizeBytes': 672044,
        'metadataAvailable': true,
        'contentStartSample': 4800,
        'contentEndSample': 340800,
        'detectorStartSample': 4600,
        'detectorEndSample': 341000,
        'truncated': true,
      });

      expect(fragment, isNotNull);
      expect(fragment!.hasContentBoundaries, isTrue);
      expect(fragment.contentStartSample, 4800);
      expect(fragment.contentEndSample, 340800);
      expect(fragment.detectorStartSample, 4600);
      expect(fragment.detectorEndSample, 341000);
      expect(fragment.truncated, isTrue);
    });

    test('reads weak candidate metadata and review state', () {
      final fragment = AudioFragment.parse(<String, Object>{
        'id': 'event_weak.wav',
        'fileName': 'event_weak.wav',
        'filePath': '/tmp/event_weak.wav',
        'startedAtMillis': 1763240400000,
        'durationMs': 10000,
        'sizeBytes': 320044,
        'contentStartSample': 4800,
        'contentEndSample': 324800,
        'profileId': 'HIGH',
        'eventClass': 'WEAK_CANDIDATE',
        'reviewStatus': 'UNREVIEWED',
        'triggerReasons': 'weak_threshold',
        'peakDbfs': -48.5,
        'averageDbfs': -55.25,
      });

      expect(fragment, isNotNull);
      expect(fragment!.profileId, 'HIGH');
      expect(fragment.eventClass, 'WEAK_CANDIDATE');
      expect(fragment.reviewStatus, 'UNREVIEWED');
      expect(fragment.isWeakCandidate, isTrue);
      expect(fragment.needsReview, isTrue);
      expect(fragment.triggerReasons, 'weak_threshold');
      expect(fragment.peakDbfs, -48.5);
      expect(fragment.averageDbfs, -55.25);
    });

    test('defaults legacy fragments to standard unreviewed events', () {
      final fragment = AudioFragment.parse(<String, Object>{
        'id': 'event_legacy.wav',
        'fileName': 'event_legacy.wav',
        'filePath': '/tmp/event_legacy.wav',
        'startedAtMillis': 1763240400000,
        'durationMs': 21000,
        'sizeBytes': 672044,
        'profileId': '',
        'eventClass': '',
        'reviewStatus': '',
        'triggerReasons': '',
      });

      expect(fragment, isNotNull);
      expect(fragment!.profileId, 'STANDARD');
      expect(fragment.eventClass, 'EVENT');
      expect(fragment.reviewStatus, 'UNREVIEWED');
      expect(fragment.isWeakCandidate, isFalse);
      expect(fragment.needsReview, isTrue);
      expect(fragment.peakDbfs, isNull);
      expect(fragment.averageDbfs, isNull);
    });

    test('drops missing or inconsistent sidecar boundaries', () {
      final missing = AudioFragment.parse(<String, Object>{
        'id': 'event_missing.wav',
        'fileName': 'event_missing.wav',
        'filePath': '/tmp/event_missing.wav',
        'startedAtMillis': 1763240400000,
        'durationMs': 21000,
        'sizeBytes': 672044,
      });
      final inconsistent = AudioFragment.parse(<String, Object>{
        'id': 'event_inconsistent.wav',
        'fileName': 'event_inconsistent.wav',
        'filePath': '/tmp/event_inconsistent.wav',
        'startedAtMillis': 1763240400000,
        'durationMs': 21000,
        'sizeBytes': 672044,
        'contentStartSample': 340800,
        'contentEndSample': 4800,
        'truncated': true,
      });

      expect(missing, isNotNull);
      expect(missing!.hasContentBoundaries, isFalse);
      expect(missing.truncated, isFalse);
      expect(inconsistent, isNotNull);
      expect(inconsistent!.hasContentBoundaries, isFalse);
      expect(inconsistent.contentStartSample, isNull);
      expect(inconsistent.contentEndSample, isNull);
      expect(inconsistent.truncated, isFalse);
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

    test('reads profiles and weak candidate counters', () {
      final status = RecordingStatus.fromMap(<String, dynamic>{
        'isRecording': false,
        'recordingProfile': 'VERY_HIGH',
        'diagnosticProfile': 'HIGH',
        'weakCandidateCount': 7,
        'weakSavedCount': 5,
        'weakRejectedCount': 2,
        'lastWeakRejection': 'cooldown',
      });

      expect(status.recordingProfile, 'VERY_HIGH');
      expect(status.diagnosticProfile, 'HIGH');
      expect(status.weakCandidateCount, 7);
      expect(status.weakSavedCount, 5);
      expect(status.weakRejectedCount, 2);
      expect(status.lastWeakRejection, 'cooldown');
    });

    test('defaults unknown profiles and missing weak counters safely', () {
      final status = RecordingStatus.fromMap(<String, dynamic>{
        'recordingProfile': 'high',
        'diagnosticProfile': 42,
        'weakCandidateCount': '7',
        'lastWeakRejection': '',
      });

      expect(status.recordingProfile, 'STANDARD');
      expect(status.diagnosticProfile, 'STANDARD');
      expect(status.weakCandidateCount, isNull);
      expect(status.weakSavedCount, isNull);
      expect(status.weakRejectedCount, isNull);
      expect(status.lastWeakRejection, isEmpty);
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
      expect(status.samplesRead, isNull);
      expect(status.readErrorCount, isNull);
      expect(status.lastChunkDbfs, isNull);
      expect(status.noiseFloorDbfs, isNull);
      expect(status.candidateCount, isNull);
      expect(status.confirmedEventCount, isNull);
      expect(status.writerOpenCount, isNull);
      expect(status.writerSavedCount, isNull);
      expect(status.writerFailedCount, isNull);
      expect(status.currentDetectorState, isEmpty);
      expect(status.lastFragmentPath, isEmpty);
      expect(status.lastWriterError, isEmpty);
    });
  });
}
