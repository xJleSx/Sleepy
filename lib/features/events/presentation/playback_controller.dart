import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

import '../domain/audio_fragment.dart';

/// Снимок состояния плеера для отрисовки интерфейса.
class PlaybackSnapshot {
  const PlaybackSnapshot({
    this.fragmentId,
    this.isPlaying = false,
    this.isPreparing = false,
    this.position = Duration.zero,
    this.duration = Duration.zero,
    this.error,
  });

  static const idle = PlaybackSnapshot();

  final String? fragmentId;
  final bool isPlaying;
  final bool isPreparing;
  final Duration position;
  final Duration duration;
  final String? error;

  bool get hasSelection => fragmentId != null;

  PlaybackSnapshot copyWith({
    bool? isPlaying,
    bool? isPreparing,
    Duration? position,
    Duration? duration,
    String? error,
    bool clearError = false,
  }) {
    return PlaybackSnapshot(
      fragmentId: fragmentId,
      isPlaying: isPlaying ?? this.isPlaying,
      isPreparing: isPreparing ?? this.isPreparing,
      position: position ?? this.position,
      duration: duration ?? this.duration,
      error: clearError ? null : (error ?? this.error),
    );
  }
}

/// Управление воспроизведением одного выбранного фрагмента.
///
/// Интерфейс отделён от платформы, чтобы экран тестировался без
/// реального звука.
abstract class AudioPlaybackController extends ChangeNotifier {
  PlaybackSnapshot get snapshot;

  Future<void> select(AudioFragment fragment);

  Future<void> togglePlayPause();

  Future<void> stop();

  Future<void> seek(Duration position);

  Future<void> pause();
}

/// Плеер поверх нативного `MediaPlayer` через канал записи.
///
/// Позиция и состояние забираются опросом: это дешевле и надёжнее, чем
/// двусторонние колбэки, а задержка в четверть секунды незаметна для
/// прослушивания фрагментов длиной в десятки секунд.
class ChannelPlaybackController extends AudioPlaybackController {
  ChannelPlaybackController({
    MethodChannel? channel,
    this.pollInterval = const Duration(milliseconds: 250),
  }) : _channel = channel ??
            const MethodChannel('com.sleepy.app.sleepy/recording');

  final MethodChannel _channel;
  final Duration pollInterval;

  Timer? _pollTimer;
  PlaybackSnapshot _snapshot = PlaybackSnapshot.idle;
  AudioFragment? _fragment;
  bool _disposed = false;

  @override
  PlaybackSnapshot get snapshot => _snapshot;

  @override
  Future<void> select(AudioFragment fragment) async {
    if (_disposed) return;

    if (_fragment?.id == fragment.id) {
      await togglePlayPause();
      return;
    }

    _fragment = fragment;
    _update(
      PlaybackSnapshot(
        fragmentId: fragment.id,
        isPreparing: true,
        duration: fragment.duration,
      ),
    );

    try {
      await _channel.invokeMethod<bool>(
        'playFragment',
        <String, Object>{'path': fragment.filePath},
      );

      if (_disposed) return;

      _update(
        _snapshot.copyWith(isPreparing: false, isPlaying: true, clearError: true),
      );
      _startPolling();
    } on MissingPluginException {
      _fail('Канал записи недоступен');
    } on PlatformException catch (exception) {
      _fail(exception.message ?? 'Не удалось воспроизвести фрагмент');
    }
  }

  @override
  Future<void> togglePlayPause() async {
    if (_disposed) return;

    final current = _snapshot;
    if (!current.hasSelection) return;

    if (current.isPlaying) {
      await pause();
      return;
    }

    try {
      final atEnd = current.duration > Duration.zero &&
          current.position >= current.duration - const Duration(milliseconds: 150);

      if (atEnd) {
        await _channel.invokeMethod<bool>(
          'seekPlayback',
          <String, Object>{'positionMs': 0},
        );
      }

      await _channel.invokeMethod<bool>('resumePlayback');

      if (_disposed) return;

      _update(
        _snapshot.copyWith(
          isPlaying: true,
          isPreparing: false,
          position: atEnd ? Duration.zero : _snapshot.position,
          clearError: true,
        ),
      );
      _startPolling();
    } on MissingPluginException {
      _fail('Канал записи недоступен');
    } on PlatformException catch (exception) {
      _fail(exception.message ?? 'Не удалось включить воспроизведение');
    }
  }

