import '../../../core/storage/local_key_value_store.dart';
import '../../../core/storage/versioned_json_store.dart';
import '../domain/sleep_alarm.dart';

class AlarmRepositoryException implements Exception {
  const AlarmRepositoryException(this.message);

  final String message;

  @override
  String toString() => message;
}

abstract class AlarmRepository {
  Future<List<SleepAlarm>> load();

  Future<void> save(List<SleepAlarm> alarms);
}

class JsonAlarmRepository implements AlarmRepository {
  JsonAlarmRepository({required LocalKeyValueStore store})
    : _store = VersionedJsonStore(
        store: store,
        key: 'sleep_alarms',
        schemaVersion: 1,
      );

  final VersionedJsonStore _store;

  @override
  Future<List<SleepAlarm>> load() async {
    try {
      final document = await _store.read();
      if (document == null) return const <SleepAlarm>[];

      final rawAlarms = document.data['alarms'];
      if (rawAlarms is! List) {
        throw const AlarmRepositoryException(
          'В настройках будильников отсутствует список',
        );
      }

      final alarms = rawAlarms
          .map(SleepAlarm.tryParse)
          .whereType<SleepAlarm>()
          .toList()
        ..sort(_byTime);

      return List<SleepAlarm>.unmodifiable(alarms);
    } on JsonStoreException catch (exception) {
      throw AlarmRepositoryException(exception.message);
    } on LocalStorageException catch (exception) {
      throw AlarmRepositoryException(exception.message);
    }
  }

  @override
  Future<void> save(List<SleepAlarm> alarms) async {
    try {
      await _store.write(<String, Object?>{
        'alarms': alarms.map((alarm) => alarm.toJson()).toList(),
      });
    } on JsonStoreException catch (exception) {
      throw AlarmRepositoryException(exception.message);
    } on LocalStorageException catch (exception) {
      throw AlarmRepositoryException(exception.message);
    }
  }

  static int _byTime(SleepAlarm left, SleepAlarm right) {
    final leftMinute = left.hour * 60 + left.minute;
    final rightMinute = right.hour * 60 + right.minute;
    final byTime = leftMinute.compareTo(rightMinute);
    if (byTime != 0) return byTime;
    return left.label.compareTo(right.label);
  }
}
