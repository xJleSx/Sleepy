import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../../../app/app_info.dart';
import '../../../app/theme/app_theme.dart';
import '../../events/data/recording_gateway.dart';
import '../../events/domain/recording_status.dart';
import '../../events/presentation/event_formatting.dart';
import '../domain/diagnostic_audio_file.dart';
import '../domain/diagnostic_report.dart';
import 'diagnostic_report_formatter.dart';
import 'diagnostics_formatting.dart';

/// Отдельный экран диагностики аудиоконвейера.
class DiagnosticsPage extends StatefulWidget {
  const DiagnosticsPage({
    super.key,
    this.gateway,
    this.statusRefreshInterval = const Duration(seconds: 3),
    this.fileRefreshInterval = const Duration(seconds: 15),
    this.appVersionValue = appVersion,
    this.copyText,
    this.now,
  });

  final RecordingGateway? gateway;
  final Duration statusRefreshInterval;
  final Duration fileRefreshInterval;
  final String appVersionValue;

  /// Подмена буфера обмена в тестах. По умолчанию используется Clipboard.
  final Future<void> Function(String text)? copyText;

  /// Подмена текущего времени в тестах.
  final DateTime Function()? now;

  @override
  State<DiagnosticsPage> createState() => _DiagnosticsPageState();
}

class _DiagnosticsPageState extends State<DiagnosticsPage> {
  late final RecordingGateway _gateway;

  Timer? _statusTimer;
  Timer? _clockTimer;

  RecordingStatus? _status;
  List<DiagnosticAudioFile> _files = const <DiagnosticAudioFile>[];

  DateTime? _lastUpdatedAt;
  DateTime? _lastFileRefreshAt;
  DateTime _now = DateTime.now();

  bool _initialLoading = true;
  bool _refreshing = false;
  bool _busy = false;
  bool _copying = false;

  String? _loadError;

  @override
  void initState() {
    super.initState();

    _gateway = widget.gateway ?? ChannelRecordingGateway();
    _now = _currentTime();

    _refreshAll(initial: true);

    _statusTimer = Timer.periodic(
      widget.statusRefreshInterval,
      (_) => _refreshStatus(),
    );
    _clockTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted || _status?.isRecording != true) return;

