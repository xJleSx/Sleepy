import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../../../app/sleepy_scope.dart';
import '../../../app/sleepy_services.dart';
import '../../../app/theme/app_theme.dart';
import '../../../shared/widgets/app_components.dart';
import '../../events/domain/audio_fragment.dart';
import '../../events/presentation/event_formatting.dart';
import '../../reports/presentation/sleep_report_page.dart';

class RecordingPage extends StatefulWidget {
  const RecordingPage({super.key});

  @override
  State<RecordingPage> createState() => _RecordingPageState();
}

class _RecordingPageState extends State<RecordingPage> {
  SleepyServices? _services;
  Timer? _timer;
  List<AudioFragment> _fragments = const <AudioFragment>[];

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final services = SleepyScope.of(context);
    if (_services == services) return;
    _services = services;
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 3), (_) => _reload());
    _reload();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _reload() async {
    final services = _services;
    if (services == null) return;

    try {
      final fragments = await services.recording.fetchFragments();
      if (!mounted) return;
      setState(() {
        _fragments = fragments.take(6).toList();
      });
    } catch (_) {}
  }

  Future<void> _finish() async {
    final services = _services;
    if (services == null) return;

    try {
      final session = await services.monitor.stopAndFinalize();
      if (!mounted) return;
      await Navigator.of(context).pushReplacement(
        MaterialPageRoute<void>(
          builder: (_) => SleepReportPage(sessionId: session.id),
        ),
      );
    } catch (exception) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          backgroundColor: danger,
          content: Text(
            exception.toString().replaceFirst('SleepMonitorException: ', ''),
          ),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final services = _services;
    if (services == null) {
      return const Scaffold(
        body: Center(child: CircularProgressIndicator(color: accent)),
      );
    }

    final monitor = services.monitor;
    final recording = monitor.isRecording;

    return AnimatedBuilder(
      animation: monitor,
      builder: (context, _) {
        return Scaffold(
          appBar: AppBar(
            title: const Text('Мониторинг сна'),
            actions: [
              IconButton(
                onPressed: monitor.isBusy ? null : _reload,
                tooltip: 'Обновить',
                icon: const Icon(Icons.refresh),
              ),
            ],
          ),
          body: ScrollContent(
            onRefresh: _reload,
            children: [
              AppCard(
                borderColor: recording
                    ? success.withValues(alpha: 0.35)
                    : danger.withValues(alpha: 0.25),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    StatusPill(
                      label: recording ? 'Запись активна' : 'Запись не активна',
                      color: recording ? success : danger,
                      icon: recording
                          ? Icons.radio_button_checked
                          : Icons.mic_off,
                    ),
                    const SizedBox(height: AppSpacing.xxl),
                    Text(
                      formatElapsed(monitor.elapsed),
                      style: const TextStyle(
                        fontSize: 46,
                        height: 1,
                        fontWeight: FontWeight.w700,
                        letterSpacing: 1.5,
                        color: accent,
                      ),
                    ),
                    const SizedBox(height: AppSpacing.sm),
                    const Text(
                      'Длительность текущей записи',
                      style: TextStyle(color: Colors.white54),
                    ),
                    if (monitor.error != null) ...[
                      const SizedBox(height: AppSpacing.lg),
                      InlineNotice(
                        icon: Icons.error_outline,
                        color: danger,
                        text: monitor.error!,
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(height: AppSpacing.xxl),
              _statusGrid(),
              const SizedBox(height: AppSpacing.xxl),
              const SectionLabel('Последние обнаруженные события'),
              if (_fragments.isEmpty)
                const AppCard(
                  child: Text(
                    'Пока нет сохранённых фрагментов. Детектор ещё не '
                    'подтвердил звуковое событие.',
                    style: TextStyle(color: Colors.white54, height: 1.45),
                  ),
                )
              else
                for (final fragment in _fragments)
                  Padding(
                    padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                    child: AppCard(
                      padding: const EdgeInsets.all(AppSpacing.lg),
                      child: Row(
                        children: [
                          const Icon(Icons.graphic_eq, color: secondary),
                          const SizedBox(width: AppSpacing.md),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  formatEventTimestamp(fragment.startedAt),
                                  style: const TextStyle(
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  '${formatDuration(fragment.duration, roundUp: true)}'
                                  ' · ${formatBytes(fragment.sizeBytes)}',
                                  style: const TextStyle(
                                    color: Colors.white54,
                                    fontSize: 12,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
              const SizedBox(height: AppSpacing.xxl),
              if (recording)
                FilledButton.icon(
                  onPressed: monitor.isStopping ? null : _finish,
                  style: FilledButton.styleFrom(
                    backgroundColor: danger,
                    padding: const EdgeInsets.symmetric(vertical: 18),
                  ),
                  icon: monitor.isStopping
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : const Icon(Icons.stop_circle_outlined),
                  label: Text(
                    monitor.isStopping
                        ? 'Завершаем и сохраняем…'
                        : 'Завершить сон',
                  ),
                )
              else
                OutlinedButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('Вернуться на главную'),
                ),
              const SizedBox(height: AppSpacing.md),
              const Text(
                'Экран можно заблокировать: запись идёт в foreground-службе. '
                'Не закрывайте приложение принудительно.',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.white38, fontSize: 12),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _statusGrid() {
    final status = _services!.monitor.status;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SectionLabel('Состояние'),
        Row(
          children: [
            Expanded(
              child: MetricTile(
                icon: Icons.mic_none,
                label: status.isRecording ? 'микрофон активен' : 'микрофон',
                value: status.hasError ? 'Ошибка' : status.isRecording ? 'Да' : 'Нет',
                valueColor: status.hasError ? danger : Colors.white,
              ),
            ),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: MetricTile(
                icon: Icons.sensors,
                label: 'foreground-служба',
                value: status.isRecording ? 'Активна' : 'Нет',
              ),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.md),
        MetricTile(
          icon: Icons.tune,
          label: 'профиль чувствительности',
          value: _services!.monitor.profile.label,
        ),
        const SizedBox(height: AppSpacing.md),
        Row(
          children: [
            Expanded(
              child: MetricTile(
                icon: Icons.bolt,
                label: 'событий обнаружено',
                value: status.eventCount.toString(),
              ),
            ),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: MetricTile(
                icon: Icons.save_alt,
                label: 'WAV сохранено',
                value: status.writerSavedCount?.toString() ?? '—',
                valueColor: (status.writerFailedCount ?? 0) > 0
                    ? warning
                    : Colors.white,
              ),
            ),
          ],
        ),
        if (status.hasWarning) ...[
          const SizedBox(height: AppSpacing.md),
          InlineNotice(
            icon: Icons.warning_amber_rounded,
            color: warning,
            text: status.lastWarning,
          ),
        ],
      ],
    );
  }
}
