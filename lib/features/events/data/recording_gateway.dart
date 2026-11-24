import 'package:flutter/services.dart';

import '../../diagnostics/domain/diagnostic_audio_file.dart';
import '../domain/audio_fragment.dart';
import '../domain/recording_status.dart';

/// Ошибка получения данных от службы записи.
class RecordingGatewayException implements Exception {
  const RecordingGatewayException(this.message);

  final String message;

  @override
  String toString() => message;
}

/// Источник состояния записи и списка фрагментов.
///
/// Абстракция нужна, чтобы экран можно было тестировать без MethodChannel.
abstract class RecordingGateway {
  Future<RecordingStatus> fetchStatus();

  Future<List<AudioFragment>> fetchFragments();

  Future<List<DiagnosticAudioFile>> fetchDiagnosticFiles();

  Future<void> deleteFragment(AudioFragment fragment);
}

abstract class RecordingControlGateway {
  Future<bool> requestMicrophonePermission();

  Future<void> startRecording();

  Future<void> stopRecording();
}

class ChannelRecordingControlGateway implements RecordingControlGateway {
  ChannelRecordingControlGateway({MethodChannel? channel})
    : _channel =
          channel ?? const MethodChannel('com.sleepy.app.sleepy/recording');

  final MethodChannel _channel;

  @override
  Future<bool> requestMicrophonePermission() async {
    try {
      return await _channel.invokeMethod<bool>(
            'requestMicrophonePermission',
          ) ??
          false;
    } on MissingPluginException {
      throw const RecordingGatewayException('Канал записи недоступен');
    } on PlatformException catch (exception) {
      throw RecordingGatewayException(
        exception.message ?? 'Не удалось запросить доступ к микрофону',
      );
    }
  }

  @override
  Future<void> startRecording() async {
    try {
      await _channel.invokeMethod<bool>('startRecording');
    } on MissingPluginException {
      throw const RecordingGatewayException('Канал записи недоступен');
    } on PlatformException catch (exception) {
      throw RecordingGatewayException(
        exception.message ?? 'Не удалось запустить запись',
      );
    }
  }

  @override
  Future<void> stopRecording() async {
    try {
      await _channel.invokeMethod<bool>('stopRecording');
    } on MissingPluginException {
      throw const RecordingGatewayException('Канал записи недоступен');
    } on PlatformException catch (exception) {
      throw RecordingGatewayException(
        exception.message ?? 'Не удалось остановить запись',
      );
    }
  }
}

/// Реализация поверх существующего канала записи.
///
/// Новые методы (`listFragments`) добавлены рядом со старыми, поэтому
/// контракт `getRecordingStatus`/`startRecording`/`stopRecording` не
/// меняется.
class ChannelRecordingGateway implements RecordingGateway {
  ChannelRecordingGateway({MethodChannel? channel})
    : _channel =
          channel ?? const MethodChannel('com.sleepy.app.sleepy/recording');

  final MethodChannel _channel;

  @override
  Future<RecordingStatus> fetchStatus() async {
    try {
      final map = await _channel.invokeMapMethod<String, dynamic>(
        'getRecordingStatus',
      );

      return RecordingStatus.fromMap(map);
    } on MissingPluginException {
      throw const RecordingGatewayException('Канал записи недоступен');
    } on PlatformException catch (exception) {
      throw RecordingGatewayException(
        exception.message ?? 'Не удалось получить состояние записи',
      );
    }
  }

  @override
  Future<List<AudioFragment>> fetchFragments() async {
    try {
      final raw = await _channel.invokeListMethod<Object?>('listFragments');

      final fragments =
          (raw ?? const <Object?>[])
              .map(AudioFragment.parse)
              .whereType<AudioFragment>()
              .toList()
            ..sort(_newestFirst);

      return fragments;
    } on MissingPluginException {
      throw const RecordingGatewayException('Канал записи недоступен');
    } on PlatformException catch (exception) {
      throw RecordingGatewayException(
        exception.message ?? 'Не удалось получить список фрагментов',
      );
    }
  }

  @override
  Future<void> deleteFragment(AudioFragment fragment) async {
    try {
      final deleted = await _channel.invokeMethod<bool>(
        'deleteFragment',
        <String, Object>{'path': fragment.filePath},
      );
      if (deleted != true) {
        throw const RecordingGatewayException('Фрагмент не был удалён');
      }
    } on MissingPluginException {
      throw const RecordingGatewayException('Канал записи недоступен');
    } on PlatformException catch (exception) {
      throw RecordingGatewayException(
        exception.message ?? 'Не удалось удалить фрагмент',
      );
    }
  }

  @override
  Future<List<DiagnosticAudioFile>> fetchDiagnosticFiles() async {
    try {
      final raw = await _channel.invokeListMethod<Object?>(
        'listDiagnosticFiles',
      );

      final files =
          (raw ?? const <Object?>[])
              .map(DiagnosticAudioFile.parse)
              .whereType<DiagnosticAudioFile>()
              .toList()
            ..sort(_newestDiagnosticFirst);

      return files;
    } on MissingPluginException {
      throw const RecordingGatewayException('Канал диагностики недоступен');
    } on PlatformException catch (exception) {
      throw RecordingGatewayException(
        exception.message ?? 'Не удалось проверить WAV-файлы',
      );
    }
  }

  static int _newestFirst(AudioFragment left, AudioFragment right) {
    final byTime = right.startedAt.compareTo(left.startedAt);
    if (byTime != 0) return byTime;

    return right.fileName.compareTo(left.fileName);
  }

  static int _newestDiagnosticFirst(
    DiagnosticAudioFile left,
    DiagnosticAudioFile right,
  ) {
    final leftTime = left.startedAt;
    final rightTime = right.startedAt;

    if (leftTime != null && rightTime != null) {
      final byTime = rightTime.compareTo(leftTime);
      if (byTime != 0) return byTime;
    } else if (leftTime == null && rightTime != null) {
      return 1;
    } else if (leftTime != null && rightTime == null) {
      return -1;
    }

    return right.fileName.compareTo(left.fileName);
  }
}
