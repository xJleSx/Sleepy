
import 'package:material_ui/material_ui.dart';

const background = Color(0xFF0B0D14);
const surface = Color(0xFF151925);
const accent = Color(0xFF9B8CFF);
const secondary = Color(0xFF72C8FF);

final sleepyTheme = ThemeData(
  brightness: Brightness.dark,
  scaffoldBackgroundColor: background,
  colorScheme: const ColorScheme.dark(
    primary: accent,
    surface: surface,
  ),
  useMaterial3: true,
);
