import 'package:flutter/services.dart';

class LocalStorageException implements Exception {
  const LocalStorageException(this.message);

  final String message;

  @override
  String toString() => message;
}

abstract class LocalKeyValueStore {
  Future<String?> readText(String key);

  Future<void> writeText(String key, String value);
}

class ChannelLocalKeyValueStore implements LocalKeyValueStore {
  ChannelLocalKeyValueStore({MethodChannel? channel})
    : _channel =
          channel ?? const MethodChannel('com.sleepy.app.sleepy/storage');

  final MethodChannel _channel;

  @override
  Future<String?> readText(String key) async {
    try {
      return await _channel.invokeMethod<String>('readText', <String, Object>{
        'key': key,
      });
    } on MissingPluginException {
      throw const LocalStorageException('Локальное хранилище недоступно');
    } on PlatformException catch (exception) {
      throw LocalStorageException(
        exception.message ?? 'Не удалось прочитать локальные данные',
      );
    }
  }

  @override
  Future<void> writeText(String key, String value) async {
    try {
      await _channel.invokeMethod<void>('writeText', <String, Object>{
        'key': key,
        'value': value,
      });
    } on MissingPluginException {
      throw const LocalStorageException('Локальное хранилище недоступно');
    } on PlatformException catch (exception) {
      throw LocalStorageException(
        exception.message ?? 'Не удалось сохранить локальные данные',
      );
    }
  }
}

class MemoryLocalKeyValueStore implements LocalKeyValueStore {
  final Map<String, String> _values = <String, String>{};

  @override
  Future<String?> readText(String key) async => _values[key];

  @override
  Future<void> writeText(String key, String value) async {
    _values[key] = value;
  }
}
