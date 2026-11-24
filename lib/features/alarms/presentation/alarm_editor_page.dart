import 'package:material_ui/material_ui.dart';

import '../../../app/sleepy_scope.dart';
import '../../../app/theme/app_theme.dart';
import '../../../shared/widgets/app_components.dart';
import '../domain/alarm_sound.dart';
import '../domain/sleep_alarm.dart';
import '../domain/smart_alarm_window.dart';
import 'alarm_formatting.dart';

class AlarmEditorPage extends StatefulWidget {
  const AlarmEditorPage({super.key, this.alarm});

  final SleepAlarm? alarm;

  @override
  State<AlarmEditorPage> createState() => _AlarmEditorPageState();
}

class _AlarmEditorPageState extends State<AlarmEditorPage> {
  static const List<String> _dayLabels = <String>[
    'Пн',
    'Вт',
    'Ср',
    'Чт',
    'Пт',
    'Сб',
    'Вс',
  ];

  late final TextEditingController _label;
  late TimeOfDay _time;
  late Set<int> _weekdays;
  late AlarmSound _sound;
  late double _volume;
  late bool _smartEnabled;
  late TimeOfDay _windowStart;
  late TimeOfDay _windowEnd;
  late bool _enabled;
  late final String _id;

  bool _saving = false;

  @override
  void initState() {
    super.initState();
    final alarm = widget.alarm;
    _id = alarm?.id ?? 'alarm_${DateTime.now().microsecondsSinceEpoch}';
    _label = TextEditingController(text: alarm?.label ?? 'Будильник');
    _time = TimeOfDay(
      hour: alarm?.hour ?? 7,
      minute: alarm?.minute ?? 0,
    );
    _weekdays = Set<int>.of(
      alarm?.weekdays ?? const <int>{1, 2, 3, 4, 5, 6, 7},
    );
    _sound = alarm?.sound ?? AlarmSound.systemAlarm;
    _volume = alarm?.volume ?? 0.8;
    _smartEnabled = alarm?.smart.enabled ?? false;
    _enabled = alarm?.enabled ?? true;

    final startMinute = alarm?.smart.startMinute ?? 390;
    final endMinute = alarm?.smart.endMinute ?? 420;
    _windowStart = TimeOfDay(
      hour: startMinute ~/ 60,
      minute: startMinute % 60,
    );
    _windowEnd = TimeOfDay(hour: endMinute ~/ 60, minute: endMinute % 60);
  }

  @override
  void dispose() {
    _label.dispose();
    super.dispose();
  }

  Future<void> _pickTime({required bool windowStart}) async {
    final initial = windowStart ? _windowStart : _windowEnd;
    final selected = await showTimePicker(
      context: context,
      initialTime: initial,
    );
    if (selected == null || !mounted) return;

    setState(() {
      if (windowStart) {
        _windowStart = selected;
      } else {
        _windowEnd = selected;
      }
    });
  }

  Future<void> _pickAlarmTime() async {
    final selected = await showTimePicker(
      context: context,
      initialTime: _time,
    );
    if (selected == null || !mounted) return;
    setState(() {
      _time = selected;
    });
  }

