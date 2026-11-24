import 'package:material_ui/material_ui.dart';

import '../../../app/sleepy_scope.dart';
import '../../../app/sleepy_services.dart';
import '../../../app/theme/app_theme.dart';
import '../../../shared/widgets/app_components.dart';
import '../../diagnostics/presentation/diagnostics_page.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  SleepyServices? _services;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final services = SleepyScope.of(context);
    if (identical(services, _services)) return;

    _services?.monitor.removeListener(_onChanged);
    _services = services;
    services.monitor.addListener(_onChanged);
  }

  @override
  void dispose() {
    _services?.monitor.removeListener(_onChanged);
    super.dispose();
  }

  void _onChanged() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final status = _services?.monitor.status;
    final alarms = _services?.alarms;

    return ScrollContent(
      children: [
        const ScreenTitle(
          title: 'Настройки',
          subtitle: 'Состояние приложения, данные и диагностика',
        ),
        const SizedBox(height: AppSpacing.xxl),
        const SectionLabel('Мониторинг'),
        AppCard(
          child: Column(
            children: [
              _row(
                'Запись',
                status?.isRecording == true ? 'активна' : 'остановлена',
                status?.isRecording == true ? accent : Colors.white54,
              ),
              const Divider(height: AppSpacing.xl),
              _row(
                'Микрофон и служба',
                status?.hasError == true
                    ? 'ошибка'
                    : status?.hasWarning == true
                    ? 'предупреждение'
                    : 'нет ошибок',
                status?.hasError == true
                    ? danger
                    : status?.hasWarning == true
                    ? warning
                    : Colors.white54,
              ),
              const Divider(height: AppSpacing.xl),
              _row(
                'Фрагментов на диске',
                (status?.fragmentCount ?? 0).toString(),
              ),
            ],
          ),
        ),
        const SizedBox(height: AppSpacing.xxl),
        const SectionLabel('Будильник'),
        AppCard(
          child: Column(
            children: [
              _row(
                'Сохранённых будильников',
                (alarms?.alarms.length ?? 0).toString(),
              ),
              const Divider(height: AppSpacing.xl),
              _row(
                'Точное время',
                alarms?.exactAllowed == true
                    ? 'доступно'
                    : 'система может сдвинуть сигнал',
                alarms?.exactAllowed == true ? accent : warning,
              ),
              const SizedBox(height: AppSpacing.md),
              const Text(
                'Разрешение на точные будильники зависит от версии Android '
                'и настроек энергосбережения. Если оно недоступно, Sleepy '
                'использует безопасное неточное срабатывание.',
                style: TextStyle(color: Colors.white38, fontSize: 12),
              ),
            ],
          ),
        ),
        const SizedBox(height: AppSpacing.xxl),
        const SectionLabel('Данные'),
        const AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(Icons.lock_outline, color: secondary, size: 20),
                  SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Text(
                      'Всё хранится локально',
                      style: TextStyle(fontWeight: FontWeight.w600),
                    ),
                  ),
                ],
              ),
              SizedBox(height: AppSpacing.md),
              Text(
                'Sleepy не отправляет записи, историю сна и настройки на '
                'сервер. Аудиофрагменты находятся в приватном каталоге '
                'приложения и удаляются только по вашему действию.',
                style: TextStyle(color: Colors.white54, height: 1.4),
              ),
            ],
          ),
        ),
        const SizedBox(height: AppSpacing.xxl),
        const SectionLabel('Диагностика'),
        AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Состояние захвата, детектора, writer и проверка сохранённых '
                'WAV-файлов.',
                style: TextStyle(color: Colors.white54, height: 1.4),
              ),
              const SizedBox(height: AppSpacing.lg),
              FilledButton.icon(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => const DiagnosticsPage(),
                    ),
                  );
                },
                icon: const Icon(Icons.arrow_forward),
                label: const Text('Открыть диагностику'),
              ),
            ],
          ),
        ),
        const SizedBox(height: AppSpacing.xxl),
        const InlineNotice(
          icon: Icons.health_and_safety_outlined,
          color: warning,
          text:
              'Sleepy не диагностирует апноэ, не измеряет стадии сна и не '
              'заменяет медицинскую консультацию. Оценка режима Sleepy — '
              'ориентир по доступным данным, а не клинический показатель.',
        ),
        const SizedBox(height: AppSpacing.xxl),
        const Text(
          'Sleepy 0.5 · локальная разработческая сборка',
          textAlign: TextAlign.center,
          style: TextStyle(color: Colors.white24, fontSize: 11),
        ),
      ],
    );
  }

  Widget _row(String label, String value, [Color color = Colors.white]) {
    return Row(
      children: [
        Expanded(
          child: Text(
            label,
            style: const TextStyle(color: Colors.white54, fontSize: 13),
          ),
        ),
        Text(
          value,
          style: TextStyle(
            color: color,
            fontWeight: FontWeight.w600,
            fontSize: 13,
          ),
        ),
      ],
    );
  }
}
