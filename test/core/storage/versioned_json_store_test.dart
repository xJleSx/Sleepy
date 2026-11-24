import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/core/storage/local_key_value_store.dart';
import 'package:sleepy/core/storage/versioned_json_store.dart';

void main() {
  test('writes a versioned document and keeps a backup', () async {
    final store = MemoryLocalKeyValueStore();
    final versioned = VersionedJsonStore(
      store: store,
      key: 'sessions',
      schemaVersion: 1,
    );

    await versioned.write(<String, Object?>{'value': 1});
    await versioned.write(<String, Object?>{'value': 2});

    final document = await versioned.read();
    expect(document?.schemaVersion, 1);
    expect(document?.data['value'], 2);

    final backup = await store.readText('sessions.backup');
    expect(backup, isNotNull);
    expect(jsonDecode(backup!)['data']['value'], 1);
  });

  test('rejects a document from a newer schema', () async {
    final store = MemoryLocalKeyValueStore();
    await store.writeText(
      'sessions',
      jsonEncode(<String, Object?>{
        'schemaVersion': 2,
        'data': <String, Object?>{'value': 1},
      }),
    );

    final versioned = VersionedJsonStore(
      store: store,
      key: 'sessions',
      schemaVersion: 1,
    );

    expect(versioned.read(), throwsA(isA<JsonStoreException>()));
    expect(
      versioned.write(<String, Object?>{'value': 2}),
      throwsA(isA<JsonStoreException>()),
    );
  });

  test('reports corrupted json instead of silently dropping data', () async {
    final store = MemoryLocalKeyValueStore();
    await store.writeText('sessions', '{broken');

    final versioned = VersionedJsonStore(
      store: store,
      key: 'sessions',
      schemaVersion: 1,
    );

    expect(versioned.read(), throwsA(isA<JsonStoreException>()));
  });
}
