
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/main.dart';

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

  testWidgets('Sleepy app launches successfully', (WidgetTester tester) async {
    await tester.pumpWidget(const SleepyApp());

    expect(find.text('Sleepy'), findsOneWidget);
    expect(find.text('Доброй ночи'), findsOneWidget);
    expect(find.text('Готовы отдохнуть?'), findsOneWidget);
  });

  testWidgets('Bottom navigation works', (WidgetTester tester) async {
    await tester.pumpWidget(const SleepyApp());

    // Переход на вкладку аналитики.
    await tester.tap(find.text('Анализ'));
    await tester.pumpAndSettle();

    expect(find.text('Статистика, показатели и оценка качества сна'), findsOneWidget);

    // Переход на вкладку истории.
    await tester.tap(find.text('История').last);
    await tester.pumpAndSettle();

    expect(find.text('Аудиособытия'), findsOneWidget);
    expect(find.text('Пока нет аудиособытий'), findsOneWidget);
  });
}
