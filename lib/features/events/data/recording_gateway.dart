import 'package:flutter/services.dart';

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
}

/// Реализация поверх существующего канала записи.
///
/// Новые методы (`listFragments`) добавлены рядом со старыми, поэтому
/// контракт `getRecordingStatus`/`startRecording`/`stopRecording` не
/// меняется.
class ChannelRecordingGateway implements RecordingGateway {
  ChannelRecordingGateway({MethodChannel? channel})
      : _channel = channel ??
            const MethodChannel('com.sleepy.app.sleepy/recording');

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

      final fragments = (raw ?? const <Object?>[])
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

  static int _newestFirst(AudioFragment left, AudioFragment right) {
    final byTime = right.startedAt.compareTo(left.startedAt);
    if (byTime != 0) return byTime;

    return right.fileName.compareTo(left.fileName);
  }
}
