import 'package:flutter/foundation.dart';

import '../../events/domain/audio_fragment.dart';
import '../../events/domain/recording_status.dart';
import '../data/sleep_session_repository.dart';
import '../domain/sleep_event.dart';
import '../domain/sleep_session.dart';

class SleepSessionController extends ChangeNotifier {
  SleepSessionController({
    required this._repository,
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now;

  final SleepSessionRepository _repository;
  final DateTime Function() _now;

  List<SleepSession> _sessions = const <SleepSession>[];
  ActiveSleepSession? _activeSession;
  bool _loading = false;
  bool _loaded = false;
  String? _error;

  List<SleepSession> get sessions => _sessions;
  ActiveSleepSession? get activeSession => _activeSession;
  bool get isLoading => _loading;
  bool get isLoaded => _loaded;
  String? get error => _error;
  SleepSession? get lastSession =>
      _sessions.isEmpty ? null : _sessions.first;

  Future<void> load({bool force = false}) async {
    if (_loading || (_loaded && !force)) return;

    _loading = true;
    _error = null;
    notifyListeners();

    try {
      final sessions = await _repository.loadSessions();
      final active = await _repository.loadActiveSession();

      _sessions = List<SleepSession>.unmodifiable(sessions);
      _activeSession = active;
      _loaded = true;
    } on SleepSessionRepositoryException catch (exception) {
      _error = exception.message;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<ActiveSleepSession> start(DateTime startedAt) async {
    final existing = _activeSession;
    if (existing != null) return existing;

    final active = ActiveSleepSession(
      id: 'sleep_${startedAt.millisecondsSinceEpoch}',
      startedAt: startedAt,
    );

    await _repository.saveActiveSession(active);
    _activeSession = active;
    _error = null;
    notifyListeners();
    return active;
  }

  Future<SleepSession?> finalize({
    required DateTime endedAt,
    required RecordingStatus status,
    required List<AudioFragment> fragments,
  }) async {
    final active = _activeSession;
    if (active == null) return null;

    final safeEnd = endedAt.isAfter(active.startedAt) ? endedAt : _now();
    if (!safeEnd.isAfter(active.startedAt)) return null;

    final windowStart = active.startedAt.subtract(const Duration(seconds: 15));
    final windowEnd = safeEnd.add(const Duration(seconds: 15));
    final events = fragments
        .where(
          (fragment) =>
              !fragment.startedAt.isBefore(windowStart) &&
              !fragment.startedAt.isAfter(windowEnd),
        )
        .map(
          (fragment) => SleepEvent(
            id: fragment.id,
            fileName: fragment.fileName,
            filePath: fragment.filePath,
            startedAt: fragment.startedAt,
            duration: fragment.duration,
            sizeBytes: fragment.sizeBytes,
            truncated: fragment.truncated,
          ),
        )
        .toList()
      ..sort((left, right) => left.startedAt.compareTo(right.startedAt));

    final sessionStatus =
        status.interrupted || status.hasError || status.hasWarning
        ? SleepSessionStatus.interrupted
        : SleepSessionStatus.completed;

    final session = SleepSession(
      id: active.id,
      startedAt: active.startedAt,
      endedAt: safeEnd,
      status: sessionStatus,
      events: List<SleepEvent>.unmodifiable(events),
      warning: status.lastWarning,
      error: status.error,
    );

    final next = <SleepSession>[
      session,
      ..._sessions.where((item) => item.id != session.id),
    ]..sort((left, right) => right.startedAt.compareTo(left.startedAt));

    await _repository.saveSessions(next);
    await _repository.clearActiveSession();

    _sessions = List<SleepSession>.unmodifiable(next);
    _activeSession = null;
    _error = null;
    notifyListeners();
    return session;
  }

  Future<void> reload() => load(force: true);

  SleepSession? findById(String id) {
    for (final session in _sessions) {
      if (session.id == id) return session;
    }
    return null;
  }
}
