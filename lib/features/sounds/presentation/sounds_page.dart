import 'package:material_ui/material_ui.dart';

import '../../../app/sleepy_scope.dart';
import '../../events/presentation/events_page.dart';

class SoundsPage extends StatelessWidget {
  const SoundsPage({super.key});

  @override
  Widget build(BuildContext context) {
    final services = SleepyScope.maybeOf(context);
    return EventsPage(gateway: services?.recording);
  }
}
