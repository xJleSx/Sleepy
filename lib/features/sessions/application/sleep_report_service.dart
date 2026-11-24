import '../domain/sleep_event.dart';
import '../domain/sleep_report.dart';
import '../domain/sleep_score.dart';
import '../domain/sleep_session.dart';
import 'sleep_score_service.dart';

class SleepReportService {
  const SleepReportService({
    this.scoreService = const SleepScoreService(),
  });

  final SleepScoreService scoreService;

  SleepReport build({
    required SleepSession session,
    required List<SleepSession> allSessions,
  }) {
    final others = allSessions
        .where((item) => item.id != session.id)
        .toList()
      ..sort((left, right) => left.endedAt.compareTo(right.endedAt));

    final previous = others
        .where((item) => item.endedAt.isBefore(session.endedAt))
        .toList();
    final previousSession = previous.isEmpty ? null : previous.last;
    final recent = previous.length <= 7
        ? previous
        : previous.sublist(previous.length - 7);

    Duration? average;
    if (recent.isNotEmpty) {
      final total = recent.fold<int>(
        0,
        (sum, item) => sum + item.duration.inSeconds,
      );
      average = Duration(seconds: (total / recent.length).round());
    }

    final score = scoreService.calculate(
      session: session,
      history: allSessions,
    );

    return SleepReport(
      session: session,
      score: score,
      sleepOnset: null,
      awakenings: null,
      distribution: _distribution(session),
      interestingEvents: _interestingEvents(session.events),
      comparison: SleepComparison(
        previousDuration: previousSession?.duration,
        sevenNightAverage: average,
        sessionsCompared: recent.length,
      ),
      recommendations: _recommendations(session, score, previousSession),
      dataNote:
          'Sleepy фиксирует звуковые события и длительность мониторинга. '
          'Приложение не измеряет стадии сна, EEG или PSG и не ставит диагнозы.',
    );
  }

  List<SleepEventBucket> _distribution(SleepSession session) {
    final start = DateTime(
      session.startedAt.year,
      session.startedAt.month,
      session.startedAt.day,
      session.startedAt.hour,
    );
    final end = session.endedAt;
    final buckets = <SleepEventBucket>[];

    var cursor = start;
    while (cursor.isBefore(end) && buckets.length < 16) {
      final next = cursor.add(const Duration(hours: 1));
      final count = session.events
          .where(
            (event) =>
                !event.startedAt.isBefore(cursor) &&
                event.startedAt.isBefore(next),
          )
          .length;
      buckets.add(
        SleepEventBucket(start: cursor, end: next, count: count),
      );
      cursor = next;
    }

    return buckets;
  }

  List<SleepEvent> _interestingEvents(List<SleepEvent> events) {
    final sorted = List<SleepEvent>.of(events)
      ..sort((left, right) {
        final byDuration = right.duration.compareTo(left.duration);
        if (byDuration != 0) return byDuration;
        return left.startedAt.compareTo(right.startedAt);
      });
    return List<SleepEvent>.unmodifiable(sorted.take(5));
  }

  List<String> _recommendations(
    SleepSession session,
    SleepScore score,
    SleepSession? previous,
  ) {
    final hours = session.duration.inMinutes / 60;
    final result = <String>[];

    if (hours < 7) {
      result.add(
        'Сон короче 7 часов. Попробуйте сдвинуть отход ко сну раньше '
        'на 20-30 минут.',
      );
    } else if (hours > 9) {
      result.add(
        'Мониторинг длился дольше 9 часов. Проверьте, совпадает ли это '
        'с фактическим временем сна.',
      );
    } else {
      result.add(
        'Продолжительность близка к ориентиру 7-9 часов. '
        'Сохраняйте стабильное время пробуждения.',
      );
    }

    final regularity = score.regularity;
    if (!regularity.isAvailable) {
      result.add(
        'Для оценки регулярности нужно минимум 3 завершённые ночи.',
      );
    }

    if (session.eventCount > 0) {
      result.add(
        'Обнаружены звуковые события, но их тип не классифицирован. '
        'Это не доказательство храпа или апноэ.',
      );
    } else {
      result.add(
        'За ночь не обнаружено звуковых событий, прошедших детектор.',
      );
    }

    if (previous != null) {
      final delta = session.duration.inMinutes - previous.duration.inMinutes;
      if (delta.abs() >= 60) {
        result.add(
          'Продолжительность отличается от предыдущей ночи '
          'на ${delta.abs() ~/ 60} ч ${delta.abs() % 60} мин.',
        );
      }
    }

    return List<String>.unmodifiable(result);
  }
}
