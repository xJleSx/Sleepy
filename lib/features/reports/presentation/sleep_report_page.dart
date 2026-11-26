import 'package:material_ui/material_ui.dart';

import '../../../app/sleepy_scope.dart';
import '../../../app/sleepy_services.dart';
import '../../../app/theme/app_theme.dart';
import '../../../shared/formatting/ru_date.dart';
import '../../../shared/widgets/app_components.dart';
import '../../events/presentation/event_formatting.dart';
import '../../sessions/application/sleep_report_service.dart';
import '../../sessions/domain/sleep_report.dart';
import '../../sessions/domain/sleep_score.dart';
import '../../sessions/domain/sleep_session.dart';

class SleepReportPage extends StatefulWidget {
  const SleepReportPage({super.key, required this.sessionId});

  final String sessionId;

  @override
  State<SleepReportPage> createState() => _SleepReportPageState();
}

class _SleepReportPageState extends State<SleepReportPage> {
  static const _reportService = SleepReportService();
  static const _noiseChartHeight = 120.0;
  static const _noiseMinDbfs = -80.0;
  static const _noiseMaxDbfs = 0.0;
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
    final session = controller?.findById(widget.sessionId);

    return Scaffold(
      appBar: AppBar(title: const Text('Отчёт за ночь')),
      body: ScrollContent(
        onRefresh: controller?.reload,
        children: [
          if (session == null)
            AppCard(
              child: controller == null || controller.isLoading
                  ? const Padding(
                      padding: EdgeInsets.symmetric(vertical: 28),
                      child: Center(
                        child: CircularProgressIndicator(color: accent),
                      ),
                    )
                  : const EmptyStateCard(
                      icon: Icons.search_off,
                      title: 'Сессия не найдена',
                      message:
                          'Возможно, запись была удалена или ещё не '
                          'завершена.',
                    ),
            )
          else
            ..._content(
              _reportService.build(
                session: session,
                allSessions: controller?.sessions ?? const <SleepSession>[],
              ),
            ),
        ],
      ),
    );
  }

  List<Widget> _content(SleepReport report) {
    return <Widget>[
      _header(report),
      const SizedBox(height: AppSpacing.xxl),
      const SectionLabel('Из чего складывается оценка'),
      for (final component in report.score.components)
        _scoreComponent(component),
      const SizedBox(height: AppSpacing.xxl),
      const SectionLabel('Звуковые события по часам'),
      _timeline(report),
      const SizedBox(height: AppSpacing.xxl),
      const SectionLabel('Шум по часам'),
      _noiseTimeline(report),
      const SizedBox(height: AppSpacing.xxl),
      const SectionLabel('Сравнение'),
      _comparison(report.comparison),
      const SizedBox(height: AppSpacing.xxl),
      const SectionLabel('Заметные события'),
      if (report.interestingEvents.isEmpty)
        const AppCard(
          child: Text(
            'За эту ночь детектор не сохранил ни одного фрагмента.',
            style: TextStyle(color: Colors.white54),
          ),
        )
      else
        for (final event in report.interestingEvents)
          AppCard(
            child: Row(
              children: [
                const Icon(Icons.graphic_eq, color: accent, size: 20),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        formatClockShort(event.startedAt),
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        '${formatDuration(event.duration, roundUp: true)} · '
                        '${formatBytes(event.sizeBytes)}',
                        style: const TextStyle(
                          color: Colors.white54,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
                const StatusPill(
                  label: 'тип не определён',
                  color: Colors.white38,
                ),
              ],
            ),
          ),
      const SizedBox(height: AppSpacing.xxl),
      const SectionLabel('Что можно улучшить'),
      AppCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (var index = 0; index < report.recommendations.length; index++)
              Padding(
                padding: EdgeInsets.only(
                  bottom: index == report.recommendations.length - 1
                      ? 0
                      : AppSpacing.md,
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(Icons.arrow_right, color: accent, size: 18),
                    const SizedBox(width: AppSpacing.sm),
                    Expanded(
                      child: Text(
                        report.recommendations[index],
                        style: const TextStyle(color: Colors.white70),
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
      const SizedBox(height: AppSpacing.xxl),
      InlineNotice(
        icon: Icons.info_outline,
        color: warning,
        text: report.dataNote,
      ),
      const SizedBox(height: AppSpacing.xxl),
      Text(
        'Сессия ${report.session.id}',
        textAlign: TextAlign.center,
        style: const TextStyle(color: Colors.white24, fontSize: 11),
      ),
    ];
  }

  Widget _header(SleepReport report) {
    final overall = report.score.overall;

    return AppCard(
      child: Column(
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      formatRussianShortDate(report.startedAt),
                      style: const TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '${formatClockShort(report.startedAt)}–'
                      '${formatClockShort(report.endedAt)}',
                      style: const TextStyle(color: Colors.white54),
                    ),
                  ],
                ),
              ),
              if (report.session.status == SleepSessionStatus.interrupted)
                const StatusPill(label: 'Прервано', color: warning),
            ],
          ),
          const SizedBox(height: AppSpacing.xl),
          Container(
            width: 132,
            height: 132,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              border: Border.all(
                color: accent.withValues(alpha: 0.75),
                width: 6,
              ),
              color: accent.withValues(alpha: 0.08),
            ),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  overall?.toString() ?? '—',
                  style: const TextStyle(
                    fontSize: 42,
                    height: 1,
                    fontWeight: FontWeight.w700,
                    color: accent,
                  ),
                ),
                const SizedBox(height: 4),
                const Text(
                  'из 100',
                  style: TextStyle(color: Colors.white54, fontSize: 12),
                ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
          const Text(
            'Оценка режима сна, а не медицинский показатель',
            textAlign: TextAlign.center,
            style: TextStyle(color: Colors.white54, fontSize: 12),
          ),
          if (report.score.provisional) ...[
            const SizedBox(height: AppSpacing.md),
            const StatusPill(label: 'предварительная оценка', color: warning),
          ],
          const SizedBox(height: AppSpacing.xl),
          Row(
            children: [
              Expanded(
                child: MetricTile(
                  icon: Icons.bedtime_outlined,
                  label: 'длительность',
                  value: formatDurationLabel(report.duration),
                ),
              ),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: MetricTile(
                  icon: Icons.graphic_eq,
                  label: 'события',
                  value: report.eventCount.toString(),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _scoreComponent(SleepScoreComponent component) {
    final value = component.roundedValue;

    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: AppCard(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 42,
              child: Text(
                value?.toString() ?? '—',
                style: TextStyle(
                  color: value == null ? Colors.white38 : accent,
                  fontSize: 20,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    component.dimension.label,
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    component.explanation,
                    style: const TextStyle(color: Colors.white54, fontSize: 12),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _timeline(SleepReport report) {
    final buckets = report.distribution;
    if (buckets.isEmpty) {
      return const AppCard(
        child: Text(
          'Недостаточно данных для почасовой разбивки.',
          style: TextStyle(color: Colors.white54),
        ),
      );
    }

    final maxCount = buckets.fold<int>(
      1,
      (max, bucket) => bucket.count > max ? bucket.count : max,
    );

    return AppCard(
      child: Column(
        children: [
          SizedBox(
            height: 96,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                for (final bucket in buckets)
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 2),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.end,
                        children: [
                          Text(
                            bucket.count == 0 ? '' : '${bucket.count}',
                            style: const TextStyle(
                              color: Colors.white38,
                              fontSize: 10,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Container(
                            height: 4 + 66 * bucket.count / maxCount,
                            decoration: BoxDecoration(
                              color: bucket.count == 0
                                  ? Colors.white12
                                  : accent,
                              borderRadius: BorderRadius.circular(3),
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            formatClockShort(bucket.start).substring(0, 2),
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
          const SizedBox(height: AppSpacing.md),
          const Text(
            'Количество фрагментов, начавшихся в каждый час.',
            style: TextStyle(color: Colors.white38, fontSize: 11),
          ),
        ],
      ),
    );
  }

  Widget _noiseTimeline(SleepReport report) {
    final buckets = report.distribution;
    final hasData = buckets.any((bucket) => bucket.hasNoiseData);
    if (!hasData) {
      return const AppCard(
        child: Text(
          'Для этой сессии уровень громкости записей не сохранился.',
          style: TextStyle(color: Colors.white54),
        ),
      );
    }

    return AppCard(
      child: Column(
        children: [
          SizedBox(
            height: _noiseChartHeight,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SizedBox(
                  width: 34,
                  child: Stack(
                    children: [
                      Positioned(top: 0, left: 0, child: _noiseAxisLabel('0')),
                      Positioned(
                        top: _noiseChartHeight / 2 - 6,
                        left: 0,
                        child: _noiseAxisLabel('-40'),
                      ),
                      Positioned(
                        bottom: 0,
                        left: 0,
                        child: _noiseAxisLabel('-80'),
                      ),
                    ],
                  ),
                ),
                Expanded(
                  child: Stack(
                    children: [
                      Positioned(
                        top: 0,
                        left: 0,
                        right: 0,
                        child: _noiseGridLine(),
                      ),
                      Positioned(
                        top: _noiseChartHeight / 2,
                        left: 0,
                        right: 0,
                        child: _noiseGridLine(),
                      ),
                      Positioned(
                        bottom: 0,
                        left: 0,
                        right: 0,
                        child: _noiseGridLine(),
                      ),
                      Positioned.fill(
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.end,
                          children: [
                            for (final bucket in buckets)
                              Expanded(
                                child: Padding(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 2,
                                  ),
                                  child: Align(
                                    alignment: Alignment.bottomCenter,
                                    child: Container(
                                      height: bucket.averageDbfs == null
                                          ? 2
                                          : _noiseBarHeight(
                                              bucket.averageDbfs!,
                                            ),
                                      decoration: BoxDecoration(
                                        color: bucket.averageDbfs == null
                                            ? Colors.white12
                                            : accent.withValues(alpha: 0.85),
                                        borderRadius: BorderRadius.circular(3),
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.sm),
          Row(
            children: [
              const SizedBox(width: 34),
              for (final bucket in buckets)
                Expanded(
                  child: Text(
                    formatClockShort(bucket.start).substring(0, 2),
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: Colors.white38, fontSize: 9),
                  ),
                ),
            ],
          ),
          const SizedBox(height: AppSpacing.md),
          const Text(
            'Средняя громкость сохранённых фрагментов по часам. '
            'Промежутки без записей не измеряются.',
            style: TextStyle(color: Colors.white38, fontSize: 11),
          ),
        ],
      ),
    );
  }

  Widget _noiseAxisLabel(String text) {
    return Text(
      text,
      style: const TextStyle(color: Colors.white38, fontSize: 9),
    );
  }

  Widget _noiseGridLine() {
    return Container(height: 1, color: Colors.white12);
  }

  double _noiseBarHeight(double dbfs) {
    final clamped = dbfs.clamp(_noiseMinDbfs, _noiseMaxDbfs);
    final normalized =
        (clamped - _noiseMinDbfs) / (_noiseMaxDbfs - _noiseMinDbfs);
    return (normalized * _noiseChartHeight)
        .clamp(2.0, _noiseChartHeight)
        .toDouble();
  }

  Widget _comparison(SleepComparison comparison) {
    return AppCard(
      child: Column(
        children: [
          _comparisonRow(
            'Предыдущая ночь',
            comparison.previousDuration == null
                ? 'нет данных'
                : formatDurationLabel(comparison.previousDuration!),
          ),
          const Divider(height: AppSpacing.xl),
          _comparisonRow(
            'Среднее за ${comparison.sessionsCompared} ноч. до этой',
            comparison.sevenNightAverage == null
                ? 'нет данных'
                : formatDurationLabel(comparison.sevenNightAverage!),
          ),
        ],
      ),
    );
  }

  Widget _comparisonRow(String label, String value) {
    return Row(
      children: [
        Expanded(
          child: Text(
            label,
            style: const TextStyle(color: Colors.white54, fontSize: 13),
          ),
        ),
        Text(value, style: const TextStyle(fontWeight: FontWeight.w600)),
      ],
    );
  }
}
