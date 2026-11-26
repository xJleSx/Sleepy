import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/features/diagnostics/domain/diagnostic_audio_file.dart';
import 'package:sleepy/features/events/data/recording_gateway.dart';
import 'package:sleepy/features/events/domain/audio_fragment.dart';
import 'package:sleepy/features/events/domain/recording_status.dart';
import 'package:sleepy/features/sessions/application/sleep_session_controller.dart';
import 'package:sleepy/features/sessions/data/sleep_session_repository.dart';
import 'package:sleepy/features/sessions/domain/sleep_event.dart';
import 'package:sleepy/features/sessions/domain/sleep_session.dart';

void main() {
  test('enriches stored sessions with fragment loudness', () async {
    final session = SleepSession(
      id: 'sleep_1',
      startedAt: DateTime(2026, 11, 23, 23),
      endedAt: DateTime(2026, 11, 24, 7),
      status: SleepSessionStatus.completed,
      events: <SleepEvent>[
        SleepEvent(
          id: 'event_1.wav',
          fileName: 'event_1.wav',
          filePath: '/tmp/event_1.wav',
          startedAt: DateTime(2026, 11, 24, 1, 10),
          duration: const Duration(seconds: 9),
          sizeBytes: 288044,
        ),
      ],
    );
    final controller = SleepSessionController(
      repository: _FakeSessionRepository(<SleepSession>[session]),
      recording: _FakeRecordingGateway(<AudioFragment>[
        AudioFragment(
          id: 'event_1.wav',
          fileName: 'event_1.wav',
          filePath: '/tmp/event_1.wav',
          startedAt: DateTime(2026, 11, 24, 1, 10),
          duration: const Duration(seconds: 9),
          sizeBytes: 288044,
          peakDbfs: -30.5,
          averageDbfs: -55.25,
        ),
      ]),
    );
    addTearDown(controller.dispose);

    await controller.load();

    final event = controller.sessions.single.events.single;
    expect(event.peakDbfs, -30.5);
    expect(event.averageDbfs, -55.25);
  });
}

class _FakeSessionRepository implements SleepSessionRepository {
  _FakeSessionRepository(this._sessions);

  List<SleepSession> _sessions;

  @override
  Future<List<SleepSession>> loadSessions() async => _sessions;

  @override
  Future<void> saveSessions(List<SleepSession> sessions) async {
    _sessions = sessions;
  }

  @override
  Future<ActiveSleepSession?> loadActiveSession() async => null;

  @override
  Future<void> saveActiveSession(ActiveSleepSession session) async {}

  @override
  Future<void> clearActiveSession() async {}
}

class _FakeRecordingGateway implements RecordingGateway {
  _FakeRecordingGateway(this.fragments);

  final List<AudioFragment> fragments;

  @override
  Future<List<AudioFragment>> fetchFragments() async => fragments;

  @override
  Future<RecordingStatus> fetchStatus() {
    throw UnimplementedError();
  }

  @override
  Future<List<DiagnosticAudioFile>> fetchDiagnosticFiles() {
    throw UnimplementedError();
  }

  @override
  Future<void> deleteFragment(AudioFragment fragment) {
    throw UnimplementedError();
  }
}
