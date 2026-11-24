enum SleepScoreDimension {
  duration('Продолжительность'),
  regularity('Регулярность'),
  depth('Глубина'),
  efficiency('Эффективность');

  const SleepScoreDimension(this.label);

  final String label;
}

class SleepScoreComponent {
  const SleepScoreComponent({
    required this.dimension,
    required this.value,
    required this.explanation,
  });

  const SleepScoreComponent.unavailable(
    this.dimension,
    this.explanation,
  ) : value = null;

  final SleepScoreDimension dimension;
  final double? value;
  final String explanation;

  bool get isAvailable => value != null;

  int? get roundedValue {
    final current = value;
    if (current == null) return null;
    return current.round().clamp(0, 100);
  }
}

class SleepScore {
  const SleepScore({
    required this.duration,
    required this.regularity,
    required this.depth,
    required this.efficiency,
    required this.overall,
    required this.provisional,
    required this.explanation,
  });

  final SleepScoreComponent duration;
  final SleepScoreComponent regularity;
  final SleepScoreComponent depth;
  final SleepScoreComponent efficiency;
  final int? overall;
  final bool provisional;
  final String explanation;

  List<SleepScoreComponent> get components => <SleepScoreComponent>[
    duration,
    regularity,
    depth,
    efficiency,
  ];
}
