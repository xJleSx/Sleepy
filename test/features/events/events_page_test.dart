import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:sleepy/app/theme/app_theme.dart';
import 'package:sleepy/features/diagnostics/domain/diagnostic_audio_file.dart';
import 'package:sleepy/features/events/data/recording_gateway.dart';
import 'package:sleepy/features/events/domain/audio_fragment.dart';
import 'package:sleepy/features/events/domain/recording_status.dart';
import 'package:sleepy/features/events/presentation/events_page.dart';
import 'package:sleepy/features/events/presentation/playback_controller.dart';

class FakeGateway implements RecordingGateway {
  FakeGateway({
    RecordingStatus? status,
    List<AudioFragment>? fragments,
    this.error,
  }) : status = status ?? RecordingStatus.empty,
       fragments = fragments ?? <AudioFragment>[];

  RecordingStatus status;
  List<AudioFragment> fragments;
  Object? error;

  int statusCalls = 0;
  int fragmentCalls = 0;

  @override
  Future<RecordingStatus> fetchStatus() async {
    statusCalls++;

    if (error != null) throw error!;
    return status;
  }

  @override
  Future<List<AudioFragment>> fetchFragments() async {
    fragmentCalls++;

    if (error != null) throw error!;
    return List<AudioFragment>.of(fragments);
  }

  @override
  Future<List<DiagnosticAudioFile>> fetchDiagnosticFiles() async {
    if (error != null) throw error!;
    return const <DiagnosticAudioFile>[];
  }

  @override
  Future<void> deleteFragment(AudioFragment fragment) async {
    if (error != null) throw error!;
    fragments = fragments
        .where((item) => item.id != fragment.id)
        .toList();
  }
}

class FakePlayer extends AudioPlaybackController {
  PlaybackSnapshot _snapshot = PlaybackSnapshot.idle;

  final List<String> calls = <String>[];

  @override
  PlaybackSnapshot get snapshot => _snapshot;

  @override
  Future<void> select(AudioFragment fragment) async {
    if (_snapshot.fragmentId == fragment.id) {
      await togglePlayPause();
      return;
    }

    calls.add('select:${fragment.id}');
    _snapshot = PlaybackSnapshot(
      fragmentId: fragment.id,
      isPlaying: true,
      duration: fragment.duration,
    );
    notifyListeners();
  }

  @override
  Future<void> togglePlayPause() async {
    if (!_snapshot.hasSelection) return;

    calls.add('toggle');
    _snapshot = _snapshot.copyWith(isPlaying: !_snapshot.isPlaying);
    notifyListeners();
  }

  @override
  Future<void> stop() async {
    calls.add('stop');
    _snapshot = PlaybackSnapshot(
      fragmentId: _snapshot.fragmentId,
      duration: _snapshot.duration,
    );
    notifyListeners();
  }

  @override
  Future<void> seek(Duration position) async {
    calls.add('seek:${position.inMilliseconds}');
    _snapshot = _snapshot.copyWith(position: position);
    notifyListeners();
  }

  @override
  Future<void> pause() async {
    calls.add('pause');

    if (_snapshot.isPlaying) {
      _snapshot = _snapshot.copyWith(isPlaying: false);
      notifyListeners();
    }
  }
}

RecordingStatus status({
  bool isRecording = false,
  int fragmentCount = 0,
  int eventCount = 0,
  String warning = '',
  String error = '',
  bool interrupted = false,
  DateTime? startedAt,
  DateTime? endedAt,
  DateTime? lastFragmentAt,
}) {
  return RecordingStatus(
    isRecording: isRecording,
    fragmentCount: fragmentCount,
    eventCount: eventCount,
    lastWarning: warning,
    error: error,
    interrupted: interrupted,
    startedAt: startedAt,
    endedAt: endedAt,
    lastFragmentAt: lastFragmentAt,
  );
}

