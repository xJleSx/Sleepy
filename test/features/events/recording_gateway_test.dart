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
        payload(name: 'event_20261114_010000_000_001.wav', startedAtMillis: 100),
        <String, Object>{'id': 'broken'},
        payload(name: 'event_20261115_230000_000_001.wav', startedAtMillis: 300),
        payload(name: 'event_20261115_230000_000_002.wav', startedAtMillis: 300),
        payload(name: 'event_20261113_230000_000_001.wav', startedAtMillis: 200),
      ];
    });

    final fragments = await createGateway().fetchFragments();

    expect(
      fragments.map((fragment) => fragment.id),
      <String>[
        'event_20261115_230000_000_002.wav',
        'event_20261115_230000_000_001.wav',
        'event_20261113_230000_000_001.wav',
        'event_20261114_010000_000_001.wav',
      ],
    );
  });

  test('returns an empty list when the platform sends nothing', () async {
    mockHandler((call) async => null);

    expect(await createGateway().fetchFragments(), isEmpty);
  });

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
  });

  test('wraps platform errors into a readable exception', () async {
    mockHandler((call) async {
      throw PlatformException(code: 'LIST_FRAGMENTS_ERROR', message: 'Нет доступа');
    });

    expect(
      createGateway().fetchFragments(),
      throwsA(
        isA<RecordingGatewayException>()
            .having((error) => error.message, 'message', 'Нет доступа'),
      ),
    );
  });

  test('reports a missing channel handler as an error', () async {
    final gateway = ChannelRecordingGateway(
      channel: const MethodChannel('sleepy.test/missing'),
    );

    expect(
      gateway.fetchStatus(),
      throwsA(isA<RecordingGatewayException>()),
    );
  });
}
