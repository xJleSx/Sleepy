import 'package:material_ui/material_ui.dart';

import '../../../app/sleepy_scope.dart';
import '../../../app/sleepy_services.dart';
import '../../../app/theme/app_theme.dart';
import '../../../shared/formatting/ru_date.dart';
import '../../../shared/widgets/app_components.dart';
import '../../events/presentation/event_formatting.dart';
import '../../sessions/application/sleep_score_service.dart';
import '../../sessions/domain/sleep_session.dart';

class AnalyticsPage extends StatefulWidget {
  const AnalyticsPage({super.key});

  @override
  State<AnalyticsPage> createState() => _AnalyticsPageState();
}

class _AnalyticsPageState extends State<AnalyticsPage> {
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
    final completed = controller?.sessions
        .where((session) => session.isComplete)
        .toList();

    return ScrollContent(
      onRefresh: controller?.reload,
      children: [
        const ScreenTitle(
          title: 'Анализ',
          subtitle: 'Тенденции по завершённым сессиям',
        ),
        const SizedBox(height: AppSpacing.xxl),
        if (controller == null || (controller.isLoading && !controller.isLoaded))
          const AppCard(
            child: Padding(
              padding: EdgeInsets.symmetric(vertical: 28),
              child: Center(child: CircularProgressIndicator(color: accent)),
            ),
          )
        else if (controller.error != null)
          AppCard(
            borderColor: danger.withValues(alpha: 0.35),
            child: InlineNotice(
              icon: Icons.error_outline,
              color: danger,
              text: controller.error!,
            ),
          )
        else if (completed == null || completed.isEmpty)
          const EmptyStateCard(
            icon: Icons.insights,
            title: 'Статистика появится после первой ночи',
            message:
                'Завершите сессию мониторинга, чтобы увидеть длительность, '
                'события и динамику оценки.',
          )
        else
          ..._content(controller.sessions),
      ],
    );
  }

  List<Widget> _content(List<SleepSession> allSessions) {
    final completed = allSessions
        .where((session) => session.isComplete)
        .toList();
    final recent = completed.take(7).toList();
    final scores = recent
        .map(
          (session) => _scoreService
              .calculate(session: session, history: allSessions)
              .overall,
        )
        .toList();
    final availableScores = scores.whereType<int>().toList();
    final averageScore = availableScores.isEmpty
        ? null
        : (availableScores.fold<int>(0, (sum, value) => sum + value) /
                  availableScores.length)
              .round();
    final averageDuration = Duration(
      seconds:
          recent.fold<int>(
            0,
            (sum, session) => sum + session.duration.inSeconds,
          ) ~/
          recent.length,
    );
    final averageEvents =
        recent.fold<int>(0, (sum, session) => sum + session.eventCount) /
        recent.length;

    return <Widget>[
      Row(
        children: [
          Expanded(
            child: MetricTile(
              icon: Icons.nightlight_outlined,
              label: 'ночей',
              value: completed.length.toString(),
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
      ),
      const SizedBox(height: AppSpacing.md),
      Row(
        children: [
          Expanded(
            child: MetricTile(
              icon: Icons.bedtime_outlined,
              label: 'средний сон',
              value: formatDurationLabel(averageDuration),
            ),
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: MetricTile(
              icon: Icons.graphic_eq,
              label: 'событий за ночь',
              value: averageEvents.toStringAsFixed(1),
            ),
          ),
        ],
      ),
      const SizedBox(height: AppSpacing.xxl),
      const SectionLabel('Оценка по ночам'),
      AppCard(
        child: SizedBox(
          height: 150,
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              for (var index = 0; index < recent.length; index++)
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        Text(
                          scores[index]?.toString() ?? '—',
                          style: const TextStyle(
                            color: Colors.white54,
                            fontSize: 10,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Container(
                          height: 8 + 92 * (scores[index] ?? 0) / 100,
                          decoration: BoxDecoration(
                            color: scores[index] == null
                                ? Colors.white12
                                : accent,
                            borderRadius: BorderRadius.circular(5),
                          ),
                        ),
                        const SizedBox(height: 6),
                        Text(
                          formatRussianShortDate(
                            recent[index].startedAt,
                          ).substring(0, 5),
                          style: const TextStyle(
                            color: Colors.white38,
                            fontSize: 9,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
      const SizedBox(height: AppSpacing.md),
      const Text(
        'Столбцы слева направо: от последней ночи к более ранним.',
        style: TextStyle(color: Colors.white38, fontSize: 11),
      ),
      const SizedBox(height: AppSpacing.xxl),
      const SectionLabel('Последние ночи'),
      for (final session in recent)
        AppCard(
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      formatRussianShortDate(session.startedAt),
                      style: const TextStyle(fontWeight: FontWeight.w600),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      '${formatDurationLabel(session.duration)} · '
                      '${session.eventCount} событий',
                      style: const TextStyle(
                        color: Colors.white54,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
              Text(
                _scoreService
                        .calculate(
                          session: session,
                          history: allSessions,
                        )
                        .overall
                        ?.toString() ??
                    '—',
                style: const TextStyle(
                  color: accent,
                  fontSize: 20,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
        ),
      const SizedBox(height: AppSpacing.xxl),
      const InlineNotice(
        icon: Icons.info_outline,
        color: warning,
        text:
            'Оценка основана на длительности мониторинга и регулярности. '
            'Звуковые события показываются отдельно. Глубина и эффективность '
            'сна требуют EEG/PSG и здесь не измеряются. '
            'Это не медицинская диагностика.',
      ),
      const SizedBox(height: AppSpacing.xl),
      const Text(
        'Оценка регулярности появляется минимум после 3 завершённых сессий.',
        textAlign: TextAlign.center,
        style: TextStyle(color: Colors.white38, fontSize: 11),
      ),
    ];
  }
}
