
import 'package:material_ui/material_ui.dart';

import '../../../shared/widgets/placeholder_page.dart';

class AnalyticsPage extends StatelessWidget {
  const AnalyticsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderPage(
      icon: Icons.insights,
      title: 'Аналитика',
      description: 'Статистика, показатели и оценка качества сна',
    );
  }
}