AudioFragment fragment({
  required String id,
  required DateTime startedAt,
  Duration duration = const Duration(seconds: 12),
  int sizeBytes = 384044,
}) {
  return AudioFragment(
    id: id,
    fileName: id,
    filePath: '/recordings/$id',
    startedAt: startedAt,
    duration: duration,
    sizeBytes: sizeBytes,
  );
}

Widget buildPage(FakeGateway gateway, FakePlayer player) {
  return MaterialApp(
    theme: sleepyTheme,
    home: Scaffold(
      body: EventsPage(
        gateway: gateway,
        playbackController: player,
        statusRefreshInterval: const Duration(seconds: 3),
      ),
    ),
  );
}

Future<void> pumpPage(
  WidgetTester tester,
  FakeGateway gateway,
  FakePlayer player,
) async {
  tester.view.physicalSize = const Size(800, 1800);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(buildPage(gateway, player));
}

void main() {
  testWidgets('shows a loading state, then the empty state', (tester) async {
    final gateway = FakeGateway();
    final player = FakePlayer();

    await pumpPage(tester, gateway, player);

    expect(find.text('Читаем записи…'), findsOneWidget);

    await tester.pump();

    expect(find.text('Звуки сна'), findsOneWidget);
    expect(find.text('Пока нет звуковых событий'), findsOneWidget);
  });

  testWidgets('keeps file and event counters independent', (tester) async {
    final gateway = FakeGateway(
      status: status(fragmentCount: 0, eventCount: 5),
    );

    await pumpPage(tester, gateway, FakePlayer());
    await tester.pump();

    expect(find.text('0'), findsOneWidget);
    expect(find.text('5'), findsOneWidget);
    expect(find.text('Пока нет звуковых событий'), findsOneWidget);
  });

  testWidgets('lists saved fragments with time, duration and size', (
    tester,
  ) async {
    final gateway = FakeGateway(
      status: status(fragmentCount: 2, eventCount: 1),
      fragments: <AudioFragment>[
        fragment(
          id: 'event_20261115_230000_000_001.wav',
          startedAt: DateTime(2026, 11, 15, 23, 0, 0),
          duration: const Duration(seconds: 21),
        ),
        fragment(
          id: 'event_20261114_220000_000_001.wav',
          startedAt: DateTime(2026, 11, 14, 22, 0, 0),
        ),
      ],
    );

    await pumpPage(tester, gateway, FakePlayer());
    await tester.pump();

    expect(find.textContaining('15.11.2026 · 23:00:00'), findsOneWidget);
    expect(find.textContaining('14.11.2026 · 22:00:00'), findsOneWidget);
    expect(find.textContaining('0:21'), findsOneWidget);
    expect(find.text('Пока нет звуковых событий'), findsNothing);
  });

  testWidgets('selecting a fragment opens the player and switches on tap', (
    tester,
  ) async {
    final first = fragment(
      id: 'event_20261115_230000_000_001.wav',
      startedAt: DateTime(2026, 11, 15, 23, 0, 0),
    );
    final second = fragment(
      id: 'event_20261114_220000_000_001.wav',
      startedAt: DateTime(2026, 11, 14, 22, 0, 0),
    );

    final gateway = FakeGateway(
      status: status(fragmentCount: 2, eventCount: 2),
      fragments: <AudioFragment>[first, second],
    );
    final player = FakePlayer();

    await pumpPage(tester, gateway, player);
    await tester.pump();

    await tester.tap(find.textContaining('15.11.2026 · 23:00:00'));
    await tester.pump();

    expect(player.calls, <String>['select:${first.id}']);
    expect(find.byType(Slider), findsOneWidget);
    expect(find.byIcon(Icons.stop_rounded), findsOneWidget);

    await tester.tap(find.textContaining('14.11.2026 · 22:00:00'));
    await tester.pump();

    expect(player.calls, <String>['select:${first.id}', 'select:${second.id}']);
    expect(player.snapshot.fragmentId, second.id);
    expect(find.byType(Slider), findsOneWidget);
  });

  testWidgets('tapping the selected fragment toggles pause', (tester) async {
    final item = fragment(
      id: 'event_20261115_230000_000_001.wav',
      startedAt: DateTime(2026, 11, 15, 23, 0, 0),
    );

    final gateway = FakeGateway(
      status: status(fragmentCount: 1, eventCount: 1),
      fragments: <AudioFragment>[item],
    );
    final player = FakePlayer();

    await pumpPage(tester, gateway, player);
    await tester.pump();

    await tester.tap(find.textContaining('15.11.2026'));
    await tester.pump();
    expect(player.snapshot.isPlaying, isTrue);

    await tester.tap(find.textContaining('15.11.2026'));
    await tester.pump();

    expect(player.calls, <String>['select:${item.id}', 'toggle']);
    expect(player.snapshot.isPlaying, isFalse);
  });

  testWidgets('shows warnings, errors and the last recording time', (
    tester,
  ) async {
    final gateway = FakeGateway(
      status: status(
        warning: 'Мало свободного места',
        error: 'Микрофон занят',
        endedAt: DateTime(2026, 11, 15, 20, 2, 0),
      ),
    );

    await pumpPage(tester, gateway, FakePlayer());
    await tester.pump();

    expect(find.text('Ошибка записи'), findsOneWidget);
    expect(find.text('Микрофон занят'), findsOneWidget);
    expect(find.text('Мало свободного места'), findsOneWidget);
    expect(
      find.textContaining('Последняя запись: 15.11.2026, 20:02'),
      findsOneWidget,
    );
  });

  testWidgets('shows a retry card when loading fails, and recovers', (
    tester,
  ) async {
    final gateway = FakeGateway(
      error: const RecordingGatewayException('Нет доступа'),
    );

    await pumpPage(tester, gateway, FakePlayer());
    await tester.pump();

    expect(find.text('Не удалось загрузить данные'), findsOneWidget);
    expect(find.text('Нет доступа'), findsOneWidget);

    gateway.error = null;

    await tester.tap(find.text('Повторить'));
    await tester.pump();
    await tester.pump();

    expect(find.text('Пока нет звуковых событий'), findsOneWidget);
  });

  testWidgets('stops playback when the selected file disappears', (
    tester,
  ) async {
    final item = fragment(
      id: 'event_20261115_230000_000_001.wav',
      startedAt: DateTime(2026, 11, 15, 23, 0, 0),
    );

    final gateway = FakeGateway(
      status: status(fragmentCount: 1, eventCount: 1),
      fragments: <AudioFragment>[item],
    );
    final player = FakePlayer();

    await pumpPage(tester, gateway, player);
    await tester.pump();

    await tester.tap(find.textContaining('15.11.2026'));
    await tester.pump();
    expect(player.snapshot.isPlaying, isTrue);

    gateway.fragments = <AudioFragment>[];
    gateway.status = status(fragmentCount: 0, eventCount: 1);

    await tester.tap(find.byTooltip('Обновить'));
    await tester.pump();
    await tester.pump();

    expect(player.calls, contains('stop'));
    expect(find.text('Пока нет звуковых событий'), findsOneWidget);
  });

  testWidgets('reloads the list when monitoring stops', (tester) async {
    final gateway = FakeGateway(
      status: status(
        isRecording: true,
        startedAt: DateTime.now().subtract(const Duration(minutes: 3)),
      ),
    );

    await pumpPage(tester, gateway, FakePlayer());
    await tester.pump();

    final callsAfterLoad = gateway.fragmentCalls;

    gateway.status = status(endedAt: DateTime.now());

    await tester.pump(const Duration(seconds: 3));
    await tester.pump();

    expect(gateway.fragmentCalls, greaterThan(callsAfterLoad));
  });
}
