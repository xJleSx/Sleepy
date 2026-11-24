import 'package:material_ui/material_ui.dart';

import '../../../app/sleepy_scope.dart';
import '../../../app/sleepy_services.dart';
import '../../../app/theme/app_theme.dart';
import '../../../shared/formatting/ru_date.dart';
import '../../../shared/widgets/app_components.dart';
import '../../events/presentation/event_formatting.dart';
import '../../reports/presentation/sleep_report_page.dart';
import '../../sessions/application/sleep_score_service.dart';
import '../../sessions/domain/sleep_session.dart';

class HistoryPage extends StatefulWidget {
  const HistoryPage({super.key});

  @override
  State<HistoryPage> createState() => _HistoryPageState();
}

class _HistoryPageState extends State<HistoryPage> {
  static const _scoreService = SleepScoreService();
  SleepyServices? _services;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final services = SleepyScope.of(context);
    if (identical(services, _services)) return;

    _services?.sessions.removeListener(_onChanged);
    _services = services;
    services.sessions.addListener(_onChanged);
    services.sessions.load();
  }

  @override
  void dispose() {
    _services?.sessions.removeListener(_onChanged);
    super.dispose();
  }

  void _onChanged() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final controller = _services?.sessions;

    return ScrollContent(
      onRefresh: controller?.reload,
      children: [
        const ScreenTitle(
          title: 'История сна',
          subtitle: 'Завершённые ночи и отчёты',
        ),
        const SizedBox(height: AppSpacing.xxl),
        if (controller == null)
          const Center(child: CircularProgressIndicator(color: accent))
        else if (controller.isLoading && !controller.isLoaded)
          const AppCard(
            child: Padding(
              padding: EdgeInsets.symmetric(vertical: 28),
              child: Center(child: CircularProgressIndicator(color: accent)),
            ),
          )
        else if (controller.error != null)
          AppCard(
            borderColor: danger.withValues(alpha: 0.35),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                InlineNotice(
                  icon: Icons.error_outline,
                  color: danger,
                  text: controller.error!,
                ),
                const SizedBox(height: AppSpacing.md),
                FilledButton(
                  onPressed: controller.reload,
                  child: const Text('Повторить'),
                ),
              ],
            ),
          )
        else if (controller.sessions.isEmpty)
          const EmptyStateCard(
            icon: Icons.history,
            title: 'Завершённых сессий пока нет',
            message:
                'Запустите мониторинг на главном экране и завершите его. '
                'Отчёт появится здесь автоматически.',
          )
        else ...[
          _summary(controller.sessions),
          const SizedBox(height: AppSpacing.xxl),
          const SectionLabel('Ночи'),
          for (final session in controller.sessions)
            _sessionCard(controller.sessions, session),
        ],
      ],
    );
  }

  Widget _summary(List<SleepSession> sessions) {
    final recent = sessions.take(7).toList();
    final average = recent.isEmpty
        ? null
        : Duration(
            seconds:
                recent.fold<int>(
                  0,
                  (sum, session) => sum + session.duration.inSeconds,
                ) ~/
                recent.length,
          );
    final scores = recent
        .map(
          (session) => _scoreService
              .calculate(session: session, history: sessions)
              .overall,
        )
        .whereType<int>()
        .toList();
    final averageScore = scores.isEmpty
        ? null
        : (scores.fold<int>(0, (sum, value) => sum + value) / scores.length)
              .round();

    return Row(
      children: [
        Expanded(
          child: MetricTile(
            icon: Icons.calendar_today_outlined,
            label: 'ночей в истории',
            value: sessions.length.toString(),
          ),
        ),
        const SizedBox(width: AppSpacing.md),
        Expanded(
          child: MetricTile(
            icon: Icons.bedtime_outlined,
            label: 'средняя длительность',
            value: average == null ? '—' : formatDurationLabel(average),
          ),
        ),
        const SizedBox(width: AppSpacing.md),
        Expanded(
          child: MetricTile(
            icon: Icons.auto_graph,
            label: 'средняя оценка',
            value: averageScore?.toString() ?? '—',
            valueColor: accent,
          ),
        ),
      ],
    );
  }

  Widget _sessionCard(List<SleepSession> sessions, SleepSession session) {
    final score = _scoreService.calculate(
      session: session,
      history: sessions,
    );
    final interrupted =
        session.status == SleepSessionStatus.interrupted;

    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: AppCard(
        onTap: () {
          Navigator.of(context).push(
            MaterialPageRoute<void>(
              builder: (_) => SleepReportPage(sessionId: session.id),
            ),
          );
        },
        child: Row(
          children: [
            Container(
              width: 52,
              height: 52,
              decoration: BoxDecoration(
                color: accent.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(AppRadii.md),
              ),
              child: Center(
                child: Text(
                  score.overall?.toString() ?? '—',
                  style: const TextStyle(
                    color: accent,
                    fontWeight: FontWeight.w700,
                    fontSize: 20,
                  ),
                ),
              ),
            ),
            const SizedBox(width: AppSpacing.lg),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          formatRussianShortDate(session.startedAt),
                          style: const TextStyle(
                            fontWeight: FontWeight.w600,
                            fontSize: 15,
                          ),
                        ),
                      ),
                      if (interrupted)
                        const StatusPill(
                          label: 'Прервано',
                          color: warning,
                        ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${formatClockShort(session.startedAt)}–'
                    '${formatClockShort(session.endedAt)} · '
                    '${formatDurationLabel(session.duration)}',
                    style: const TextStyle(
                      color: Colors.white54,
                      fontSize: 13,
                    ),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    '${session.eventCount} аудиособытий',
                    style: const TextStyle(
                      color: Colors.white38,
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: Colors.white38),
          ],
        ),
      ),
    );
  }
}
