import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/features/events/presentation/event_formatting.dart';

void main() {
  group('formatDuration', () {
    test('shows minutes and seconds', () {
      expect(formatDuration(const Duration(seconds: 5)), '0:05');
      expect(formatDuration(const Duration(seconds: 61)), '1:01');
      expect(
        formatDuration(const Duration(minutes: 12, seconds: 30)),
        '12:30',
      );
    });

    test('shows hours for long fragments', () {
      expect(
        formatDuration(const Duration(hours: 1, minutes: 2, seconds: 3)),
        '1:02:03',
      );
    });

    test('rounds up very short fragments so they are not 0:00', () {
      expect(
        formatDuration(const Duration(milliseconds: 156), roundUp: true),
        '0:01',
      );
      expect(formatDuration(Duration.zero, roundUp: true), '0:00');
    });
  });

  group('formatElapsed', () {
    test('always uses hours, minutes and seconds', () {
      expect(formatElapsed(Duration.zero), '00:00:00');
      expect(formatElapsed(const Duration(minutes: 1, seconds: 5)), '00:01:05');
      expect(
        formatElapsed(const Duration(hours: 2, minutes: 3, seconds: 4)),
        '02:03:04',
      );
    });

    test('clamps negative values', () {
      expect(formatElapsed(const Duration(seconds: -5)), '00:00:00');
    });
  });

  group('formatBytes', () {
    test('picks a readable unit', () {
      expect(formatBytes(980), '980 Б');
      expect(formatBytes(65536), '64 КБ');
      expect(formatBytes(1572864), '1.5 МБ');
    });
  });

  group('formatEventTimestamp', () {
    test('includes date and time with seconds', () {
      final time = DateTime(2026, 11, 15, 23, 4, 7);

      expect(formatEventTimestamp(time), '15.11.2026 · 23:04:07');
      expect(formatDateTime(time), '15.11.2026, 23:04');
    });
  });
}
