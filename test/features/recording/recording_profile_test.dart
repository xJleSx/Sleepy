import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/features/recording/domain/recording_profile.dart';

void main() {
  test('keeps stable ids and Russian labels', () {
    expect(RecordingProfile.values.map((profile) => profile.id), <String>[
      'STANDARD',
      'HIGH',
      'VERY_HIGH',
    ]);
    expect(RecordingProfile.standard.label, 'Стандартный');
    expect(RecordingProfile.high.description, contains('слабые кандидаты'));
  });

  test('falls back to standard for unknown or missing ids', () {
    expect(RecordingProfile.fromId('HIGH'), RecordingProfile.high);
    expect(RecordingProfile.fromId('VERY_HIGH'), RecordingProfile.veryHigh);
    expect(RecordingProfile.fromId('STANDARD'), RecordingProfile.standard);
    expect(RecordingProfile.fromId(null), RecordingProfile.standard);
    expect(RecordingProfile.fromId(''), RecordingProfile.standard);
    expect(RecordingProfile.fromId('high'), RecordingProfile.standard);
  });
}
