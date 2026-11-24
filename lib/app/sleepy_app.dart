import 'package:material_ui/material_ui.dart';

import 'main_screen.dart';
import 'sleepy_scope.dart';
import 'sleepy_services.dart';
import 'theme/app_theme.dart';

class SleepyApp extends StatefulWidget {
  const SleepyApp({super.key, this.services});

  final SleepyServices? services;

  @override
  State<SleepyApp> createState() => _SleepyAppState();
}

class _SleepyAppState extends State<SleepyApp> with WidgetsBindingObserver {
  late final SleepyServices _services;
  late final bool _ownsServices;

  @override
  void initState() {
    super.initState();
    _ownsServices = widget.services == null;
    _services = widget.services ?? SleepyServices();
    WidgetsBinding.instance.addObserver(this);
    _services.monitor.initialize();
    _services.alarms.load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    if (_ownsServices) {
      _services.dispose();
    }
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _services.monitor.syncAfterResume();
      _services.alarms.reload();
    }
  }

  @override
  Widget build(BuildContext context) {
    return SleepyScope(
      services: _services,
      child: MaterialApp(
        title: 'Sleepy',
        debugShowCheckedModeBanner: false,
        theme: sleepyTheme,
        home: const MainScreen(),
      ),
    );
  }
}
