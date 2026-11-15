
import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/main.dart';

void main() {
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

    expect(
      find.text('Архив ночных сессий и сравнение результатов'),
      findsOneWidget,
    );
  });
}
