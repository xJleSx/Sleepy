
import 'package:material_ui/material_ui.dart';

import '../../../shared/widgets/placeholder_page.dart';

class SoundsPage extends StatelessWidget {
  const SoundsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderPage(
      icon: Icons.graphic_eq,
      title: 'Звуки',
      description: 'Аудиособытия, фрагменты записей и воспроизведение',
    );
  }
}
