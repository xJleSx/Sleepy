
import 'package:material_ui/material_ui.dart';

import '../../../shared/widgets/placeholder_page.dart';

class HistoryPage extends StatelessWidget {
  const HistoryPage({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderPage(
      icon: Icons.history,
      title: 'История',
      description: 'Архив ночных сессий и сравнение результатов',
    );
  }
}
