import 'package:material_ui/material_ui.dart';

import '../../../app/sleepy_scope.dart';
import '../../../app/sleepy_services.dart';
import '../../../app/theme/app_theme.dart';
import '../../../shared/formatting/ru_date.dart';
import '../../../shared/widgets/app_components.dart';
import '../application/alarm_controller.dart';
import '../domain/sleep_alarm.dart';
import 'alarm_editor_page.dart';
import 'alarm_formatting.dart';

class AlarmPage extends StatefulWidget {
  const AlarmPage({super.key});

  @override
  State<AlarmPage> createState() => _AlarmPageState();
}

class _AlarmPageState extends State<AlarmPage> {
  SleepyServices? _services;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final services = SleepyScope.of(context);
    if (identical(services, _services)) return;

    _services?.alarms.removeListener(_onChanged);
    _services = services;
    services.alarms.addListener(_onChanged);
    services.alarms.load();
  }

  @override
  void dispose() {
    _services?.alarms.removeListener(_onChanged);
    super.dispose();
  }

  void _onChanged() {
    if (mounted) setState(() {});
  }

  Future<void> _openEditor([SleepAlarm? alarm]) async {
    final note = await Navigator.of(context).push<String?>(
      MaterialPageRoute<String?>(
        builder: (_) => AlarmEditorPage(alarm: alarm),
      ),
    );

    if (!mounted || note == null || note.isEmpty) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(backgroundColor: warning, content: Text(note)),
    );
  }

  Future<void> _toggle(SleepAlarm alarm, bool enabled) async {
    try {
      await _services!.alarms.setEnabled(alarm, enabled);
    } catch (exception) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          backgroundColor: danger,
          content: Text(exception.toString()),
        ),
      );
    }
  }

  Future<void> _delete(SleepAlarm alarm) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Удалить будильник?'),
        content: Text('«${alarm.label}» больше не будет запланирован.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Отмена'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            style: FilledButton.styleFrom(backgroundColor: danger),
            child: const Text('Удалить'),
          ),
        ],
      ),
    );

    if (confirmed != true || !mounted) return;

    try {
      await _services!.alarms.deleteAlarm(alarm.id);
    } catch (exception) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          backgroundColor: danger,
          content: Text(exception.toString()),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final controller = _services?.alarms;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Будильник'),
        actions: [
          IconButton(
            onPressed: _services?.alarms.reload,
            tooltip: 'Обновить',
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _openEditor(),
        backgroundColor: accent,
        foregroundColor: background,
        icon: const Icon(Icons.add_alarm),
        label: const Text('Добавить'),
      ),
      body: ScrollContent(
        onRefresh: controller?.reload,
        children: [
          const ScreenTitle(
            title: 'Ночной ритм',
            subtitle: 'Обычный и осторожный умный будильник',
          ),
          const SizedBox(height: AppSpacing.xxl),
          if (controller == null)
            const Center(
              child: CircularProgressIndicator(color: accent),
            )
          else ...[
            _capabilityCard(controller),
            const SizedBox(height: AppSpacing.xxl),
            if (controller.isLoading && !controller.isLoaded)
              const AppCard(
                child: Padding(
                  padding: EdgeInsets.symmetric(vertical: 24),
                  child: Center(
                    child: CircularProgressIndicator(color: accent),
                  ),
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
            else if (controller.alarms.isEmpty)
              const EmptyStateCard(
                icon: Icons.alarm_add,
                title: 'Будильников пока нет',
                message:
                    'Добавьте время и дни. Настройки сохранятся локально '
                    'и переживут перезапуск приложения.',
              )
            else
              for (final alarm in controller.alarms)
                _alarmCard(controller, alarm),
            const SizedBox(height: 80),
          ],
        ],
      ),
    );
  }

  Widget _capabilityCard(AlarmController controller) {
    final exactAllowed = controller.exactAllowed;

    return AppCard(
      borderColor: (exactAllowed ? success : warning).withValues(alpha: 0.3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                exactAllowed ? Icons.verified_outlined : Icons.info_outline,
                color: exactAllowed ? success : warning,
              ),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(
                  exactAllowed
                      ? 'Точные будильники разрешены'
                      : 'Точность ограничена Android',
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.sm),
          Text(
            exactAllowed
                ? 'Android разрешает точное срабатывание. Если интерфейс '
                      'закрыт, сигнал запускает foreground-служба.'
                : 'Android может сдвинуть сигнал. Разрешите точные будильники '
                      'в системных настройках или продублируйте критичное '
                      'пробуждение в системных часах.',
            style: const TextStyle(color: Colors.white54, height: 1.45),
          ),
          if (!exactAllowed) ...[
            const SizedBox(height: AppSpacing.md),
            TextButton.icon(
              onPressed: () async {
                try {
                  await controller.requestExactAlarmPermission();
                } catch (exception) {
                  if (!mounted) return;
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(
                      backgroundColor: danger,
                      content: Text(exception.toString()),
                    ),
                  );
                }
              },
              icon: const Icon(Icons.settings_outlined),
              label: const Text('Открыть настройки'),
            ),
          ],
        ],
      ),
    );
  }

  Widget _alarmCard(AlarmController controller, SleepAlarm alarm) {
    final note = controller.scheduleNote(alarm.id);
    final next = controller.nextTriggerFor(alarm);

    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: AppCard(
        borderColor: alarm.enabled
            ? accent.withValues(alpha: 0.25)
            : subtleBorder,
        onTap: () => _openEditor(alarm),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        alarmTimeLabel(alarm),
                        style: TextStyle(
                          fontSize: 38,
                          height: 1,
                          fontWeight: FontWeight.w700,
                          color: alarm.enabled ? Colors.white : Colors.white38,
                        ),
                      ),
                      const SizedBox(height: AppSpacing.sm),
                      Text(
                        alarm.label,
                        style: const TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 3),
                      Text(
                        '${alarmDaysLabel(alarm)} · '
                        '${alarm.sound.label}',
                        style: const TextStyle(
                          color: Colors.white54,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
                Switch(
                  value: alarm.enabled,
                  onChanged: (value) => _toggle(alarm, value),
                ),
                PopupMenuButton<String>(
                  onSelected: (value) {
                    if (value == 'delete') _delete(alarm);
                    if (value == 'edit') _openEditor(alarm);
                  },
                  itemBuilder: (context) => const [
                    PopupMenuItem(value: 'edit', child: Text('Изменить')),
                    PopupMenuItem(value: 'delete', child: Text('Удалить')),
                  ],
                ),
              ],
            ),
            if (alarm.smart.enabled) ...[
              const SizedBox(height: AppSpacing.md),
              Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.sm,
                children: [
                  StatusPill(
                    label: 'Умное окно ${alarmWindowLabel(alarm)}',
                    color: accent,
                    icon: Icons.auto_awesome,
                  ),
                  const StatusPill(
                    label: 'Сейчас: резервное время',
                    color: warning,
                    icon: Icons.shield_outlined,
                  ),
                ],
              ),
            ],
            if (next != null && alarm.enabled) ...[
              const SizedBox(height: AppSpacing.md),
              Text(
                'Следующее срабатывание: ${_nextLabel(next)}',
                style: const TextStyle(
                  color: Colors.white54,
                  fontSize: 12,
                ),
              ),
            ],
            if (note != null && note.isNotEmpty) ...[
              const SizedBox(height: AppSpacing.md),
              InlineNotice(
                icon: Icons.info_outline,
                color: warning,
                text: note,
              ),
            ],
          ],
        ),
      ),
    );
  }

  String _nextLabel(DateTime time) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final target = DateTime(time.year, time.month, time.day);
    final days = target.difference(today).inDays;

    final prefix = switch (days) {
      0 => 'сегодня',
      1 => 'завтра',
      _ => 'через $days дн.',
    };
    return '$prefix, ${formatClockShort(time)}';
  }
}
