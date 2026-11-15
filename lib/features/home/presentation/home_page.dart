import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

import '../../../app/theme/app_theme.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  static const MethodChannel _recordingChannel =
      MethodChannel('com.sleepy.app.sleepy/recording');

  Timer? _timer;

  bool _isRecording = false;
  bool _isStarting = false;

  Duration _elapsed = Duration.zero;

  String? _filePath;
  String? _error;

  @override
  void initState() {
    super.initState();

    _refreshStatus();

    _timer = Timer.periodic(
      const Duration(seconds: 1),
      (_) => _refreshStatus(),
    );
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _refreshStatus() async {
    try {
      final status = await _recordingChannel.invokeMapMethod<String, dynamic>(
        'getRecordingStatus',
      );

      if (!mounted || status == null) return;

      final isRecording = status['isRecording'] == true;
      final startedAtMillis =
          (status['startedAtMillis'] as num?)?.toInt() ?? 0;

      final startedAt = startedAtMillis > 0
          ? DateTime.fromMillisecondsSinceEpoch(startedAtMillis)
          : null;

      final filePath = status['filePath'] as String?;
      final error = status['error'] as String?;

      setState(() {
        _isRecording = isRecording;
        _filePath = filePath;
        _error = error?.isNotEmpty == true ? error : null;

        _elapsed = isRecording && startedAt != null
            ? DateTime.now().difference(startedAt)
            : Duration.zero;
      });
    } on PlatformException catch (exception) {
      if (!mounted) return;

      setState(() {
        _error = exception.message ?? 'Не удалось получить состояние записи';
      });
    }
  }

  Future<void> _toggleMonitoring() async {
    if (_isStarting) return;

    if (_isRecording) {
      await _stopMonitoring();
    } else {
      await _startMonitoring();
    }
  }

  Future<void> _startMonitoring() async {
    setState(() {
      _isStarting = true;
      _error = null;
    });

    try {
      final permissionGranted =
          await _recordingChannel.invokeMethod<bool>(
                'requestMicrophonePermission',
              ) ??
              false;

      if (!permissionGranted) {
        throw Exception(
          'Для мониторинга необходимо разрешение на микрофон',
        );
      }

      await _recordingChannel.invokeMethod<bool>(
        'startRecording',
      );

      // Ждём, пока Android действительно начнёт запись.
      for (var i = 0; i < 10; i++) {
        await Future.delayed(const Duration(milliseconds: 500));

        await _refreshStatus();

        if (_isRecording) {
          break;
        }
      }

      if (!_isRecording) {
        throw Exception(
          'Служба не начала запись. Проверь разрешение на микрофон.',
        );
      }
    } on PlatformException catch (exception) {
      _showError(exception.message ?? 'Ошибка запуска мониторинга');
    } catch (exception) {
      _showError(exception.toString().replaceFirst('Exception: ', ''));
    } finally {
      if (mounted) {
        setState(() {
          _isStarting = false;
        });
      }
    }
  }

  Future<void> _stopMonitoring() async {
    try {
      await _recordingChannel.invokeMethod<bool>(
        'stopRecording',
      );

      await _refreshStatus();
    } on PlatformException catch (exception) {
      _showError(exception.message ?? 'Не удалось остановить запись');
    }
  }

  void _showError(String message) {
    if (!mounted) return;

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: Colors.red.shade800,
      ),
    );
  }

  String _formatDuration(Duration duration) {
    final hours = duration.inHours.toString().padLeft(2, '0');
    final minutes = duration.inMinutes.remainder(60).toString().padLeft(2, '0');
    final seconds = duration.inSeconds.remainder(60).toString().padLeft(2, '0');

    return '$hours:$minutes:$seconds';
  }

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Row(
          children: [
            const Icon(Icons.nights_stay, color: accent, size: 32),
            const SizedBox(width: 10),
            const Text(
              'Sleepy',
              style: TextStyle(
                fontSize: 27,
                fontWeight: FontWeight.w700,
              ),
            ),
            const Spacer(),
            const Icon(Icons.notifications_none),
          ],
        ),
        const SizedBox(height: 32),
        const Text(
          'Доброй ночи',
          style: TextStyle(
            fontSize: 27,
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: 8),
        const Text(
          'Готовы отдохнуть?',
          style: TextStyle(color: Colors.white60),
        ),
        const SizedBox(height: 28),

        // Результат последней ночи.
        Container(
          padding: const EdgeInsets.all(24),
          decoration: BoxDecoration(
            color: surface,
            borderRadius: BorderRadius.circular(28),
          ),
          child: Column(
            children: [
              const Text(
                'ПОСЛЕДНЯЯ НОЧЬ',
                style: TextStyle(
                  color: Colors.white54,
                  letterSpacing: 2,
                  fontSize: 12,
                ),
              ),
              const SizedBox(height: 24),
              Container(
                width: 180,
                height: 180,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  border: Border.all(
                    color: accent.withValues(alpha: 0.25),
                    width: 12,
                  ),
                ),
                child: const Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        '--',
                        style: TextStyle(
                          fontSize: 58,
                          fontWeight: FontWeight.w700,
                          color: accent,
                        ),
                      ),
                      Text(
                        'из 100',
                        style: TextStyle(color: Colors.white54),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 24),
              const Text(
                'Оценка появится после сна',
                style: TextStyle(color: Colors.white70),
              ),
              const SizedBox(height: 8),
              const Text(
                'Данные пока отсутствуют',
                style: TextStyle(color: Colors.white38),
              ),
            ],
          ),
        ),

        const SizedBox(height: 24),

        // Показатели мониторинга.
        Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: surface,
            borderRadius: BorderRadius.circular(24),
          ),
          child: const Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'ТВОЙ СОН',
                style: TextStyle(
                  color: Colors.white54,
                  letterSpacing: 1.5,
                  fontSize: 12,
                ),
              ),
              SizedBox(height: 20),
              MetricRow(
                icon: Icons.bedtime_outlined,
                title: 'Продолжительность',
                value: '--',
              ),
              MetricRow(
                icon: Icons.schedule,
                title: 'Засыпание',
                value: '--:--',
              ),
              MetricRow(
                icon: Icons.wb_sunny_outlined,
                title: 'Пробуждение',
                value: '--:--',
              ),
            ],
          ),
        ),

        const SizedBox(height: 24),

        // Состояние записи.
        Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: surface,
            borderRadius: BorderRadius.circular(24),
            border: Border.all(
              color: _isRecording
                  ? Colors.green.withValues(alpha: 0.4)
                  : accent.withValues(alpha: 0.12),
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    _isRecording
                        ? Icons.radio_button_checked
                        : Icons.mic_none,
                    color: _isRecording ? Colors.greenAccent : accent,
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      _isRecording
                          ? 'Мониторинг активен'
                          : 'Мониторинг не запущен',
                      style: const TextStyle(
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ],
              ),
              if (_isRecording) ...[
                const SizedBox(height: 16),
                Text(
                  _formatDuration(_elapsed),
                  style: const TextStyle(
                    fontSize: 30,
                    fontWeight: FontWeight.w700,
                    color: accent,
                  ),
                ),
                const SizedBox(height: 4),
                const Text(
                  'Время записи',
                  style: TextStyle(color: Colors.white54),
                ),
              ],
              if (_error != null) ...[
                const SizedBox(height: 12),
                Text(
                  _error!,
                  style: const TextStyle(color: Colors.redAccent),
                ),
              ],
              if (!_isRecording && _filePath != null && _filePath!.isNotEmpty) ...[
                const SizedBox(height: 12),
                const Text(
                  'Последний файл записи:',
                  style: TextStyle(color: Colors.white54),
                ),
                const SizedBox(height: 4),
                Text(
                  _filePath!.split(RegExp(r'[/\\]')).last,
                  style: const TextStyle(color: Colors.white70),
                ),
              ],
            ],
          ),
        ),

        const SizedBox(height: 24),

        FilledButton.icon(
          onPressed: _isStarting ? null : _toggleMonitoring,
          style: FilledButton.styleFrom(
            backgroundColor: _isRecording ? Colors.red.shade700 : accent,
            foregroundColor: Colors.white,
            padding: const EdgeInsets.symmetric(vertical: 20),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(20),
            ),
          ),
          icon: _isStarting
              ? const SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Colors.white,
                  ),
                )
              : Icon(
                  _isRecording
                      ? Icons.stop_circle_outlined
                      : Icons.nights_stay,
                ),
          label: Text(
            _isStarting
                ? 'Запускаем мониторинг...'
                : _isRecording
                    ? 'Остановить мониторинг'
                    : 'Начать мониторинг сна',
            style: const TextStyle(fontSize: 16),
          ),
        ),

        const SizedBox(height: 16),

        const Center(
          child: Text(
            'Запись звука сохраняется локально на устройстве',
            textAlign: TextAlign.center,
            style: TextStyle(
              color: Colors.white38,
              fontSize: 12,
            ),
          ),
        ),

        const SizedBox(height: 16),

        const Center(
          child: Text(
            'Sleepy · Your sleep companion',
            style: TextStyle(color: Colors.white24),
          ),
        ),
      ],
    );
  }
}

class MetricRow extends StatelessWidget {
  final IconData icon;
  final String title;
  final String value;

  const MetricRow({
    super.key,
    required this.icon,
    required this.title,
    required this.value,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 18),
      child: Row(
        children: [
          Icon(icon, color: secondary),
          const SizedBox(width: 12),
          Expanded(child: Text(title)),
          Text(
            value,
            style: const TextStyle(color: Colors.white54),
          ),
        ],
      ),
    );
  }
}