import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/app/sleepy_services.dart';
import 'package:sleepy/core/storage/local_key_value_store.dart';
import 'package:sleepy/features/alarms/data/alarm_scheduler.dart';
import 'package:sleepy/features/alarms/domain/sleep_alarm.dart';
import 'package:sleepy/main.dart';

class FakeAlarmScheduler implements AlarmScheduler {
  @override
  Future<bool> canScheduleExactAlarms() async => false;

  @override
  Future<void> requestExactAlarmPermission() async {}

  @override
  Future<void> cancel(String alarmId) async {}

  @override
  Future<AlarmScheduleResult> schedule(
    SleepAlarm alarm,
    DateTime triggerAt,
  ) async {
    return AlarmScheduleResult(
      scheduled: true,
      exact: false,
      triggerAt: triggerAt,
      message: '',
    );
  }
}

void main() {
  const channel = MethodChannel('com.sleepy.app.sleepy/recording');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      switch (call.method) {
        case 'getRecordingStatus':
          return <String, dynamic>{
            'isRecording': false,
            'filePath': '',
            'startedAtMillis': 0,
            'endedAtMillis': 0,
            'error': '',
            'fragmentCount': 0,
            'eventCount': 0,
          };
        case 'listFragments':
          return <Object?>[];
        case 'getRecordingState':
          return <String, dynamic>{};
        default:
          return true;
      }
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  SleepyServices buildServices() {
    return SleepyServices(
      store: MemoryLocalKeyValueStore(),
      alarmScheduler: FakeAlarmScheduler(),
    );
  }

  testWidgets('Sleepy app launches successfully', (WidgetTester tester) async {
    final services = buildServices();
    addTearDown(services.dispose);
    await tester.pumpWidget(SleepyApp(services: services));
    await tester.pumpAndSettle();

    expect(find.text('Sleepy'), findsOneWidget);
    expect(find.text('Начните мониторинг сна'), findsOneWidget);
    expect(find.text('Начать сон'), findsOneWidget);
  });

  testWidgets('Bottom navigation works', (WidgetTester tester) async {
    final services = buildServices();
    addTearDown(services.dispose);
    await tester.pumpWidget(SleepyApp(services: services));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Анализ'));
    await tester.pumpAndSettle();

    expect(find.text('Тенденции по завершённым сессиям'), findsOneWidget);
    expect(
      find.text('Статистика появится после первой ночи'),
      findsOneWidget,
    );

    await tester.tap(find.text('История').last);
    await tester.pumpAndSettle();

    expect(find.text('История сна'), findsOneWidget);
    expect(find.text('Завершённых сессий пока нет'), findsOneWidget);

    await tester.tap(find.text('Звуки'));
    await tester.pumpAndSettle();

    expect(find.text('Звуки сна'), findsOneWidget);
    expect(find.text('Пока нет звуковых событий'), findsOneWidget);
  });
}
