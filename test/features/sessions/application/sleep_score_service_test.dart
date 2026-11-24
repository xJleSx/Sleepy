import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/features/sessions/application/sleep_score_service.dart';
import 'package:sleepy/features/sessions/domain/sleep_session.dart';

void main() {
  const service = SleepScoreService();

  test('scores eight hours as full duration without false depth', () {
    final session = _session(
      start: DateTime(2026, 11, 23, 23),
      end: DateTime(2026, 11, 24, 7),
    );

    final score = service.calculate(
      session: session,
      history: <SleepSession>[session],
    );

    expect(score.duration.roundedValue, 100);
    expect(score.overall, 100);
    expect(score.provisional, isTrue);
    expect(score.depth.isAvailable, isFalse);
    expect(score.depth.value, isNull);
    expect(score.efficiency.isAvailable, isFalse);
  });

  test('scores six hours below the target range', () {
    final session = _session(
      start: DateTime(2026, 11, 23, 23),
      end: DateTime(2026, 11, 24, 5),
    );

    final score = service.calculate(
      session: session,
      history: <SleepSession>[session],
    );

    expect(score.duration.roundedValue, 86);
    expect(score.overall, 86);
  });

  test('penalizes monitoring longer than nine hours', () {
    final session = _session(
      start: DateTime(2026, 11, 23, 22),
      end: DateTime(2026, 11, 24, 9),
    );

    final score = service.calculate(
      session: session,
      history: <SleepSession>[session],
    );

    expect(score.duration.roundedValue, 70);
  });

  test('regularity becomes available after three nights', () {
    final sessions = <SleepSession>[
      _session(
        start: DateTime(2026, 11, 20, 23),
        end: DateTime(2026, 11, 21, 7),
      ),
      _session(
        start: DateTime(2026, 11, 21, 23),
        end: DateTime(2026, 11, 22, 7),
      ),
      _session(
        start: DateTime(2026, 11, 22, 23),
        end: DateTime(2026, 11, 23, 7),
      ),
      _session(
        start: DateTime(2026, 11, 23, 23),
        end: DateTime(2026, 11, 24, 7),
      ),
    ];

    final score = service.calculate(
      session: sessions.last,
      history: sessions,
    );

    expect(score.regularity.isAvailable, isTrue);
    expect(score.regularity.roundedValue, 100);
    expect(score.overall, 100);
    expect(score.provisional, isFalse);
  });

  test('ignores interrupted sessions when calculating regularity', () {
    final sessions = <SleepSession>[
      _session(
        start: DateTime(2026, 11, 21, 23),
        end: DateTime(2026, 11, 22, 7),
      ),
      _session(
        start: DateTime(2026, 11, 22, 23),
        end: DateTime(2026, 11, 23, 7),
      ),
      _session(
        start: DateTime(2026, 11, 23, 23),
        end: DateTime(2026, 11, 24, 7),
        status: SleepSessionStatus.interrupted,
      ),
    ];

    final score = service.calculate(
      session: sessions.last,
      history: sessions,
    );

    expect(score.regularity.isAvailable, isFalse);
    expect(score.provisional, isTrue);
  });

  test('regularity stays unavailable for two nights', () {
    final sessions = <SleepSession>[
      _session(
        start: DateTime(2026, 11, 22, 23),
        end: DateTime(2026, 11, 23, 7),
      ),
      _session(
        start: DateTime(2026, 11, 23, 23),
        end: DateTime(2026, 11, 24, 7),
      ),
    ];

    final score = service.calculate(
      session: sessions.last,
      history: sessions,
    );

    expect(score.regularity.isAvailable, isFalse);
    expect(score.regularity.explanation, contains('минимум 3'));
    expect(score.provisional, isTrue);
  });
}

SleepSession _session({
  required DateTime start,
  required DateTime end,
  SleepSessionStatus status = SleepSessionStatus.completed,
}) {
  return SleepSession(
    id: 'session_${start.millisecondsSinceEpoch}',
    startedAt: start,
    endedAt: end,
    status: status,
    events: const [],
  );
}
