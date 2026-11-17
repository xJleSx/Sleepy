import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:sleepy/app/theme/app_theme.dart';
import 'package:sleepy/features/diagnostics/domain/diagnostic_audio_file.dart';
import 'package:sleepy/features/diagnostics/presentation/diagnostics_page.dart';
import 'package:sleepy/features/events/data/recording_gateway.dart';
import 'package:sleepy/features/events/domain/audio_fragment.dart';
import 'package:sleepy/features/events/domain/recording_status.dart';

class FakeGateway implements RecordingGateway {
  FakeGateway({
    RecordingStatus? status,
    List<DiagnosticAudioFile>? files,
    this.error,
  }) : status = status ?? RecordingStatus.empty,
       files = files ?? <DiagnosticAudioFile>[];

  RecordingStatus status;
  List<DiagnosticAudioFile> files;
  Object? error;

  int statusCalls = 0;
  int fileCalls = 0;

  @override
  Future<RecordingStatus> fetchStatus() async {
    statusCalls++;

    if (error != null) throw error!;
    return status;
  }

  @override
  Future<List<DiagnosticAudioFile>> fetchDiagnosticFiles() async {
    fileCalls++;

    if (error != null) throw error!;
    return List<DiagnosticAudioFile>.of(files);
  }

  @override
  Future<List<AudioFragment>> fetchFragments() async {
    return const <AudioFragment>[];
  }
}

