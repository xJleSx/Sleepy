import '../../../core/storage/local_key_value_store.dart';
import '../../../core/storage/versioned_json_store.dart';
import '../domain/sleep_session.dart';

class SleepSessionRepositoryException implements Exception {
  const SleepSessionRepositoryException(this.message);

  final String message;

  @override
  String toString() => message;
}

abstract class SleepSessionRepository {
  Future<List<SleepSession>> loadSessions();

  Future<void> saveSessions(List<SleepSession> sessions);

  Future<ActiveSleepSession?> loadActiveSession();

  Future<void> saveActiveSession(ActiveSleepSession session);

  Future<void> clearActiveSession();
}

class JsonSleepSessionRepository implements SleepSessionRepository {
  JsonSleepSessionRepository({
    required LocalKeyValueStore store,
  }) : _sessions = VersionedJsonStore(
         store: store,
         key: 'sleep_sessions',
         schemaVersion: 1,
       ),
       _active = VersionedJsonStore(
         store: store,
         key: 'sleep_active_session',
         schemaVersion: 1,
       );

  final VersionedJsonStore _sessions;
  final VersionedJsonStore _active;

  @override
  Future<List<SleepSession>> loadSessions() async {
    try {
      final document = await _sessions.read();
      if (document == null) return const <SleepSession>[];

      final rawSessions = document.data['sessions'];
      if (rawSessions is! List) {
        throw const SleepSessionRepositoryException(
          'В истории сна отсутствует список сессий',
        );
      }

      final sessions = rawSessions
          .map(SleepSession.tryParse)
          .whereType<SleepSession>()
          .toList()
        ..sort((left, right) => right.startedAt.compareTo(left.startedAt));

      return List<SleepSession>.unmodifiable(sessions);
    } on JsonStoreException catch (exception) {
      throw SleepSessionRepositoryException(exception.message);
    } on LocalStorageException catch (exception) {
      throw SleepSessionRepositoryException(exception.message);
    }
  }

  @override
  Future<void> saveSessions(List<SleepSession> sessions) async {
    try {
      await _sessions.write(<String, Object?>{
        'sessions': sessions.map((session) => session.toJson()).toList(),
      });
    } on JsonStoreException catch (exception) {
      throw SleepSessionRepositoryException(exception.message);
    } on LocalStorageException catch (exception) {
      throw SleepSessionRepositoryException(exception.message);
    }
  }

  @override
  Future<ActiveSleepSession?> loadActiveSession() async {
    try {
      final document = await _active.read();
      if (document == null) return null;
      return ActiveSleepSession.tryParse(document.data);
    } on JsonStoreException catch (exception) {
      throw SleepSessionRepositoryException(exception.message);
    } on LocalStorageException catch (exception) {
      throw SleepSessionRepositoryException(exception.message);
    }
  }

  @override
  Future<void> saveActiveSession(ActiveSleepSession session) async {
    try {
      await _active.write(session.toJson());
    } on JsonStoreException catch (exception) {
      throw SleepSessionRepositoryException(exception.message);
    } on LocalStorageException catch (exception) {
      throw SleepSessionRepositoryException(exception.message);
    }
  }

  @override
  Future<void> clearActiveSession() async {
    try {
      await _active.write(<String, Object?>{});
    } on JsonStoreException catch (exception) {
      throw SleepSessionRepositoryException(exception.message);
    } on LocalStorageException catch (exception) {
      throw SleepSessionRepositoryException(exception.message);
    }
  }
}
