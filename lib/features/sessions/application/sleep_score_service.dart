import 'dart:math' as math;

import '../domain/sleep_score.dart';
import '../domain/sleep_session.dart';

/// Оценка режима, а не клиническая оценка сна.
///
/// Формулы намеренно отделены от UI:
/// - продолжительность: 7-9 часов = 100; меньше 7 — линейно к 0;
///   больше 9 — минус 15 баллов за каждый час;
/// - регулярность: нужен минимум 3 завершённых сессии, сравниваются
///   локальные минуты начала и окончания за последние 7 ночей;
///   среднее циклическое отклонение 90 минут соответствует 0 баллам;
/// - глубина и эффективность недоступны без EEG/PSG и классификатора
///   сон/бодрствование, поэтому не вычисляются из аудиособытий.
class SleepScoreService {
  const SleepScoreService();

  SleepScore calculate({
    required SleepSession session,
    required List<SleepSession> history,
  }) {
    final duration = _durationComponent(session.duration);
    final regularity = _regularityComponent(session, history);
    const depth = SleepScoreComponent.unavailable(
      SleepScoreDimension.depth,
      'Нет EEG/PSG. Аудиособытия не позволяют измерить глубину сна.',
    );
    const efficiency = SleepScoreComponent.unavailable(
      SleepScoreDimension.efficiency,
      'Нет достоверного разделения сна и бодрствования. '
      'Время в постели не равно времени сна.',
    );

    final weighted = <(double, SleepScoreComponent)>[
      if (duration.isAvailable) (0.6, duration),
      if (regularity.isAvailable) (0.4, regularity),
    ];

    int? overall;
    if (weighted.isNotEmpty) {
      final weightSum = weighted.fold<double>(
        0,
        (sum, item) => sum + item.$1,
      );
      final valueSum = weighted.fold<double>(
        0,
        (sum, item) => sum + item.$1 * item.$2.value!,
      );
      overall = (valueSum / weightSum).round().clamp(0, 100);
    }

    final provisional = !regularity.isAvailable;

    return SleepScore(
      duration: duration,
      regularity: regularity,
      depth: depth,
      efficiency: efficiency,
      overall: overall,
      provisional: provisional,
      explanation: provisional
          ? 'Предварительная оценка по доступной продолжительности и режиму. '
                'Она не является медицинским показателем сна.'
          : 'Оценка режима по продолжительности и регулярности. '
                'Глубина и эффективность пока недоступны.',
    );
  }

  SleepScoreComponent _durationComponent(Duration duration) {
    final hours = duration.inMinutes / 60.0;
    final double value;

    if (hours < 7) {
      value = (hours / 7 * 100).clamp(0, 100);
    } else if (hours <= 9) {
      value = 100;
    } else {
      value = (100 - (hours - 9) * 15).clamp(0, 100);
    }

    return SleepScoreComponent(
      dimension: SleepScoreDimension.duration,
      value: value,
      explanation: '${_formatHours(hours)} из ориентира 7-9 часов.',
    );
  }

  SleepScoreComponent _regularityComponent(
    SleepSession session,
    List<SleepSession> history,
  ) {
    final relevant = history
        .where(
          (item) => item.isComplete && !item.endedAt.isAfter(session.endedAt),
        )
        .toList()
      ..sort((left, right) => right.endedAt.compareTo(left.endedAt));

    final nights = relevant.take(7).toList();
    if (nights.length < 3) {
      return const SleepScoreComponent.unavailable(
        SleepScoreDimension.regularity,
        'Нужно минимум 3 завершённые ночи для сравнения времени сна.',
      );
    }

    final startDeviation = _meanCircularDeviation(
      nights.map((item) => _minuteOfDay(item.startedAt)).toList(),
    );
    final endDeviation = _meanCircularDeviation(
      nights.map((item) => _minuteOfDay(item.endedAt)).toList(),
    );
    final deviation = (startDeviation + endDeviation) / 2;
    final value = (100 - deviation / 90 * 100).clamp(0, 100).toDouble();

    return SleepScoreComponent(
      dimension: SleepScoreDimension.regularity,
      value: value,
      explanation:
          'Среднее отклонение времени сна ${deviation.round()} мин '
          'по ${nights.length} ночам.',
    );
  }

  int _minuteOfDay(DateTime time) => time.hour * 60 + time.minute;

  double _meanCircularDeviation(List<int> minutes) {
    if (minutes.isEmpty) return 0;

    var sinSum = 0.0;
    var cosSum = 0.0;
    for (final minute in minutes) {
      final angle = minute / 1440 * 2 * math.pi;
      sinSum += math.sin(angle);
      cosSum += math.cos(angle);
    }

    final meanAngle = math.atan2(
      sinSum / minutes.length,
      cosSum / minutes.length,
    );
    final meanMinute = (meanAngle / (2 * math.pi) * 1440 + 1440) % 1440;

    var deviationSum = 0.0;
    for (final minute in minutes) {
      var difference = minute - meanMinute;
      while (difference > 720) {
        difference -= 1440;
      }
      while (difference < -720) {
        difference += 1440;
      }
      deviationSum += difference.abs();
    }

    return deviationSum / minutes.length;
  }

  String _formatHours(double hours) {
    final totalMinutes = (hours * 60).round();
    final wholeHours = totalMinutes ~/ 60;
    final minutes = totalMinutes % 60;
    if (minutes == 0) return '$wholeHours ч';
    return '$wholeHours ч $minutes мин';
  }
}