  @override
  Future<void> pause() async {
    if (_disposed) return;

    try {
      await _channel.invokeMethod<bool>('pausePlayback');
    } on MissingPluginException {
      // Плеер просто останется в текущем состоянии.
    } on PlatformException {
      // Пауза не критична: состояние подтвердит следующий опрос.
    }

    if (_disposed) return;
    _update(_snapshot.copyWith(isPlaying: false));
  }

  @override
  Future<void> stop() async {
    if (_disposed) return;

    _pollTimer?.cancel();
    _pollTimer = null;

    try {
      await _channel.invokeMethod<bool>('stopPlayback');
    } on MissingPluginException {
      // Ничего страшного: страница всё равно сбрасывает состояние.
    } on PlatformException {
      // Останавливаем локально даже при ошибке канала.
    }

    if (_disposed) return;

    _update(
      PlaybackSnapshot(
        fragmentId: _fragment?.id,
        duration: _fragment?.duration ?? Duration.zero,
      ),
    );
  }

  @override
  Future<void> seek(Duration position) async {
    if (_disposed) return;

    final duration = _snapshot.duration;
    final target = position < Duration.zero
        ? Duration.zero
        : (duration > Duration.zero && position > duration ? duration : position);

    _update(_snapshot.copyWith(position: target));

    try {
      await _channel.invokeMethod<bool>(
        'seekPlayback',
        <String, Object>{'positionMs': target.inMilliseconds},
      );
    } on MissingPluginException {
      // Позиция останется локальной.
    } on PlatformException {
      // Следующий опрос вернёт фактическую позицию.
    }
  }

  void _startPolling() {
    if (_disposed) return;

    _pollTimer?.cancel();
    _pollTimer = Timer.periodic(pollInterval, (_) => _poll());
  }

  Future<void> _poll() async {
    if (_disposed) return;

    final selected = _fragment;
    if (selected == null) return;

    try {
      final map = await _channel.invokeMapMethod<String, dynamic>(
        'getPlaybackState',
      );

      if (_disposed || map == null) return;

      final path = map['path'] as String? ?? '';
      if (path != selected.filePath) return;

      final positionMs = (map['positionMs'] as num?)?.toInt() ?? 0;
      final errorText = map['error'] as String? ?? '';
      final prepared = map['prepared'] == true;

      _update(
        PlaybackSnapshot(
          fragmentId: selected.id,
          isPlaying: map['isPlaying'] == true,
          isPreparing: !prepared && errorText.isEmpty,
          position: Duration(milliseconds: positionMs),
          duration: selected.duration,
          error: errorText.isEmpty ? null : errorText,
        ),
      );

      if (errorText.isNotEmpty) {
        _pollTimer?.cancel();
        _pollTimer = null;
      }
    } on MissingPluginException {
      _fail('Канал записи недоступен');
    } on PlatformException {
      // Временный сбой опроса не должен сбрасывать интерфейс.
    }
  }

  void _fail(String message) {
    _pollTimer?.cancel();
    _pollTimer = null;

    _update(
      PlaybackSnapshot(
        fragmentId: _fragment?.id,
        duration: _fragment?.duration ?? Duration.zero,
        error: message,
      ),
    );
  }

  void _update(PlaybackSnapshot next) {
    if (_disposed) return;

    _snapshot = next;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _pollTimer?.cancel();
    _pollTimer = null;

    _channel.invokeMethod<bool>('stopPlayback').catchError((Object _) => false);

    super.dispose();
  }
}
