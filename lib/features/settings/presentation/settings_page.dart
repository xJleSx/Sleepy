
import 'package:material_ui/material_ui.dart';

import '../../../shared/widgets/placeholder_page.dart';

class SettingsPage extends StatelessWidget {
  const SettingsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderPage(
      icon: Icons.settings,
      title: 'Профиль',
      description: 'Настройки мониторинга, будильника и приложения',
    );
  }
}