void main() {
  final now = DateTime(2026, 11, 17, 1, 30);

  Future<void> setTallSurface(WidgetTester tester) async {
    await tester.binding.setSurfaceSize(const Size(1000, 3200));
    addTearDown(() => tester.binding.setSurfaceSize(null));
  }

  Future<void> pumpPage(
    WidgetTester tester,
    FakeGateway gateway, {
    Future<void> Function(String text)? copyText,
    Duration statusRefreshInterval = const Duration(seconds: 3),
    Duration fileRefreshInterval = const Duration(seconds: 15),
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: sleepyTheme,
        home: DiagnosticsPage(
          gateway: gateway,
          statusRefreshInterval: statusRefreshInterval,
          fileRefreshInterval: fileRefreshInterval,
          copyText: copyText,
          now: () => now,
        ),
      ),
    );
    await tester.pump();
  }

  testWidgets('shows the active session diagnostics', (tester) async {
    await setTallSurface(tester);
    final gateway = FakeGateway(
      status: RecordingStatus(
        isRecording: true,
        eventCount: 4,
        fragmentCount: 4,
        lastWarning: '',
        error: '',
        interrupted: false,
        startedAt: now.subtract(const Duration(minutes: 1, seconds: 5)),
        endedAt: null,
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
        currentDetectorState: 'ACTIVE',
        lastFragmentPath: '/data/events/event_001.wav',
      ),
    );

    await pumpPage(tester, gateway);

    expect(find.text('Запись активна'), findsOneWidget);
    expect(find.text('00:01:05'), findsOneWidget);
    expect(find.text('1234567'), findsOneWidget);
    expect(find.text('−54.2 дБFS'), findsOneWidget);
    expect(find.text('нет сигнала'), findsOneWidget);
    expect(find.text('ACTIVE · событие'), findsOneWidget);
    expect(find.text('event_001.wav'), findsOneWidget);
    expect(find.textContaining('/data/events'), findsNothing);
    expect(find.text('Файлов пока нет'), findsOneWidget);
  });

  testWidgets('shows absent diagnostics instead of fake zeros', (tester) async {
    await setTallSurface(tester);
    final gateway = FakeGateway(status: RecordingStatus.empty);

    await pumpPage(tester, gateway);

    expect(find.text('Запись остановлена'), findsOneWidget);
    expect(find.text('—'), findsWidgets);
    expect(find.text('1234567'), findsNothing);
  });

  testWidgets('shows an actionable error when the channel fails', (
    tester,
  ) async {
    await setTallSurface(tester);
    final gateway = FakeGateway(
      error: const RecordingGatewayException('Канал диагностики недоступен'),
    );

    await pumpPage(tester, gateway);

    expect(find.text('Диагностика ещё не получена'), findsOneWidget);
    expect(find.text('Канал диагностики недоступен'), findsOneWidget);
    expect(find.text('Повторить'), findsOneWidget);
  });

  testWidgets('separates valid empty corrupted and failed WAV files', (
    tester,
  ) async {
    await setTallSurface(tester);
    final gateway = FakeGateway(
      files: <DiagnosticAudioFile>[
        DiagnosticAudioFile(
          fileName: 'event_valid.wav',
          startedAt: now,
          duration: const Duration(seconds: 21),
          sizeBytes: 672044,
          status: WavFileStatus.valid,
          reason: '',
        ),
        DiagnosticAudioFile(
          fileName: 'event_empty.wav',
          startedAt: null,
          duration: null,
          sizeBytes: null,
          status: WavFileStatus.empty,
          reason: 'Нет аудиоданных',
        ),
        DiagnosticAudioFile(
          fileName: 'event_corrupted.wav',
          startedAt: null,
          duration: null,
          sizeBytes: null,
          status: WavFileStatus.corrupted,
          reason: 'Обрезанный data-чанк',
        ),
        DiagnosticAudioFile(
          fileName: 'event_failed.wav',
          startedAt: null,
          duration: null,
          sizeBytes: null,
          status: WavFileStatus.failed,
          reason: 'Нет доступа',
        ),
      ],
    );

    await pumpPage(tester, gateway);

    expect(find.text('КОРРЕКТНЫЕ · 1'), findsOneWidget);
    expect(find.text('ПУСТЫЕ · 1'), findsOneWidget);
    expect(find.text('ПОВРЕЖДЁННЫЕ · 1'), findsOneWidget);
    expect(find.text('НЕ ПРОШЛИ ПРОВЕРКУ · 1'), findsOneWidget);
    expect(find.text('event_valid.wav'), findsOneWidget);
    expect(find.text('event_corrupted.wav'), findsOneWidget);
    expect(find.text('Обрезанный data-чанк'), findsOneWidget);
    expect(find.text('Корректный'), findsOneWidget);
    expect(find.text('Пустой'), findsOneWidget);
    expect(find.text('Повреждённый'), findsOneWidget);
    expect(find.text('Не прошёл проверку'), findsOneWidget);
  });

  testWidgets('copies a text report without absolute paths', (tester) async {
    await setTallSurface(tester);
    String? copied;
    final gateway = FakeGateway(
      status: RecordingStatus(
        isRecording: false,
        eventCount: 1,
        fragmentCount: 1,
        lastWarning: '',
        error: '',
        interrupted: false,
        startedAt: now.subtract(const Duration(seconds: 21)),
        endedAt: now,
        lastFragmentAt: null,
        samplesRead: 100,
        lastFragmentPath: '/data/user/0/app/event_001.wav',
      ),
      files: <DiagnosticAudioFile>[
        DiagnosticAudioFile(
          fileName: 'event_001.wav',
          startedAt: now,
          duration: const Duration(seconds: 21),
          sizeBytes: 672044,
          status: WavFileStatus.valid,
          reason: '',
        ),
      ],
    );

    await pumpPage(
      tester,
      gateway,
      copyText: (text) async {
        copied = text;
      },
    );

    await tester.ensureVisible(find.text('Скопировать отчёт'));
    await tester.tap(find.text('Скопировать отчёт'));
    await tester.pump();

    expect(copied, isNotNull);
    expect(copied, contains('Sleepy · диагностика аудиозаписи'));
    expect(copied, contains('event_001.wav'));
    expect(copied, contains('lastFragmentPath: event_001.wav'));
    expect(copied, isNot(contains('/data/')));
    expect(find.text('Отчёт скопирован'), findsOneWidget);
  });

  testWidgets('polls status and stops timers after dispose', (tester) async {
    await setTallSurface(tester);
    final gateway = FakeGateway();

    await pumpPage(tester, gateway);
    final callsAfterLoad = gateway.statusCalls;

    await tester.pump(const Duration(seconds: 3));
    await tester.pump();

    expect(gateway.statusCalls, greaterThan(callsAfterLoad));

    await tester.pumpWidget(const MaterialApp(home: SizedBox()));
    final callsAfterDispose = gateway.statusCalls;

    await tester.pump(const Duration(seconds: 10));

    expect(gateway.statusCalls, callsAfterDispose);
  });
}
