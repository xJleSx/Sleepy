import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../events/data/recording_gateway.dart';
import '../../events/domain/recording_status.dart';
import '../../sessions/application/sleep_session_controller.dart';
import '../../sessions/domain/sleep_session.dart';

class SleepMonitorException implements Exception {
  const SleepMonitorException(this.message);

  final String message;

  @override
  String toString() => message;
}

class SleepMonitorController extends ChangeNotifier {
  SleepMonitorController({
    required this._gateway,
    required this._control,
    required this._sessions,
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now;

  final RecordingGateway _gateway;
  final RecordingControlGateway _control;
  final SleepSessionController _sessions;
  final DateTime Function() _now;

  RecordingStatus _status = RecordingStatus.empty;
  bool _initializing = false;
  bool _initialized = false;
  bool _starting = false;
  bool _stopping = false;
  bool _refreshing = false;
  String? _error;
  SleepSession? _lastCompletedSession;
  Timer? _ticker;

  RecordingStatus get status => _status;
  bool get isRecording => _status.isRecording;
  bool get isStarting => _starting;
  bool get isStopping => _stopping;
  bool get isBusy => _starting || _stopping || _refreshing;
  bool get isInitializing => _initializing;
  String? get error => _error;
  SleepSession? get lastCompletedSession => _lastCompletedSession;
  SleepSessionController get sessions => _sessions;

  Duration get elapsed {
    final startedAt = _status.startedAt;
    if (!_status.isRecording || startedAt == null) return Duration.zero;
    final value = _now().difference(startedAt);
    return value.isNegative ? Duration.zero : value;
  }

  Future<void> initialize() async {
    if (_initialized || _initializing) return;

    _initializing = true;
    notifyListeners();

    try {
      await _sessions.load();
      _status = await _gateway.fetchStatus();

      if (_status.isRecording) {
        await _sessions.start(_status.startedAt ?? _now());
        _startTicker();
      } else {
        await _finalizeIfFinished();
      }

      _initialized = true;
      _error = null;
    } on RecordingGatewayException catch (exception) {
      _error = exception.message;
    } on SleepMonitorException catch (exception) {
      _error = exception.message;
    } finally {
      _initializing = false;
      notifyListeners();
    }
  }

  Future<void> refresh() async {
    if (_refreshing) return;

    _refreshing = true;
    try {
      final previous = _status;
      final next = await _gateway.fetchStatus();
      _status = next;
      _error = null;

      if (next.isRecording) {
        if (_sessions.activeSession == null) {
          await _sessions.start(next.startedAt ?? _now());
        }
        _startTicker();
      } else {
        _ticker?.cancel();
        _ticker = null;
        if (previous.isRecording) {
          await _finalizeIfFinished();
        }
      }
    } on RecordingGatewayException catch (exception) {
      _error = exception.message;
    } finally {
      _refreshing = false;
      notifyListeners();
    }
  }

  Future<void> start() async {
    if (_starting || _stopping) {
      throw const SleepMonitorException('Операция уже выполняется');
    }

    _starting = true;
    _error = null;
    notifyListeners();

    try {
      final granted = await _control.requestMicrophonePermission();
      if (!granted) {
        throw const SleepMonitorException(
          'Для мониторинга нужно разрешение на микрофон',
        );
      }

      await _control.startRecording();

      for (var attempt = 0; attempt < 12; attempt++) {
        await Future<void>.delayed(const Duration(milliseconds: 500));
        _status = await _gateway.fetchStatus();
        if (_status.isRecording) break;
      }

      if (!_status.isRecording) {
        throw const SleepMonitorException(
          'Служба не начала запись. Проверьте разрешение на микрофон.',
        );
      }

      await _sessions.start(_status.startedAt ?? _now());
      _startTicker();
      _error = null;
      notifyListeners();
    } on RecordingGatewayException catch (exception) {
      _error = exception.message;
      throw SleepMonitorException(exception.message);
    } finally {
      _starting = false;
      notifyListeners();
    }
  }

  Future<SleepSession> stopAndFinalize() async {
    if (_starting || _stopping) {
      throw const SleepMonitorException('Операция уже выполняется');
    }

    _stopping = true;
    _error = null;
    notifyListeners();

    try {
      await _control.stopRecording();

      for (var attempt = 0; attempt < 30; attempt++) {
        await Future<void>.delayed(const Duration(milliseconds: 500));
        _status = await _gateway.fetchStatus();
        if (!_status.isRecording) break;
      }

      if (_status.isRecording) {
        throw const SleepMonitorException(
          'Служба ещё завершает запись. Откройте экран позже.',
        );
      }

      final fragments = await _gateway.fetchFragments();
      final endedAt = _status.endedAt ?? _now();
      final session = await _sessions.finalize(
        endedAt: endedAt,
        status: _status,
        fragments: fragments,
      );

      if (session == null) {
        throw const SleepMonitorException(
          'Нет активной сессии для формирования отчёта',
        );
      }

      _lastCompletedSession = session;
      _ticker?.cancel();
      _ticker = null;
      return session;
    } on RecordingGatewayException catch (exception) {
      _error = exception.message;
      throw SleepMonitorException(exception.message);
    } finally {
      _stopping = false;
      notifyListeners();
    }
  }

  Future<void> syncAfterResume() async {
    await _sessions.load();
    await refresh();
  }

  Future<SleepSession?> _finalizeIfFinished() async {
    final active = _sessions.activeSession;
    final endedAt = _status.endedAt;

    if (active == null || endedAt == null) return null;
    if (!endedAt.isAfter(active.startedAt)) return null;

    try {
      final fragments = await _gateway.fetchFragments();
      final session = await _sessions.finalize(
        endedAt: endedAt,
        status: _status,
        fragments: fragments,
      );
      _lastCompletedSession = session;
      return session;
    } on RecordingGatewayException catch (exception) {
      _error = exception.message;
      return null;
    }
  }

  void _startTicker() {
    _ticker?.cancel();
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!_status.isRecording) return;
      notifyListeners();
    });
  }

  @override
  void dispose() {
    _ticker?.cancel();
    super.dispose();
  }
}
