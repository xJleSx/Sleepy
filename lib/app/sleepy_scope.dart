import 'package:material_ui/material_ui.dart';

import 'sleepy_services.dart';

class SleepyScope extends InheritedWidget {
  const SleepyScope({
    super.key,
    required this.services,
    required super.child,
  });

  final SleepyServices services;

  static SleepyServices of(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<SleepyScope>();
    assert(scope != null, 'SleepyScope отсутствует в дереве виджетов');
    return scope!.services;
  }

  static SleepyServices? maybeOf(BuildContext context) {
    return context
        .dependOnInheritedWidgetOfExactType<SleepyScope>()
        ?.services;
  }

  @override
  bool updateShouldNotify(SleepyScope oldWidget) {
    return services != oldWidget.services;
  }
}
