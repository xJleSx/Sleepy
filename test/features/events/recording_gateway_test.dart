import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/features/events/data/recording_gateway.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channelName = 'sleepy.test/recording';
  final channel = MethodChannel(channelName);

  ChannelRecordingGateway createGateway() =>
      ChannelRecordingGateway(channel: channel);

  void mockHandler(Future<Object?> Function(MethodCall call) handler) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, handler);
  }

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  Map<String, Object> payload({
    required String name,
    required int startedAtMillis,
    int durationMs = 1000,
  }) {
    return <String, Object>{
      'id': name,
      'fileName': name,
      'filePath': '/recordings/$name',
      'startedAtMillis': startedAtMillis,
      'durationMs': durationMs,
      'sizeBytes': 32044,
    };
  }

  test('sorts fragments newest first and skips broken entries', () async {
    mockHandler((call) async {
      expect(call.method, 'listFragments');

      return <Object?>[
        payload(
          name: 'event_20261114_010000_000_001.wav',
          startedAtMillis: 100,
        ),
        <String, Object>{'id': 'broken'},
        payload(
          name: 'event_20261115_230000_000_001.wav',
          startedAtMillis: 300,
        ),
        payload(
          name: 'event_20261115_230000_000_002.wav',
          startedAtMillis: 300,
        ),
        payload(
          name: 'event_20261113_230000_000_001.wav',
          startedAtMillis: 200,
        ),
      ];
    });

    final fragments = await createGateway().fetchFragments();

    expect(fragments.map((fragment) => fragment.id), <String>[
      'event_20261115_230000_000_002.wav',
      'event_20261115_230000_000_001.wav',
      'event_20261113_230000_000_001.wav',
      'event_20261114_010000_000_001.wav',
    ]);
  });

  test('returns an empty list when the platform sends nothing', () async {
    mockHandler((call) async => null);

    expect(await createGateway().fetchFragments(), isEmpty);
    expect(await createGateway().fetchDiagnosticFiles(), isEmpty);
  });

  test(
    'reads diagnostic files newest first and keeps problem statuses',
    () async {
      mockHandler((call) async {
        expect(call.method, 'listDiagnosticFiles');

        return <Object?>[
          <String, Object>{
            'fileName': 'event_old.wav',
            'startedAtMillis': 100,
            'sizeBytes': 44,
            'status': 'EMPTY',
            'reason': 'Нет аудиоданных',
          },
          <String, Object>{
            'fileName': 'event_new.wav',
            'startedAtMillis': 300,
            'durationMs': 21000,
            'sizeBytes': 672044,
            'status': 'VALID',
            'reason': '',
          },
          <String, Object>{'fileName': 'broken.wav'},
          <String, Object>{
            'fileName': 'event_mid.wav',
            'startedAtMillis': 200,
            'sizeBytes': 44,
            'status': 'CORRUPTED',
            'reason': 'Обрезанный data-чанк',
          },
        ];
      });

      final files = await createGateway().fetchDiagnosticFiles();

      expect(files.map((file) => file.fileName), <String>[
        'event_new.wav',
        'event_mid.wav',
        'event_old.wav',
      ]);
      expect(files.first.status.name, 'valid');
      expect(files.first.duration, const Duration(seconds: 21));
      expect(files[1].status.name, 'corrupted');
      expect(files[1].duration, isNull);
      expect(files[2].status.name, 'empty');
      expect(files[2].reason, 'Нет аудиоданных');
    },
  );

  test('reads recording status', () async {
    mockHandler((call) async {
      expect(call.method, 'getRecordingStatus');

      return <String, dynamic>{
        'isRecording': true,
        'fragmentCount': 3,
        'eventCount': 1,
        'startedAtMillis': 1000,
        'samplesRead': 12345,
        'readErrorCount': 2,
        'lastChunkDbfs': -33.5,
        'noiseFloorDbfs': -55.25,
        'currentDetectorState': 'QUIET',
        'candidateCount': 3,
        'confirmedEventCount': 1,
        'writerOpenCount': 4,
        'writerSavedCount': 3,
        'writerFailedCount': 1,
        'lastWriterError': 'Не удалось создать WAV-фрагмент',
        'lastFragmentPath': '/recordings/event_a.wav',
        'recordingProfile': 'HIGH',
        'diagnosticProfile': 'VERY_HIGH',
        'weakCandidateCount': 6,
        'weakSavedCount': 4,
        'weakRejectedCount': 2,
        'lastWeakRejection': 'cooldown',
      };
    });

    final status = await createGateway().fetchStatus();

    expect(status.isRecording, isTrue);
    expect(status.fragmentCount, 3);
    expect(status.eventCount, 1);
    expect(status.startedAt, isNotNull);
    expect(status.samplesRead, 12345);
    expect(status.readErrorCount, 2);
    expect(status.lastChunkDbfs, -33.5);
    expect(status.noiseFloorDbfs, -55.25);
    expect(status.currentDetectorState, 'QUIET');
    expect(status.candidateCount, 3);
    expect(status.confirmedEventCount, 1);
    expect(status.writerOpenCount, 4);
    expect(status.writerSavedCount, 3);
    expect(status.writerFailedCount, 1);
    expect(status.lastWriterError, 'Не удалось создать WAV-фрагмент');
    expect(status.lastFragmentPath, '/recordings/event_a.wav');
    expect(status.recordingProfile, 'HIGH');
    expect(status.diagnosticProfile, 'VERY_HIGH');
    expect(status.weakCandidateCount, 6);
    expect(status.weakSavedCount, 4);
    expect(status.weakRejectedCount, 2);
    expect(status.lastWeakRejection, 'cooldown');
  });

  test('sends the selected recording profile to the platform', () async {
    mockHandler((call) async {
      expect(call.method, 'setRecordingProfile');
      expect(call.arguments, <String, Object>{'profile': 'VERY_HIGH'});
      return null;
    });

    await ChannelRecordingControlGateway(channel: channel)
        .setRecordingProfile('VERY_HIGH');
  });

  test('wraps profile persistence errors into a readable exception', () async {
    mockHandler((call) async {
      throw PlatformException(
        code: 'SET_PROFILE_ERROR',
        message: 'Не удалось сохранить профиль',
      );
    });

    expect(
      ChannelRecordingControlGateway(channel: channel)
          .setRecordingProfile('HIGH'),
      throwsA(
        isA<RecordingGatewayException>().having(
          (error) => error.message,
          'message',
          'Не удалось сохранить профиль',
        ),
      ),
    );
  });

  test('wraps platform errors into a readable exception', () async {
    mockHandler((call) async {
      throw PlatformException(
        code: 'LIST_FRAGMENTS_ERROR',
        message: 'Нет доступа',
      );
    });

    expect(
      createGateway().fetchFragments(),
      throwsA(
        isA<RecordingGatewayException>().having(
          (error) => error.message,
          'message',
          'Нет доступа',
        ),
      ),
    );
  });

  test('reports a missing channel handler as an error', () async {
    final gateway = ChannelRecordingGateway(
      channel: const MethodChannel('sleepy.test/missing'),
    );

    expect(gateway.fetchStatus(), throwsA(isA<RecordingGatewayException>()));
  });
}
