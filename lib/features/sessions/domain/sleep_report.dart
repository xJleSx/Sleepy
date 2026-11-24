import 'sleep_event.dart';
import 'sleep_score.dart';
import 'sleep_session.dart';

class SleepEventBucket {
  const SleepEventBucket({
    required this.start,
    required this.end,
    required this.count,
  });

  final DateTime start;
  final DateTime end;
  final int count;
}

class SleepComparison {
  const SleepComparison({
    required this.previousDuration,
    required this.sevenNightAverage,
    required this.sessionsCompared,
  });

  final Duration? previousDuration;
  final Duration? sevenNightAverage;
  final int sessionsCompared;
}

class SleepReport {
  const SleepReport({
    required this.session,
    required this.score,
    required this.sleepOnset,
    required this.awakenings,
    required this.distribution,
    required this.interestingEvents,
    required this.comparison,
    required this.recommendations,
    required this.dataNote,
  });

  final SleepSession session;
  final SleepScore score;
  final DateTime? sleepOnset;
  final int? awakenings;
  final List<SleepEventBucket> distribution;
  final List<SleepEvent> interestingEvents;
  final SleepComparison comparison;
  final List<String> recommendations;
  final String dataNote;

  Duration get duration => session.duration;
  DateTime get startedAt => session.startedAt;
  DateTime get endedAt => session.endedAt;
  int get eventCount => session.eventCount;
}
