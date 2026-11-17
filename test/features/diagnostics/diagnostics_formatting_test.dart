import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/features/diagnostics/presentation/diagnostics_formatting.dart';
import 'package:sleepy/features/events/domain/recording_status.dart';

void main() {
  test('renders the detector floor as no signal', () {
    expect(formatDbfs(-120), 'нет сигнала');
    expect(formatDbfs(-119.999), 'нет сигнала');
    expect(formatDbfs(-42.35), '−42.4 дБFS');
    expect(formatDbfs(null), missingValue);
  });

  test('formats missing optional values with a dash', () {
    expect(formatOptionalCount(null), missingValue);
    expect(formatOptionalCount(0), '0');
    expect(formatFileDuration(null), missingValue);
    expect(formatFileSize(null), missingValue);
    expect(formatFileDate(null), missingValue);
    expect(fileNameFromPath(null), missingValue);
    expect(fileNameFromPath(''), missingValue);
  });

  test('derives a file name from both path separators', () {
    expect(
      fileNameFromPath('/data/user/0/app/events/event_001.wav'),
      'event_001.wav',
    );
    expect(
      fileNameFromPath(r'C:\sleepy\events\event_002.wav'),
      'event_002.wav',
    );
  });

  test('translates session and detector states', () {
    final active = RecordingStatus(
      isRecording: true,
      fragmentCount: 0,
      eventCount: 0,
      lastWarning: '',
      error: '',
      interrupted: false,
      startedAt: DateTime(2026, 11, 17, 0, 0),
      endedAt: null,
      lastFragmentAt: null,
    );
    final stopped = RecordingStatus(
      isRecording: false,
      fragmentCount: 0,
      eventCount: 0,
      lastWarning: '',
      error: '',
      interrupted: false,
      startedAt: DateTime(2026, 11, 17, 0, 0),
      endedAt: DateTime(2026, 11, 17, 0, 21, 5),
      lastFragmentAt: null,
    );

    expect(formatSessionState(active), 'Запись активна');
    expect(formatSessionState(stopped), 'Запись остановлена');
    expect(
      formatSessionDuration(active, DateTime(2026, 11, 17, 0, 1, 5)),
      '00:01:05',
    );
    expect(
      formatSessionDuration(stopped, DateTime(2026, 11, 17, 2)),
      '00:21:05',
    );
    expect(formatDetectorState('WARMING_UP'), 'WARMING_UP · прогрев');
    expect(formatDetectorState('QUIET'), 'QUIET · тишина');
    expect(formatDetectorState('CANDIDATE'), 'CANDIDATE · кандидат');
    expect(formatDetectorState('ACTIVE'), 'ACTIVE · событие');
    expect(formatDetectorState('CUSTOM'), 'CUSTOM');
    expect(formatDetectorState(null), missingValue);
  });
}
