import 'package:material_ui/material_ui.dart';

import '../../../app/sleepy_scope.dart';
import '../../../app/sleepy_services.dart';
import '../../../app/theme/app_theme.dart';
import '../../../shared/formatting/ru_date.dart';
import '../../../shared/widgets/app_components.dart';
import '../../alarms/domain/alarm_time_calculator.dart';
import '../../alarms/domain/sleep_alarm.dart';
import '../../alarms/presentation/alarm_page.dart';
import '../../events/presentation/event_formatting.dart';
import '../../recording/presentation/recording_page.dart';
import '../../reports/presentation/sleep_report_page.dart';
import '../../sessions/application/sleep_score_service.dart';
import '../../sessions/domain/sleep_session.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  static const _scoreService = SleepScoreService();
  static const _alarmCalculator = AlarmTimeCalculator();

  SleepyServices? _services;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final services = SleepyScope.of(context);
    if (identical(services, _services)) return;

    _services?.monitor.removeListener(_onChanged);
    _services?.sessions.removeListener(_onChanged);
    _services?.alarms.removeListener(_onChanged);

    _services = services;
    services.monitor.addListener(_onChanged);
    services.sessions.addListener(_onChanged);
    services.alarms.addListener(_onChanged);
  }

  @override
  void dispose() {
    _services?.monitor.removeListener(_onChanged);
    _services?.sessions.removeListener(_onChanged);
    _services?.alarms.removeListener(_onChanged);
    super.dispose();
  }

  void _onChanged() {
    if (mounted) setState(() {});
  }

  Future<void> _startSleep() async {
    final services = _services;
    if (services == null) return;

    try {
      await services.monitor.start();
      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute<void>(builder: (_) => const RecordingPage()),
      );
    } catch (exception) {
      _showError(exception.toString());
    }
  }

  Future<void> _stopSleep() async {
    final services = _services;
    if (services == null) return;

    try {
      final session = await services.monitor.stopAndFinalize();
      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => SleepReportPage(sessionId: session.id),
        ),
      );
    } catch (exception) {
      _showError(exception.toString());
    }
  }

  void _showError(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        backgroundColor: danger,
        content: Text(message.replaceFirst('SleepMonitorException: ', '')),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final services = _services;
    if (services == null) {
      return const Center(child: CircularProgressIndicator(color: accent));
    }

    final monitor = services.monitor;
    final sessions = services.sessions;
    final lastSession = sessions.lastSession;
    final now = DateTime.now();
    final recording = monitor.isRecording;

    return ScrollContent(
      onRefresh: monitor.refresh,
      children: [
        _header(now),
        const SizedBox(height: AppSpacing.xxl),
        if (monitor.isInitializing)
          const AppCard(
            child: Padding(
              padding: EdgeInsets.symmetric(vertical: 28),
              child: Center(child: CircularProgressIndicator(color: accent)),
            ),
          )
        else
          _mainCard(monitor.elapsed, recording),
        if (monitor.error != null) ...[
          const SizedBox(height: AppSpacing.md),
          AppCard(
            borderColor: danger.withValues(alpha: 0.35),
            child: InlineNotice(
              icon: Icons.error_outline,
              color: danger,
              text: monitor.error!,
            ),
          ),
        ],
        const SizedBox(height: AppSpacing.xxl),
        _alarmCard(),
        const SizedBox(height: AppSpacing.xxl),
        _lastSleepCard(lastSession),
        const SizedBox(height: AppSpacing.xxl),
        _quickStats(sessions.sessions),
        if (lastSession != null) ...[
          const SizedBox(height: AppSpacing.xxl),
          _lastAnalysisCard(lastSession),
        ],
        const SizedBox(height: AppSpacing.xl),
        const Center(
          child: Text(
            'Данные хранятся только на этом устройстве',
            style: TextStyle(color: Colors.white38, fontSize: 12),
          ),
        ),
      ],
    );
  }

  Widget _header(DateTime now) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Sleepy',
                style: TextStyle(
                  color: accent,
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 1.4,
                ),
              ),
              const SizedBox(height: AppSpacing.xs),
              Text(
                greetingForHour(now.hour),
                style: Theme.of(context).textTheme.headlineLarge,
              ),
              const SizedBox(height: AppSpacing.xs),
              Text(
                formatRussianDate(now),
                style: const TextStyle(color: Colors.white54),
              ),
            ],
          ),
        ),
        Container(
          width: 46,
          height: 46,
          decoration: BoxDecoration(
            color: surface,
            borderRadius: BorderRadius.circular(AppRadii.md),
            border: Border.all(color: subtleBorder),
          ),
          child: const Icon(Icons.nightlight_round, color: accent),
        ),
      ],
    );
  }

  Widget _mainCard(Duration elapsed, bool recording) {
    final monitor = _services!.monitor;

    return Container(
      padding: const EdgeInsets.all(AppSpacing.xxl),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(AppRadii.xl),
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: recording
              ? <Color>[
                  accent.withValues(alpha: 0.24),
                  surface,
                  surface,
                ]
              : <Color>[surfaceHigh, surface],
        ),
        border: Border.all(
          color: recording
              ? accent.withValues(alpha: 0.45)
              : subtleBorder,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              StatusPill(
                label: recording ? 'Запись идёт' : 'Готово к ночи',
                color: recording ? success : accent,
                icon: recording
                    ? Icons.radio_button_checked
                    : Icons.nightlight_round,
              ),
              const Spacer(),
              if (recording)
                Text(
                  formatClockShort(DateTime.now()),
                  style: const TextStyle(color: Colors.white54),
                ),
            ],
          ),
          const SizedBox(height: AppSpacing.xxl),
          Text(
            recording ? formatElapsed(elapsed) : 'Начните мониторинг сна',
            style: TextStyle(
              fontSize: recording ? 44 : 28,
              height: 1.05,
              fontWeight: FontWeight.w700,
              letterSpacing: recording ? 1.5 : -0.5,
            ),
          ),
          const SizedBox(height: AppSpacing.sm),
          Text(
            recording
                ? 'Микрофон и foreground-служба работают. '
                      'Экран можно заблокировать.'
                : 'Sleepy сохранит только звуковые события, '
                      'а не непрерывную ночную запись.',
            style: const TextStyle(color: Colors.white60, height: 1.45),
          ),
          if (recording) ...[
            const SizedBox(height: AppSpacing.xl),
            Row(
              children: [
                Expanded(
                  child: _MiniMetric(
                    icon: Icons.graphic_eq,
                    label: 'События',
                    value: monitor.status.eventCount.toString(),
                  ),
                ),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: _MiniMetric(
                    icon: Icons.save_outlined,
                    label: 'WAV сохранено',
                    value: monitor.status.writerSavedCount?.toString() ?? '—',
                  ),
                ),
              ],
            ),
          ],
          const SizedBox(height: AppSpacing.xxl),
          Row(
            children: [
              Expanded(
                child: FilledButton.icon(
                  onPressed: monitor.isStarting || monitor.isStopping
                      ? null
                      : recording
                      ? _stopSleep
                      : _startSleep,
                  style: FilledButton.styleFrom(
                    backgroundColor: recording ? danger : accent,
                    foregroundColor: recording ? Colors.white : background,
                    padding: const EdgeInsets.symmetric(vertical: 18),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(AppRadii.md),
                    ),
                  ),
                  icon: monitor.isStarting || monitor.isStopping
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : Icon(
                          recording
                              ? Icons.stop_circle_outlined
                              : Icons.play_arrow_rounded,
                        ),
                  label: Text(
                    monitor.isStarting
                        ? 'Запускаем…'
                        : monitor.isStopping
                        ? 'Завершаем…'
                        : recording
                        ? 'Завершить сон'
                        : 'Начать сон',
                  ),
                ),
              ),
              if (recording) ...[
                const SizedBox(width: AppSpacing.sm),
                IconButton.filledTonal(
                  onPressed: () {
                    Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => const RecordingPage(),
                      ),
                    );
                  },
                  tooltip: 'Экран записи',
                  icon: const Icon(Icons.open_in_new),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }

  Widget _alarmCard() {
    final alarm = _nextAlarm();
    final trigger = alarm == null
        ? null
        : _alarmCalculator.nextTrigger(alarm, DateTime.now());

    return AppCard(
      onTap: () {
        Navigator.of(context).push(
          MaterialPageRoute<void>(builder: (_) => const AlarmPage()),
        );
      },
      child: Row(
        children: [
          Container(
            width: 48,
            height: 48,
            decoration: BoxDecoration(
              color: secondary.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(AppRadii.md),
            ),
            child: const Icon(Icons.alarm, color: secondary),
          ),
          const SizedBox(width: AppSpacing.lg),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Будильник',
                  style: TextStyle(fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 3),
                Text(
                  trigger == null
                      ? 'Не настроен'
                      : '${formatClockShort(trigger)} · ${alarm!.label}',
                  style: const TextStyle(color: Colors.white54),
                ),
              ],
            ),
          ),
          const Icon(Icons.chevron_right, color: Colors.white38),
        ],
      ),
    );
  }

  Widget _lastSleepCard(SleepSession? session) {
    if (session == null) {
      return const EmptyStateCard(
        icon: Icons.bedtime_outlined,
        title: 'История пока пуста',
        message:
            'После первой завершённой записи здесь появится длительность, '
            'события и предварительная оценка режима.',
      );
    }

    final score = _scoreService.calculate(
      session: session,
      history: _services!.sessions.sessions,
    );

    return AppCard(
      onTap: () {
        Navigator.of(context).push(
          MaterialPageRoute<void>(
            builder: (_) => SleepReportPage(sessionId: session.id),
          ),
        );
      },
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  'Последняя ночь',
                  style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
                ),
              ),
              Text(
                formatRussianShortDate(session.startedAt),
                style: const TextStyle(color: Colors.white54, fontSize: 12),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.lg),
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                score.overall?.toString() ?? '—',
                style: const TextStyle(
                  fontSize: 48,
                  fontWeight: FontWeight.w700,
                  color: accent,
                  height: 1,
                ),
              ),
              const Padding(
                padding: EdgeInsets.only(bottom: 5, left: 5),
                child: Text(
                  'из 100',
                  style: TextStyle(color: Colors.white54),
                ),
              ),
              const Spacer(),
              StatusPill(
                label: score.provisional ? 'Предварительно' : 'Оценка режима',
                color: score.provisional ? warning : success,
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.lg),
          Wrap(
            spacing: AppSpacing.lg,
            runSpacing: AppSpacing.sm,
            children: [
              _InlineMetric(
                icon: Icons.bedtime_outlined,
                text: formatDurationLabel(session.duration),
              ),
              _InlineMetric(
                icon: Icons.graphic_eq,
                text: '${session.eventCount} событий',
              ),
              _InlineMetric(
                icon: Icons.schedule,
                text:
                    '${formatClockShort(session.startedAt)}–'
                    '${formatClockShort(session.endedAt)}',
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _quickStats(List<SleepSession> sessions) {
    final now = DateTime.now();
    final weekStart = now.subtract(const Duration(days: 7));
    final week = sessions
        .where((session) => session.endedAt.isAfter(weekStart))
        .toList();
    final average = week.isEmpty
        ? null
        : Duration(
            seconds:
                week.fold<int>(
                  0,
                  (sum, session) => sum + session.duration.inSeconds,
                ) ~/
                week.length,
          );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SectionLabel('Краткая статистика'),
        Row(
          children: [
            Expanded(
              child: MetricTile(
                icon: Icons.calendar_today_outlined,
                label: 'ночей за 7 дней',
                value: week.length.toString(),
              ),
            ),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: MetricTile(
                icon: Icons.timelapse,
                label: 'средняя длительность',
                value: average == null ? '—' : formatDurationLabel(average),
              ),
            ),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: MetricTile(
                icon: Icons.graphic_eq,
                label: 'событий за 7 дней',
                value: week
                    .fold<int>(0, (sum, session) => sum + session.eventCount)
                    .toString(),
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _lastAnalysisCard(SleepSession session) {
    return AppCard(
      child: Row(
        children: [
          const Icon(Icons.insights, color: secondary),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Последний результат',
                  style: TextStyle(fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 3),
                Text(
                  session.eventCount == 0
                      ? 'Звуковые события не обнаружены'
                      : 'Обнаружено событий: ${session.eventCount}. '
                            'Тип звука пока не классифицируется.',
                  style: const TextStyle(color: Colors.white54, height: 1.4),
                ),
              ],
            ),
          ),
          const SizedBox(width: AppSpacing.sm),
          IconButton(
            onPressed: () {
              Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => SleepReportPage(sessionId: session.id),
                ),
              );
            },
            tooltip: 'Открыть отчёт',
            icon: const Icon(Icons.arrow_forward),
          ),
        ],
      ),
    );
  }

  SleepAlarm? _nextAlarm() {
    final alarms = _services!.alarms.alarms
        .where((alarm) => alarm.enabled)
        .toList();
    if (alarms.isEmpty) return null;

    SleepAlarm? best;
    DateTime? bestTime;
    final now = DateTime.now();
    for (final alarm in alarms) {
      final trigger = _alarmCalculator.nextTrigger(alarm, now);
      if (trigger == null) continue;
      if (bestTime == null || trigger.isBefore(bestTime)) {
        best = alarm;
        bestTime = trigger;
      }
    }
    return best;
  }
}

class _MiniMetric extends StatelessWidget {
  const _MiniMetric({
    required this.icon,
    required this.label,
    required this.value,
  });

  final IconData icon;
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: background.withValues(alpha: 0.55),
        borderRadius: BorderRadius.circular(AppRadii.md),
      ),
      child: Row(
        children: [
          Icon(icon, size: 18, color: secondary),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  value,
                  style: const TextStyle(
                    fontWeight: FontWeight.w700,
                    fontSize: 18,
                  ),
                ),
                Text(
                  label,
                  style: const TextStyle(
                    color: Colors.white54,
                    fontSize: 11,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _InlineMetric extends StatelessWidget {
  const _InlineMetric({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 16, color: Colors.white38),
        const SizedBox(width: 5),
        Text(text, style: const TextStyle(color: Colors.white60)),
      ],
    );
  }
}
