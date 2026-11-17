import 'package:material_ui/material_ui.dart';

import '../../../app/theme/app_theme.dart';
import '../../diagnostics/presentation/diagnostics_page.dart';

class SettingsPage extends StatelessWidget {
  const SettingsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        const Row(
          children: [
            Icon(Icons.settings, color: accent, size: 30),
            SizedBox(width: 10),
            Text(
              'Профиль',
              style: TextStyle(fontSize: 26, fontWeight: FontWeight.w700),
            ),
          ],
        ),
        const SizedBox(height: 8),
        const Text(
          'Настройки мониторинга, будильника и приложения',
          style: TextStyle(color: Colors.white54),
        ),
        const SizedBox(height: 28),
        Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: surface,
            borderRadius: BorderRadius.circular(24),
            border: Border.all(color: accent.withValues(alpha: 0.18)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Row(
                children: [
                  Icon(Icons.monitor_heart_outlined, color: secondary),
                  SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      'Диагностика записи',
                      style: TextStyle(fontWeight: FontWeight.w600),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              const Text(
                'Состояние захвата, детектора, writer и проверка сохранённых '
                'WAV-файлов. Помогает понять, где теряются аудиособытия.',
                style: TextStyle(color: Colors.white54, height: 1.4),
              ),
              const SizedBox(height: 16),
              FilledButton.icon(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => const DiagnosticsPage(),
                    ),
                  );
                },
                style: FilledButton.styleFrom(
                  backgroundColor: accent,
                  foregroundColor: background,
                ),
                icon: const Icon(Icons.arrow_forward),
                label: const Text('Открыть диагностику'),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
