import 'dart:convert';

import 'local_key_value_store.dart';

class JsonStoreException implements Exception {
  const JsonStoreException(this.message);

  final String message;

  @override
  String toString() => message;
}

class VersionedJsonDocument {
  const VersionedJsonDocument({
    required this.schemaVersion,
    required this.data,
  });

  final int schemaVersion;
  final Map<String, Object?> data;
}

class VersionedJsonStore {
  VersionedJsonStore({
    required this._store,
    required this.key,
    required this.schemaVersion,
  });

  final LocalKeyValueStore _store;
  final String key;
  final int schemaVersion;

  Future<VersionedJsonDocument?> read() async {
    final raw = await _store.readText(key);
    if (raw == null || raw.trim().isEmpty) return null;

    final Object? decoded;
    try {
      decoded = jsonDecode(raw);
    } on FormatException {
      throw JsonStoreException('Повреждённый JSON в «$key»');
    }

    if (decoded is! Map) {
      throw JsonStoreException('Неверный формат JSON в «$key»');
    }

    final map = decoded.cast<String, Object?>();
    final versionValue = map['schemaVersion'];
    final version = versionValue is num ? versionValue.toInt() : 0;

    if (version > schemaVersion) {
      throw JsonStoreException(
        'Файл «$key» создан более новой версией приложения',
      );
    }

    final data = map['data'];
    if (data is! Map) {
      throw JsonStoreException('В «$key» отсутствует объект data');
    }

    return VersionedJsonDocument(
      schemaVersion: version,
      data: data.cast<String, Object?>(),
    );
  }

  Future<void> write(Map<String, Object?> data) async {
    final existing = await read();
    if (existing != null && existing.schemaVersion > schemaVersion) {
      throw JsonStoreException(
        'Нельзя перезаписать «$key», созданный более новой версией',
      );
    }

    final raw = await _store.readText(key);
    if (raw != null && raw.isNotEmpty) {
      await _store.writeText('$key.backup', raw);
    }

    await _store.writeText(
      key,
      jsonEncode(<String, Object?>{
        'schemaVersion': schemaVersion,
        'data': data,
      }),
    );
  }
}
