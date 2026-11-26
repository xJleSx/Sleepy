enum RecordingProfile {
  standard(
    id: 'STANDARD',
    label: 'Стандартный',
    description: 'Текущая чувствительность. Меньше ложных срабатываний.',
  ),
  high(
    id: 'HIGH',
    label: 'Высокая',
    description:
        'Ловит тихие звуки и сохраняет слабые кандидаты для проверки.',
  ),
  veryHigh(
    id: 'VERY_HIGH',
    label: 'Очень высокая',
    description:
        'Максимальная чувствительность. Больше кандидатов и ложных тревог.',
  );

  const RecordingProfile({
    required this.id,
    required this.label,
    required this.description,
  });

  final String id;
  final String label;
  final String description;

  static RecordingProfile fromId(String? value) {
    return RecordingProfile.values.firstWhere(
      (profile) => profile.id == value,
      orElse: () => RecordingProfile.standard,
    );
  }
}
