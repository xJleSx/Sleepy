import 'sleep_event.dart';

enum SleepSessionStatus {
  completed,
  interrupted;

  String get wireName {
    switch (this) {
      case SleepSessionStatus.completed:
        return 'completed';
      case SleepSessionStatus.interrupted:
        return 'interrupted';
    }
  }

  static SleepSessionStatus parse(Object? raw) {
    return raw == 'interrupted'
        ? SleepSessionStatus.interrupted
        : SleepSessionStatus.completed;
  }
}

class SleepSession {
  const SleepSession({
    required this.id,
    required this.startedAt,
    required this.endedAt,
    required this.status,
    required this.events,
    this.warning = '',
    this.error = '',
  });

  final String id;
  final DateTime startedAt;
  final DateTime endedAt;
  final SleepSessionStatus status;
  final List<SleepEvent> events;
  final String warning;
  final String error;

  Duration get duration {
    final value = endedAt.difference(startedAt);
    return value.isNegative ? Duration.zero : value;
  }

  int get eventCount => events.length;

  bool get isComplete => status == SleepSessionStatus.completed;

  Map<String, Object?> toJson() {
    return <String, Object?>{
      'id': id,
      'startedAtMillis': startedAt.millisecondsSinceEpoch,
      'endedAtMillis': endedAt.millisecondsSinceEpoch,
      'status': status.wireName,
      'events': events.map((event) => event.toJson()).toList(),
      'warning': warning,
      'error': error,
    };
  }

  static SleepSession? tryParse(Object? raw) {
    if (raw is! Map) return null;

    final id = raw['id'];
    final startedAtMillis = raw['startedAtMillis'];
    final endedAtMillis = raw['endedAtMillis'];
    final rawEvents = raw['events'];

    if (id is! String || id.isEmpty) return null;
    if (startedAtMillis is! num || startedAtMillis <= 0) return null;
    if (endedAtMillis is! num || endedAtMillis <= 0) return null;
    if (endedAtMillis <= startedAtMillis) return null;
    if (rawEvents is! List) return null;

    final events = rawEvents
        .map(SleepEvent.tryParse)
        .whereType<SleepEvent>()
        .toList()
      ..sort((left, right) => left.startedAt.compareTo(right.startedAt));

    final warning = raw['warning'];
    final error = raw['error'];

    return SleepSession(
      id: id,
      startedAt: DateTime.fromMillisecondsSinceEpoch(startedAtMillis.toInt()),
      endedAt: DateTime.fromMillisecondsSinceEpoch(endedAtMillis.toInt()),
      status: SleepSessionStatus.parse(raw['status']),
      events: List<SleepEvent>.unmodifiable(events),
      warning: warning is String ? warning : '',
      error: error is String ? error : '',
    );
  }
}

class ActiveSleepSession {
  const ActiveSleepSession({required this.id, required this.startedAt});

  final String id;
  final DateTime startedAt;

  Map<String, Object?> toJson() {
    return <String, Object?>{
      'id': id,
      'startedAtMillis': startedAt.millisecondsSinceEpoch,
    };
  }

  static ActiveSleepSession? tryParse(Object? raw) {
    if (raw is! Map) return null;

    final id = raw['id'];
    final startedAtMillis = raw['startedAtMillis'];
    if (id is! String || id.isEmpty) return null;
    if (startedAtMillis is! num || startedAtMillis <= 0) return null;

    return ActiveSleepSession(
      id: id,
      startedAt: DateTime.fromMillisecondsSinceEpoch(startedAtMillis.toInt()),
    );
  }
}
