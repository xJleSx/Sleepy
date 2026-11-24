import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/features/sessions/application/sleep_report_service.dart';
import 'package:sleepy/features/sessions/domain/sleep_event.dart';
import 'package:sleepy/features/sessions/domain/sleep_session.dart';

void main() {
  const service = SleepReportService();

  test('builds an honest report with comparison and no event type', () {
    final previous = _session(
      id: 'previous',
      start: DateTime(2026, 11, 22, 23),
      end: DateTime(2026, 11, 23, 8),
    );
    final current = _session(
      id: 'current',
      start: DateTime(2026, 11, 23, 23),
      end: DateTime(2026, 11, 24, 7),
      events: <SleepEvent>[
        _event('a', DateTime(2026, 11, 24, 1, 10), const Duration(seconds: 4)),
        _event('b', DateTime(2026, 11, 24, 3, 40), const Duration(seconds: 9)),
      ],
    );

    final report = service.build(
      session: current,
      allSessions: <SleepSession>[current, previous],
    );

    expect(report.eventCount, 2);
    expect(report.comparison.previousDuration, const Duration(hours: 9));
    expect(report.comparison.sevenNightAverage, const Duration(hours: 9));
    expect(report.comparison.sessionsCompared, 1);
    expect(report.distribution, isNotEmpty);
    expect(report.interestingEvents.first.id, 'b');
    expect(
      report.recommendations.join(' '),
      allOf(contains('7-9'), contains('тип не классифицирован')),
    );
    expect(report.dataNote, contains('не измеряет стадии сна'));
    expect(report.sleepOnset, isNull);
    expect(report.awakenings, isNull);
  });

  test('marks a short night and avoids claiming a diagnosis', () {
    final session = _session(
      id: 'short',
      start: DateTime(2026, 11, 23, 23),
      end: DateTime(2026, 11, 24, 4),
    );

    final report = service.build(
      session: session,
      allSessions: <SleepSession>[session],
    );

    expect(report.recommendations.join(' '), contains('короче 7 часов'));
    expect(report.comparison.previousDuration, isNull);
    expect(report.comparison.sevenNightAverage, isNull);
    expect(report.comparison.sessionsCompared, 0);
  });
}

SleepSession _session({
  required String id,
  required DateTime start,
  required DateTime end,
  List<SleepEvent> events = const <SleepEvent>[],
}) {
  return SleepSession(
    id: id,
    startedAt: start,
    endedAt: end,
    status: SleepSessionStatus.completed,
    events: events,
  );
}

SleepEvent _event(String id, DateTime start, Duration duration) {
  return SleepEvent(
    id: id,
    fileName: '$id.wav',
    filePath: '/tmp/$id.wav',
    startedAt: start,
    duration: duration,
    sizeBytes: 1024,
  );
}
