import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/features/diagnostics/domain/diagnostic_audio_file.dart';
import 'package:sleepy/features/events/data/recording_gateway.dart';
import 'package:sleepy/features/events/domain/audio_fragment.dart';
import 'package:sleepy/features/events/domain/recording_status.dart';
import 'package:sleepy/features/recording/application/sleep_monitor_controller.dart';
import 'package:sleepy/features/recording/domain/recording_profile.dart';
import 'package:sleepy/features/sessions/application/sleep_session_controller.dart';
import 'package:sleepy/features/sessions/data/sleep_session_repository.dart';
import 'package:sleepy/features/sessions/domain/sleep_session.dart';

RecordingStatus status({bool recording = false, String profile = 'STANDARD'}) {
  return RecordingStatus(
    isRecording: recording,
    fragmentCount: 0,
    eventCount: 0,
    lastWarning: '',
    error: '',
    interrupted: false,
    startedAt: recording ? DateTime(2026, 11, 26, 0, 0) : null,
    endedAt: null,
    lastFragmentAt: null,
    recordingProfile: profile,
    diagnosticProfile: profile,
  );
}

class FakeGateway implements RecordingGateway {
  FakeGateway(this.currentStatus);

  RecordingStatus currentStatus;
  int statusCalls = 0;

  @override
  Future<RecordingStatus> fetchStatus() async {
    statusCalls += 1;
    return currentStatus;
  }

  @override
  Future<List<AudioFragment>> fetchFragments() async => const <AudioFragment>[];

  @override
  Future<List<DiagnosticAudioFile>> fetchDiagnosticFiles() async =>
      const <DiagnosticAudioFile>[];

  @override
  Future<void> deleteFragment(AudioFragment fragment) async {}
}

class FakeControl implements RecordingControlGateway {
  final List<String> profileCalls = <String>[];
  RecordingGatewayException? failure;

  @override
  Future<bool> requestMicrophonePermission() async => true;

  @override
  Future<void> startRecording() async {}

  @override
  Future<void> stopRecording() async {}

  @override
  Future<void> setRecordingProfile(String profileId) async {
    profileCalls.add(profileId);
    if (failure != null) throw failure!;
  }
}

class FakeSessionRepository implements SleepSessionRepository {
  @override
  Future<List<SleepSession>> loadSessions() async => const <SleepSession>[];

  @override
  Future<void> saveSessions(List<SleepSession> sessions) async {}

  @override
  Future<ActiveSleepSession?> loadActiveSession() async => null;

  @override
  Future<void> saveActiveSession(ActiveSleepSession session) async {}

  @override
  Future<void> clearActiveSession() async {}
}

SleepMonitorController controllerFor({
  required FakeGateway gateway,
  required FakeControl control,
}) {
  return SleepMonitorController(
    gateway: gateway,
    control: control,
    sessions: SleepSessionController(repository: FakeSessionRepository()),
    now: () => DateTime(2026, 11, 26, 0, 0),
  );
}

void main() {
  test('initialize adopts the persisted recording profile', () async {
    final gateway = FakeGateway(status(profile: 'HIGH'));
    final controller = controllerFor(gateway: gateway, control: FakeControl());

    await controller.initialize();

    expect(controller.profile, RecordingProfile.high);
    expect(controller.error, isNull);
    expect(gateway.statusCalls, 1);
  });

  test('setProfile persists the selected id and updates state', () async {
    final control = FakeControl();
    final controller = controllerFor(
      gateway: FakeGateway(status()),
      control: control,
    );

    await controller.setProfile(RecordingProfile.veryHigh);

    expect(controller.profile, RecordingProfile.veryHigh);
    expect(control.profileCalls, <String>['VERY_HIGH']);
  });

  test('setProfile rolls back when native persistence fails', () async {
    final control = FakeControl()
      ..failure = const RecordingGatewayException('Профиль недоступен');
    final controller = controllerFor(
      gateway: FakeGateway(status()),
      control: control,
    );

    await expectLater(
      controller.setProfile(RecordingProfile.high),
      throwsA(isA<SleepMonitorException>()),
    );

    expect(controller.profile, RecordingProfile.standard);
    expect(controller.error, 'Профиль недоступен');
    expect(control.profileCalls, <String>['HIGH']);
  });
}
