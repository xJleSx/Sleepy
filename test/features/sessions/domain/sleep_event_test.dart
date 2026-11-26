import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/features/sessions/domain/sleep_event.dart';

void main() {
  test('round-trips loudness fields', () {
    final event = SleepEvent(
      id: 'a',
      fileName: 'a.wav',
      filePath: '/tmp/a.wav',
      startedAt: DateTime(2026, 11, 24, 1, 10),
      duration: const Duration(seconds: 9),
      sizeBytes: 288044,
      peakDbfs: -24.5,
      averageDbfs: -51.25,
    );

    final parsed = SleepEvent.tryParse(event.toJson());

    expect(parsed, isNotNull);
    expect(parsed!.peakDbfs, -24.5);
    expect(parsed.averageDbfs, -51.25);
  });

  test('accepts legacy events without loudness fields', () {
    final parsed = SleepEvent.tryParse(<String, Object>{
      'id': 'legacy',
      'fileName': 'legacy.wav',
      'filePath': '/tmp/legacy.wav',
      'startedAtMillis': 1763943000000,
      'durationMs': 9000,
      'sizeBytes': 288044,
    });

    expect(parsed, isNotNull);
    expect(parsed!.peakDbfs, isNull);
    expect(parsed.averageDbfs, isNull);
  });
}