      setState(() {
        _now = _currentTime();
      });
    });
  }

  @override
  void dispose() {
    _statusTimer?.cancel();
    _clockTimer?.cancel();
    super.dispose();
  }

  DateTime _currentTime() => widget.now?.call() ?? DateTime.now();

  Future<void> _refreshAll({bool initial = false}) async {
    if (_busy) return;

    _busy = true;

    if (!initial) {
      setState(() {
        _refreshing = true;
      });
    }

    try {
      final status = await _gateway.fetchStatus();
      final now = _currentTime();

      if (!mounted) return;

      setState(() {
        _status = status;
        _lastUpdatedAt = now;
        _now = now;
        _loadError = null;
      });

      await _reloadFiles(now);
    } on RecordingGatewayException catch (exception) {
      if (!mounted) return;

      setState(() {
        _loadError = exception.message;
      });
    } finally {
      _busy = false;

      if (mounted) {
        setState(() {
          _refreshing = false;
          _initialLoading = false;
        });
      }
    }
  }

  Future<void> _refreshStatus() async {
    if (_busy) return;

    _busy = true;

    try {
      final previous = _status;
      final status = await _gateway.fetchStatus();
      final now = _currentTime();

      if (!mounted) return;

      final fragmentCountChanged =
          previous != null && previous.fragmentCount != status.fragmentCount;
      final filesStale =
          _lastFileRefreshAt == null ||
          now.difference(_lastFileRefreshAt!) >= widget.fileRefreshInterval;

      setState(() {
        _status = status;
        _lastUpdatedAt = now;
        _now = now;
        _loadError = null;
      });

      if (fragmentCountChanged || filesStale) {
        await _reloadFiles(now);
      }
    } on RecordingGatewayException catch (exception) {
      if (!mounted) return;

      setState(() {
        _loadError = exception.message;
      });
    } finally {
      _busy = false;
    }
  }

  Future<void> _reloadFiles(DateTime now) async {
    try {
      final files = await _gateway.fetchDiagnosticFiles();

      if (!mounted) return;

      setState(() {
        _files = files;
        _lastFileRefreshAt = now;
        _loadError = null;
      });
    } on RecordingGatewayException catch (exception) {
      if (!mounted) return;

      setState(() {
        _loadError = exception.message;
      });
    }
  }

  Future<void> _copyReport() async {
    final status = _status;
    if (status == null || _copying) return;

    final generatedAt = _currentTime();
    final report = DiagnosticReport(
      appVersion: widget.appVersionValue,
      generatedAt: generatedAt,
      lastUpdatedAt: _lastUpdatedAt,
      status: status,
      files: _files,
    );
    final text = buildDiagnosticReport(report);

    setState(() {
      _copying = true;
    });

    try {
      final copy = widget.copyText ?? _writeClipboard;
      await copy(text);

      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          behavior: SnackBarBehavior.floating,
          backgroundColor: surface,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
          content: const Text(
            'Отчёт скопирован',
            style: TextStyle(color: Colors.white),
          ),
        ),
      );
    } catch (exception) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          behavior: SnackBarBehavior.floating,
          backgroundColor: Colors.red.shade800,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
          content: Text(
            'Не удалось скопировать отчёт: $exception',
            style: const TextStyle(color: Colors.white),
          ),
        ),
      );
    } finally {
      if (mounted) {
        setState(() {
          _copying = false;
        });
      }
    }
  }

  Future<void> _writeClipboard(String text) {
    return Clipboard.setData(ClipboardData(text: text));
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;

    return Scaffold(
      backgroundColor: background,
      appBar: AppBar(
        backgroundColor: background,
        surfaceTintColor: Colors.transparent,
        title: const Text('Диагностика'),
        actions: [
          IconButton(
            onPressed: _refreshing ? null : _refreshAll,
            tooltip: 'Обновить диагностику',
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SafeArea(
        child: RefreshIndicator(
          color: accent,
          backgroundColor: surface,
          onRefresh: _refreshAll,
          child: ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
            children: [
              _header(),
              const SizedBox(height: 20),
              if (_initialLoading)
                _loadingCard()
              else if (status == null)
                _missingDiagnosticsCard()
              else ...[
                if (_loadError != null) ...[
                  _errorBanner(_loadError!),
                  const SizedBox(height: 16),
                ],
                _sessionCard(status),
                const SizedBox(height: 16),
                _captureCard(status),
                const SizedBox(height: 16),
                _detectorCard(status),
                const SizedBox(height: 16),
                _writerCard(status),
                const SizedBox(height: 16),
                _statsCard(status),
                const SizedBox(height: 24),
                _sectionLabel('ПРОВЕРКА WAV'),
                _filesSection(),
                const SizedBox(height: 24),
                _copyButton(),
                const SizedBox(height: 12),
                const Text(
                  'Отчёт содержит только показатели диагностики и имена файлов. '
                  'Аудиоданные и абсолютные пути в него не включаются.',
                  style: TextStyle(color: Colors.white38, fontSize: 12),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _header() {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Аудиоконвейер',
                style: TextStyle(fontSize: 26, fontWeight: FontWeight.w700),
              ),
              SizedBox(height: 4),
              Text(
                'AudioRecord → Detector → Recorder → Writer → WAV → Catalog',
                style: TextStyle(color: Colors.white54, fontSize: 13),
              ),
            ],
          ),
        ),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          decoration: BoxDecoration(
            color: surface,
            borderRadius: BorderRadius.circular(999),
          ),
          child: Text(
            'v${widget.appVersionValue}',
            style: const TextStyle(color: Colors.white38, fontSize: 11),
          ),
        ),
      ],
    );
  }

  Widget _sessionCard(RecordingStatus status) {
    final color = _stateColor(status);

    return _card(
      borderColor: color.withValues(alpha: 0.35),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                status.isRecording
                    ? Icons.radio_button_checked
                    : status.hasError
                    ? Icons.error_outline
                    : status.interrupted
                    ? Icons.warning_amber_rounded
                    : Icons.mic_none,
                color: color,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  formatSessionState(status),
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Text(
            formatSessionDuration(status, _now),
            style: TextStyle(
              fontSize: 30,
              fontWeight: FontWeight.w700,
              color: status.isRecording ? accent : Colors.white70,
            ),
          ),
          const SizedBox(height: 4),
          const Text(
            'Захват, детектор и writer ниже — за эту же сессию',
            style: TextStyle(color: Colors.white54, fontSize: 12),
          ),
          const SizedBox(height: 14),
          _metricRow(
            'Диагностика обновлена',
            _lastUpdatedAt == null
                ? missingValue
                : formatClock(_lastUpdatedAt!),
          ),
          if (status.startedAt != null)
            _metricRow('Начало сессии', formatDateTime(status.startedAt!)),
          if (!status.isRecording && status.endedAt != null)
            _metricRow('Конец сессии', formatDateTime(status.endedAt!)),
          if (status.hasError) ...[
            const SizedBox(height: 10),
            _notice(Icons.error_outline, Colors.redAccent, status.error),
          ],
          if (status.interrupted) ...[
            const SizedBox(height: 10),
            _notice(
              Icons.warning_amber_rounded,
              Colors.amber,
              'Сессия была прервана',
            ),
          ],
          if (status.hasWarning) ...[
            const SizedBox(height: 10),
            _notice(
              Icons.warning_amber_rounded,
              Colors.amber,
              status.lastWarning,
            ),
          ],
        ],
      ),
    );
  }

  Widget _captureCard(RecordingStatus status) {
    return _card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _sectionTitle('ЗАХВАТ АУДИО · СЕССИЯ'),
          _metricRow(
            'Обработано сэмплов',
            formatOptionalCount(status.samplesRead),
          ),
          _metricRow(
            'Ошибок чтения',
            formatOptionalCount(status.readErrorCount),
          ),
          _metricRow('Последний блок', formatDbfs(status.lastChunkDbfs)),
          const SizedBox(height: 4),
          const Text(
            '«Нет сигнала» для −120 dBFS означает нижнюю границу детектора, '
            'а не обычную тишину.',
            style: TextStyle(color: Colors.white38, fontSize: 12, height: 1.4),
          ),
        ],
      ),
    );
  }

  Widget _detectorCard(RecordingStatus status) {
    return _card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _sectionTitle('ДЕТЕКТОР · СЕССИЯ'),
          _metricRow('Профиль', status.diagnosticProfile),
          _metricRow(
            'Состояние',
            formatDetectorState(status.currentDetectorState),
          ),
          _metricRow('Шумовой пол', formatDbfs(status.noiseFloorDbfs)),
          _metricRow('Кандидаты', formatOptionalCount(status.candidateCount)),
          _metricRow(
            'Подтверждено',
            formatOptionalCount(status.confirmedEventCount),
          ),
          _metricRow(
            'Слабые кандидаты',
            formatOptionalCount(status.weakCandidateCount),
          ),
          _metricRow(
            'Слабых сохранено',
            formatOptionalCount(status.weakSavedCount),
          ),
          _metricRow(
            'Слабых отклонено',
            formatOptionalCount(status.weakRejectedCount),
          ),
          if (status.lastWeakRejection.isNotEmpty)
            _metricRow('Причина отказа', status.lastWeakRejection),
        ],
      ),
    );
  }

  Widget _writerCard(RecordingStatus status) {
    final error = status.lastWriterError;

    return _card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _sectionTitle('ЗАПИСЬ ФАЙЛОВ · СЕССИЯ'),
          _metricRow(
            'Открытий writer',
            formatOptionalCount(status.writerOpenCount),
          ),
          _metricRow(
            'Сохранено WAV',
            formatOptionalCount(status.writerSavedCount),
          ),
          _metricRow(
            'Ошибок writer',
            formatOptionalCount(status.writerFailedCount),
          ),
          _metricRow(
            'Последний файл',
            fileNameFromPath(status.lastFragmentPath),
          ),
          if (error.isNotEmpty) ...[
            const SizedBox(height: 10),
            _notice(Icons.error_outline, Colors.redAccent, error),
          ],
        ],
      ),
    );
  }

  Widget _statsCard(RecordingStatus status) {
    final valid = _files.where((file) => file.isPlayable).length;

    return _card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _sectionTitle('СТАТИСТИКА · НАКОПИТЕЛЬНО'),
          _metricRow('Событий всего', status.eventCount.toString()),
          _metricRow('Фрагментов в статусе', status.fragmentCount.toString()),
          _metricRow('Доступно WAV', valid.toString()),
          _metricRow('WAV в каталоге', _files.length.toString()),
        ],
      ),
    );
  }

  Widget _filesSection() {
    if (_files.isEmpty) {
      return _card(
        child: Column(
          children: const [
            Icon(Icons.folder_open, color: Colors.white38, size: 38),
            SizedBox(height: 12),
            Text(
              'Файлов пока нет',
              style: TextStyle(fontWeight: FontWeight.w600),
            ),
            SizedBox(height: 6),
            Text(
              'После сохранения событий здесь появятся результаты проверки WAV.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.white54, height: 1.4),
            ),
          ],
        ),
      );
    }

    final valid = _files.where((file) => file.status == WavFileStatus.valid);
    final empty = _files.where((file) => file.status == WavFileStatus.empty);
    final corrupted = _files.where(
      (file) => file.status == WavFileStatus.corrupted,
    );
    final failed = _files.where((file) => file.status == WavFileStatus.failed);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _card(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _metricRow('Корректные', valid.length.toString()),
              _metricRow('Пустые', empty.length.toString()),
              _metricRow('Повреждённые', corrupted.length.toString()),
              _metricRow('Не прошли проверку', failed.length.toString()),
            ],
          ),
        ),
        const SizedBox(height: 16),
        if (valid.isNotEmpty) _fileGroup('КОРРЕКТНЫЕ', valid.toList()),
        if (empty.isNotEmpty) _fileGroup('ПУСТЫЕ', empty.toList()),
        if (corrupted.isNotEmpty)
          _fileGroup('ПОВРЕЖДЁННЫЕ', corrupted.toList()),
        if (failed.isNotEmpty)
          _fileGroup('НЕ ПРОШЛИ ПРОВЕРКУ', failed.toList()),
      ],
    );
  }

  Widget _fileGroup(String title, List<DiagnosticAudioFile> files) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _sectionLabel('$title · ${files.length}'),
          for (final file in files) _fileCard(file),
        ],
      ),
    );
  }

  Widget _fileCard(DiagnosticAudioFile file) {
    final color = _fileStatusColor(file.status);

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: surface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: color.withValues(alpha: 0.3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Text(
                  file.fileName,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ),
              const SizedBox(width: 8),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: color.withValues(alpha: 0.14),
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Text(
                  formatWavStatus(file.status),
                  style: TextStyle(color: color, fontSize: 11),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            '${formatFileDate(file.startedAt)} · '
            '${formatFileDuration(file.duration)} · '
            '${formatFileSize(file.sizeBytes)}',
            style: const TextStyle(color: Colors.white54, fontSize: 13),
          ),
          if (file.reason.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(file.reason, style: TextStyle(color: color, fontSize: 12)),
          ],
        ],
      ),
    );
  }

  Widget _copyButton() {
    return FilledButton.icon(
      onPressed: _status == null || _copying ? null : _copyReport,
      style: FilledButton.styleFrom(
        backgroundColor: accent,
        foregroundColor: background,
        padding: const EdgeInsets.symmetric(vertical: 16),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      ),
      icon: _copying
          ? const SizedBox(
              width: 18,
              height: 18,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : const Icon(Icons.copy_all_outlined),
      label: const Text('Скопировать отчёт'),
    );
  }

  Widget _loadingCard() {
    return _card(
      child: const Padding(
        padding: EdgeInsets.symmetric(vertical: 28),
        child: Center(
          child: Column(
            children: [
              CircularProgressIndicator(color: accent),
              SizedBox(height: 16),
              Text(
                'Читаем диагностику…',
                style: TextStyle(color: Colors.white54),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _missingDiagnosticsCard() {
    return _card(
      borderColor: (_loadError == null ? accent : Colors.redAccent).withValues(
        alpha: 0.35,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            _loadError == null ? Icons.query_stats : Icons.error_outline,
            color: _loadError == null ? accent : Colors.redAccent,
          ),
          const SizedBox(height: 12),
          const Text(
            'Диагностика ещё не получена',
            style: TextStyle(fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 6),
          Text(
            _loadError ?? 'Запустите мониторинг или повторите обновление.',
            style: const TextStyle(color: Colors.white60),
          ),
          const SizedBox(height: 12),
          FilledButton(onPressed: _refreshAll, child: const Text('Повторить')),
        ],
      ),
    );
  }

  Widget _errorBanner(String message) {
    return _card(
      borderColor: Colors.redAccent.withValues(alpha: 0.35),
      child: _notice(Icons.error_outline, Colors.redAccent, message),
    );
  }

  Widget _card({required Widget child, Color? borderColor}) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: surface,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(
          color: borderColor ?? Colors.white.withValues(alpha: 0.06),
        ),
      ),
      child: child,
    );
  }

  Widget _sectionLabel(String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Text(
        text,
        style: const TextStyle(
          color: Colors.white54,
          letterSpacing: 1.6,
          fontSize: 12,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }

  Widget _sectionTitle(String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Text(
        text,
        style: const TextStyle(
          color: Colors.white54,
          letterSpacing: 1.4,
          fontSize: 12,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }

  Widget _metricRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Text(
              label,
              style: const TextStyle(color: Colors.white60, fontSize: 13),
            ),
          ),
          const SizedBox(width: 12),
          Flexible(
            child: Text(
              value,
              textAlign: TextAlign.right,
              style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }

  Widget _notice(IconData icon, Color color, String text) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, color: color, size: 18),
        const SizedBox(width: 8),
        Expanded(
          child: Text(text, style: TextStyle(color: color, fontSize: 13)),
        ),
      ],
    );
  }

  Color _stateColor(RecordingStatus status) {
    if (status.isRecording) return Colors.greenAccent;
    if (status.hasError) return Colors.redAccent;
    if (status.interrupted) return Colors.amber;
    return Colors.white54;
  }

  Color _fileStatusColor(WavFileStatus status) {
    switch (status) {
      case WavFileStatus.valid:
        return Colors.greenAccent;
      case WavFileStatus.empty:
        return Colors.amber;
      case WavFileStatus.corrupted:
        return Colors.redAccent;
      case WavFileStatus.failed:
        return Colors.orangeAccent;
    }
  }
}
