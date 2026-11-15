
import 'package:material_ui/material_ui.dart';

import 'main_screen.dart';
import 'theme/app_theme.dart';

class SleepyApp extends StatelessWidget {
  const SleepyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Sleepy',
      debugShowCheckedModeBanner: false,
      theme: sleepyTheme,
      home: const MainScreen(),
    );
  }
}