  Future<void> _save() async {
    if (_saving) return;

    if (_smartEnabled && _toMinute(_windowEnd) <= _toMinute(_windowStart)) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          backgroundColor: warning,
          content: Text('Конец умного окна должен быть позже начала.'),
        ),
      );
      return;
    }

    _saving = true;
    setState(() {});

    final alarm = SleepAlarm(
      id: _id,
      label: _label.text.trim().isEmpty
          ? 'Будильник'
          : _label.text.trim(),
      hour: _time.hour,
      minute: _time.minute,
      weekdays: Set<int>.unmodifiable(_weekdays),
      enabled: _enabled,
      sound: _sound,
      volume: _volume,
      smart: SmartAlarmWindow(
        enabled: _smartEnabled,
        startMinute: _windowStart.hour * 60 + _windowStart.minute,
        endMinute: _windowEnd.hour * 60 + _windowEnd.minute,
      ),
    );

    try {
      final note = await SleepyScope.of(
        context,
      ).alarms.saveAlarm(alarm);
      if (!mounted) return;
      Navigator.of(context).pop(note ?? '');
    } catch (exception) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          backgroundColor: danger,
          content: Text(exception.toString()),
        ),
      );
      setState(() {
        _saving = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.alarm == null ? 'Новый будильник' : 'Изменить'),
        actions: [
          IconButton(
            onPressed: _saving ? null : _save,
            tooltip: 'Сохранить',
            icon: _saving
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: accent,
                    ),
                  )
                : const Icon(Icons.check),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(
          AppSpacing.xl,
          AppSpacing.lg,
          AppSpacing.xl,
          AppSpacing.xxxl,
        ),
        children: [
          AppCard(
            child: Column(
              children: [
                Text(
                  alarmTimeLabel(
                    SleepAlarm(
                      id: _id,
                      label: _label.text,
                      hour: _time.hour,
                      minute: _time.minute,
                      weekdays: _weekdays,
                      enabled: _enabled,
                      sound: _sound,
                      volume: _volume,
                      smart: SmartAlarmWindow(
                        enabled: _smartEnabled,
                        startMinute: _toMinute(_windowStart),
                        endMinute: _toMinute(_windowEnd),
                      ),
                    ),
                  ),
                  style: const TextStyle(
                    fontSize: 58,
                    height: 1,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 2,
                  ),
                ),
                const SizedBox(height: AppSpacing.lg),
                OutlinedButton.icon(
                  onPressed: _pickAlarmTime,
                  icon: const Icon(Icons.schedule),
                  label: const Text('Выбрать время'),
                ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.xxl),
          const SectionLabel('Название'),
          AppCard(
            child: TextField(
              controller: _label,
              textCapitalization: TextCapitalization.sentences,
              decoration: const InputDecoration(
                hintText: 'Например, на работу',
              ),
              onChanged: (_) => setState(() {}),
            ),
          ),
          const SizedBox(height: AppSpacing.xxl),
          const SectionLabel('Дни недели'),
          AppCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Wrap(
                  spacing: AppSpacing.sm,
                  runSpacing: AppSpacing.sm,
                  children: [
                    for (var day = 1; day <= 7; day++)
                      FilterChip(
                        label: Text(_dayLabels[day - 1]),
                        selected: _weekdays.contains(day),
                        onSelected: (selected) {
                          setState(() {
                            if (selected) {
                              _weekdays.add(day);
                            } else {
                              _weekdays.remove(day);
                            }
                          });
                        },
                      ),
                  ],
                ),
                const SizedBox(height: AppSpacing.md),
                Text(
                  _weekdays.isEmpty
                      ? 'Ничего не выбрано: будильник сработает один раз.'
                      : 'Повтор: ${_daysHint(_weekdays)}',
                  style: const TextStyle(
                    color: Colors.white54,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.xxl),
          const SectionLabel('Звук и громкость'),
          AppCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                DropdownButtonFormField<AlarmSound>(
                  initialValue: _sound,
                  decoration: const InputDecoration(
                    labelText: 'Звук',
                  ),
                  items: AlarmSound.values
                      .map(
                        (sound) => DropdownMenuItem<AlarmSound>(
                          value: sound,
                          child: Text(sound.label),
                        ),
                      )
                      .toList(),
                  onChanged: (value) {
                    if (value == null) return;
                    setState(() {
                      _sound = value;
                    });
                  },
                ),
                const SizedBox(height: AppSpacing.lg),
                Row(
                  children: [
                    const Icon(Icons.volume_down, color: Colors.white54),
                    Expanded(
                      child: Slider(
                        value: _volume,
                        onChanged: (value) {
                          setState(() {
                            _volume = value;
                          });
                        },
                      ),
                    ),
                    SizedBox(
                      width: 44,
                      child: Text(
                        '${(_volume * 100).round()}%',
                        textAlign: TextAlign.right,
                        style: const TextStyle(color: Colors.white54),
                      ),
                    ),
                  ],
                ),
                const Text(
                  'Громкость применяется к потоку будильника. '
                  'Системный ограничитель громкости остаётся главным.',
                  style: TextStyle(color: Colors.white38, fontSize: 12),
                ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.xxl),
          const SectionLabel('Умный режим'),
          AppCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Умное пробуждение'),
                  subtitle: const Text(
                    'Окно вокруг обязательного времени',
                    style: TextStyle(color: Colors.white54),
                  ),
                  value: _smartEnabled,
                  onChanged: (value) {
                    setState(() {
                      _smartEnabled = value;
                    });
                  },
                ),
                if (_smartEnabled) ...[
                  const SizedBox(height: AppSpacing.sm),
                  Row(
                    children: [
                      Expanded(
                        child: _TimeChoice(
                          label: 'Начало окна',
                          value: minuteOfDayLabel(_toMinute(_windowStart)),
                          onTap: () => _pickTime(windowStart: true),
                        ),
                      ),
                      const SizedBox(width: AppSpacing.md),
                      Expanded(
                        child: _TimeChoice(
                          label: 'Конец окна',
                          value: minuteOfDayLabel(_toMinute(_windowEnd)),
                          onTap: () => _pickTime(windowStart: false),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: AppSpacing.lg),
                  const InlineNotice(
                    icon: Icons.info_outline,
                    color: warning,
                    text:
                        'Сейчас используется безопасная резервная логика: '
                        'сигнал сработает в обязательное время. Sleepy не '
                        'определяет стадии сна и не выбирает момент по '
                        'неподтверждённым аудиоданным.',
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.xxl),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Будильник включён'),
            value: _enabled,
            onChanged: (value) {
              setState(() {
                _enabled = value;
              });
            },
          ),
          const SizedBox(height: AppSpacing.xxl),
          FilledButton(
            onPressed: _saving ? null : _save,
            style: FilledButton.styleFrom(
              padding: const EdgeInsets.symmetric(vertical: 18),
            ),
            child: Text(_saving ? 'Сохраняем…' : 'Сохранить будильник'),
          ),
          const SizedBox(height: AppSpacing.md),
          const Text(
            'Настройки хранятся локально. Для срабатывания при закрытом '
            'интерфейсе Android использует AlarmManager и foreground-службу; '
            'точность зависит от разрешений и энергосбережения устройства.',
            textAlign: TextAlign.center,
            style: TextStyle(color: Colors.white38, fontSize: 12),
          ),
        ],
      ),
    );
  }

  int _toMinute(TimeOfDay value) => value.hour * 60 + value.minute;

  String _daysHint(Set<int> days) {
    if (days.length == 7) return 'каждый день';
    final sorted = days.toList()..sort();
    return sorted.map((day) => _dayLabels[day - 1]).join(', ');
  }
}

class _TimeChoice extends StatelessWidget {
  const _TimeChoice({
    required this.label,
    required this.value,
    required this.onTap,
  });

  final String label;
  final String value;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(AppRadii.md),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.all(AppSpacing.md),
        decoration: BoxDecoration(
          color: background,
          borderRadius: BorderRadius.circular(AppRadii.md),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              label,
              style: const TextStyle(
                color: Colors.white54,
                fontSize: 11,
              ),
            ),
            const SizedBox(height: AppSpacing.xs),
            Text(
              value,
              style: const TextStyle(
                fontSize: 20,
                fontWeight: FontWeight.w700,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
