import '../core/storage/local_key_value_store.dart';
import '../features/alarms/application/alarm_controller.dart';
import '../features/alarms/data/alarm_repository.dart';
import '../features/alarms/data/alarm_scheduler.dart';
import '../features/events/data/recording_gateway.dart';
import '../features/recording/application/sleep_monitor_controller.dart';
import '../features/sessions/application/sleep_session_controller.dart';
import '../features/sessions/data/sleep_session_repository.dart';

class SleepyServices {
  SleepyServices({
    LocalKeyValueStore? store,
    RecordingGateway? recordingGateway,
    RecordingControlGateway? recordingControl,
    AlarmScheduler? alarmScheduler,
    DateTime Function()? now,
  }) : _ownsControllers = true {
    final localStore = store ?? ChannelLocalKeyValueStore();
    final clock = now ?? DateTime.now;
    recording = recordingGateway ?? ChannelRecordingGateway();
    recordingControl = recordingControl ?? ChannelRecordingControlGateway();
    sessions = SleepSessionController(
      repository: JsonSleepSessionRepository(store: localStore),
      now: clock,
    );
    monitor = SleepMonitorController(
      gateway: recording,
      control: recordingControl,
      sessions: sessions,
      now: clock,
    );
    alarms = AlarmController(
      repository: JsonAlarmRepository(store: localStore),
      scheduler: alarmScheduler ?? ChannelAlarmScheduler(),
      now: clock,
    );
  }

  final bool _ownsControllers;

  late final RecordingGateway recording;
  late final RecordingControlGateway recordingControl;
  late final SleepSessionController sessions;
  late final SleepMonitorController monitor;
  late final AlarmController alarms;

  void dispose() {
    if (!_ownsControllers) return;
    monitor.dispose();
    sessions.dispose();
    alarms.dispose();
  }
}
