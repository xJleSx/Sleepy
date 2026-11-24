import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../../../app/theme/app_theme.dart';
import '../data/recording_gateway.dart';
import '../domain/audio_fragment.dart';
import '../domain/recording_status.dart';
import 'event_formatting.dart';
import 'playback_controller.dart';

/// Экран аудиособытий: состояние записи, накопительная статистика,
/// список сохранённых фрагментов и плеер.
class EventsPage extends StatefulWidget {
  const EventsPage({
    super.key,
    this.gateway,
    this.playbackController,
    this.statusRefreshInterval = const Duration(seconds: 3),
  });

  final RecordingGateway? gateway;
  final AudioPlaybackController? playbackController;
  final Duration statusRefreshInterval;

  @override
  State<EventsPage> createState() => _EventsPageState();
}

class _EventsPageState extends State<EventsPage> with WidgetsBindingObserver {
  late final RecordingGateway _gateway;
  late final AudioPlaybackController _player;
  late final bool _ownsPlayer;

  RecordingStatus? _status;
  List<AudioFragment> _fragments = const [];

  bool _initialLoading = true;
  bool _refreshing = false;
  bool _statusBusy = false;
  bool _listBusy = false;
  bool _busy = false;
  String? _deletingId;

  String? _loadError;

  double? _dragPositionMs;
  String? _draggingFragmentId;

  Timer? _statusTimer;
  Timer? _clockTimer;

  DateTime _now = DateTime.now();

  @override
  void initState() {
    super.initState();

    _gateway = widget.gateway ?? ChannelRecordingGateway();
    _ownsPlayer = widget.playbackController == null;
    _player = widget.playbackController ?? ChannelPlaybackController();

    _player.addListener(_onPlayerChanged);
    WidgetsBinding.instance.addObserver(this);

    _refreshAll(initial: true);

    _statusTimer = Timer.periodic(
      widget.statusRefreshInterval,
      (_) => _refreshStatus(),
    );

    _clockTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted || _status?.isRecording != true) return;

      setState(() {
        _now = DateTime.now();
      });
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _statusTimer?.cancel();
    _clockTimer?.cancel();
    _player.removeListener(_onPlayerChanged);

    if (_ownsPlayer) {
      _player.dispose();
    }

    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.inactive ||
        state == AppLifecycleState.hidden) {
      if (_player.snapshot.isPlaying) {
        _player.pause();
      }
      return;
    }

    if (state == AppLifecycleState.detached) {
      _player.stop();
    }
  }

  void _onPlayerChanged() {
    if (!mounted) return;

    setState(() {});
  }

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
      final fragments = await _gateway.fetchFragments();

      if (!mounted) return;

      setState(() {
        _status = status;
        _fragments = fragments;
        _loadError = null;
        _now = DateTime.now();
      });

      await _stopIfMissing(fragments);
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
    if (_busy || _statusBusy) return;

    _statusBusy = true;

    try {
      final status = await _gateway.fetchStatus();

      if (!mounted) return;

      final previous = _status;

      setState(() {
        _status = status;
        _now = DateTime.now();
      });

      final recordingStopped =
          previous != null && previous.isRecording && !status.isRecording;
      final fragmentsChanged =
          previous != null && previous.fragmentCount != status.fragmentCount;

      if (recordingStopped || fragmentsChanged) {
        await _reloadFragments();
      }
    } on RecordingGatewayException {
      // Временный сбой: состояние обновится на следующем тике.
    } finally {
      _statusBusy = false;
    }
  }

  Future<void> _reloadFragments() async {
    if (_listBusy) return;

    _listBusy = true;

    try {
      final fragments = await _gateway.fetchFragments();

      if (!mounted) return;

      setState(() {
        _fragments = fragments;
        _loadError = null;
      });

      await _stopIfMissing(fragments);
    } on RecordingGatewayException {
      // Оставляем прежний список: данные могли просто не прочитаться.
    } finally {
      _listBusy = false;
    }
  }

  Future<void> _stopIfMissing(List<AudioFragment> fragments) async {
    final selectedId = _player.snapshot.fragmentId;
    if (selectedId == null) return;

    final stillExists = fragments.any((fragment) => fragment.id == selectedId);
    if (stillExists) return;

    await _player.stop();
  }

  Future<void> _selectFragment(AudioFragment fragment) async {
    setState(() {
      _dragPositionMs = null;
      _draggingFragmentId = null;
    });

    await _player.select(fragment);
  }

  Future<void> _seek(Duration position) async {
    await _player.seek(position);

    if (!mounted) return;

    setState(() {
      _draggingFragmentId = null;
      _dragPositionMs = null;
    });
  }

  Future<void> _confirmDelete(AudioFragment fragment) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Удалить фрагмент?'),
        content: const Text(
          'WAV-файл будет удалён с устройства. Это действие нельзя отменить.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Отмена'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            style: FilledButton.styleFrom(backgroundColor: Colors.red.shade700),
            child: const Text('Удалить'),
          ),
        ],
      ),
    );

    if (confirmed != true || !mounted) return;

    setState(() {
      _deletingId = fragment.id;
    });

    try {
      if (_player.snapshot.fragmentId == fragment.id) {
        await _player.stop();
      }
      await _gateway.deleteFragment(fragment);

      if (!mounted) return;
      setState(() {
        _fragments = _fragments
            .where((item) => item.id != fragment.id)
            .toList();
      });
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Фрагмент удалён')),
      );
    } on RecordingGatewayException catch (exception) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          backgroundColor: Colors.red.shade800,
          content: Text(exception.message),
        ),
      );
    } finally {
      if (mounted) {
        setState(() {
          _deletingId = null;
        });
      }
    }
  }

  Duration _monitoringElapsed(RecordingStatus status) {
    final startedAt = status.startedAt;
    if (!status.isRecording || startedAt == null) return Duration.zero;

    final elapsed = _now.difference(startedAt);
    return elapsed.isNegative ? Duration.zero : elapsed;
  }

  String _statusTitle(RecordingStatus status) {
    if (status.isRecording) return 'Мониторинг активен';
    if (status.hasError) return 'Ошибка записи';
    if (status.interrupted) return 'Запись прервана';
    return 'Мониторинг остановлен';
  }

  Color _statusColor(RecordingStatus status) {
    if (status.isRecording) return Colors.greenAccent;
    if (status.hasError) return Colors.redAccent;
    if (status.interrupted) return Colors.amber;
    return Colors.white54;
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;

    return RefreshIndicator(
      color: accent,
      backgroundColor: surface,
      onRefresh: _refreshAll,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 32),
        children: [
          _header(),
          const SizedBox(height: 20),
          if (_initialLoading)
            _loadingCard()
          else if (status == null)
            _fullErrorCard()
          else ...[
            _statusCard(status),
            const SizedBox(height: 16),
            _statsCard(status),
            const SizedBox(height: 24),
            _sectionLabel('ФРАГМЕНТЫ'),
            if (_fragments.isEmpty)
              _emptyCard()
            else
              for (final fragment in _fragments) _fragmentCard(fragment),
            const SizedBox(height: 12),
            const Text(
              'Детектор фиксирует акустические события. Отдельный '
              'классификатор типов звуков пока недоступен, поэтому Sleepy '
              'не называет каждое событие храпом.',
              style: TextStyle(color: Colors.white38, fontSize: 12),
            ),
          ],
        ],
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
                'Звуки сна',
                style: TextStyle(
                  fontSize: 26,
                  fontWeight: FontWeight.w700,
                ),
              ),
              SizedBox(height: 4),
              Text(
                'Обнаруженные события и сохранённые WAV-фрагменты',
                style: TextStyle(color: Colors.white54),
              ),
            ],
          ),
        ),
        IconButton(
          onPressed: _refreshing ? null : _refreshAll,
          tooltip: 'Обновить',
          icon: const Icon(Icons.refresh),
        ),
      ],
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

  Widget _loadingCard() {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 48),
      decoration: BoxDecoration(
        color: surface,
        borderRadius: BorderRadius.circular(24),
      ),
      child: const Center(
        child: Column(
          children: [
            CircularProgressIndicator(color: accent),
            SizedBox(height: 16),
            Text(
              'Читаем записи…',
              style: TextStyle(color: Colors.white54),
            ),
          ],
        ),
      ),
    );
  }

  Widget _fullErrorCard() {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: surface,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: Colors.redAccent.withValues(alpha: 0.4)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.error_outline, color: Colors.redAccent),
          const SizedBox(height: 12),
          const Text(
            'Не удалось загрузить данные',
            style: TextStyle(fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 6),
          Text(
            _loadError ?? 'Повторите попытку позже',
            style: const TextStyle(color: Colors.white60),
          ),
          const SizedBox(height: 12),
          FilledButton(
            onPressed: _refreshAll,
            style: FilledButton.styleFrom(backgroundColor: accent),
            child: const Text('Повторить'),
          ),
        ],
      ),
    );
  }

  Widget _statusCard(RecordingStatus status) {
    final color = _statusColor(status);
    final lastActivity = status.lastActivityAt;

    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: surface,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: color.withValues(alpha: 0.35)),
      ),
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
                        : Icons.mic_none,
                color: color,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  _statusTitle(status),
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ),
              if (_refreshing)
                const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
            ],
          ),
          if (status.isRecording) ...[
            const SizedBox(height: 16),
            Text(
              formatElapsed(_monitoringElapsed(status)),
              style: const TextStyle(
                fontSize: 30,
                fontWeight: FontWeight.w700,
                color: accent,
              ),
            ),
            const SizedBox(height: 4),
            const Text(
              'Длительность мониторинга',
              style: TextStyle(color: Colors.white54),
            ),
          ] else if (lastActivity != null) ...[
            const SizedBox(height: 12),
            Text(
              'Последняя запись: ${formatDateTime(lastActivity)}',
              style: const TextStyle(color: Colors.white70),
            ),
          ],
          if (status.hasWarning) ...[
            const SizedBox(height: 12),
            _notice(
              icon: Icons.warning_amber_rounded,
              color: Colors.amber,
              text: status.lastWarning,
            ),
          ],
          if (status.hasError) ...[
            const SizedBox(height: 12),
            _notice(
              icon: Icons.error_outline,
              color: Colors.redAccent,
              text: status.error,
            ),
          ],
          if (_loadError != null) ...[
            const SizedBox(height: 12),
            _notice(
              icon: Icons.cloud_off,
              color: Colors.amber,
              text: 'Не удалось обновить данные: $_loadError',
            ),
          ],
        ],
      ),
    );
  }

  Widget _notice({
    required IconData icon,
    required Color color,
    required String text,
  }) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, color: color, size: 18),
        const SizedBox(width: 8),
        Expanded(
          child: Text(text, style: TextStyle(color: color)),
        ),
      ],
    );
  }

  Widget _statsCard(RecordingStatus status) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: surface,
        borderRadius: BorderRadius.circular(24),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: _statTile(
                  label: 'ФАЙЛЫ НА ДИСКЕ',
                  value: status.fragmentCount.toString(),
                  icon: Icons.graphic_eq,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _statTile(
                  label: 'СОБЫТИЯ ВСЕГО',
                  value: status.eventCount.toString(),
                  icon: Icons.bolt,
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          const Text(
            'Одно событие может состоять из нескольких фрагментов. '
            'Счётчики накопительные: они не сбрасываются между ночами.',
            style: TextStyle(color: Colors.white38, fontSize: 12),
          ),
        ],
      ),
    );
  }

  Widget _statTile({
    required String label,
    required String value,
    required IconData icon,
  }) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(18),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: secondary, size: 20),
          const SizedBox(height: 12),
          Text(
            value,
            style: const TextStyle(
              fontSize: 26,
              fontWeight: FontWeight.w700,
              color: accent,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            label,
            style: const TextStyle(
              color: Colors.white54,
              fontSize: 11,
              letterSpacing: 1.1,
            ),
          ),
        ],
      ),
    );
  }

  Widget _emptyCard() {
    return Container(
      padding: const EdgeInsets.all(28),
      decoration: BoxDecoration(
        color: surface,
        borderRadius: BorderRadius.circular(24),
      ),
      child: Column(
        children: [
          Icon(
            Icons.graphic_eq,
            size: 44,
            color: accent.withValues(alpha: 0.7),
          ),
          const SizedBox(height: 14),
          const Text(
            'Пока нет звуковых событий',
            style: TextStyle(fontWeight: FontWeight.w600, fontSize: 16),
          ),
          const SizedBox(height: 6),
          const Text(
            'Sleepy сохранит фрагмент, когда услышит звук во время '
            'мониторинга. Запустите мониторинг на главном экране.',
            textAlign: TextAlign.center,
            style: TextStyle(color: Colors.white54, height: 1.4),
          ),
        ],
      ),
    );
  }

  Widget _fragmentCard(AudioFragment fragment) {
    final snapshot = _player.snapshot;
    final selected = snapshot.fragmentId == fragment.id;
    final playing = selected && snapshot.isPlaying;

    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      decoration: BoxDecoration(
        color: surface,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: selected
              ? accent.withValues(alpha: 0.5)
              : Colors.white.withValues(alpha: 0.06),
        ),
      ),
      child: Column(
        children: [
          InkWell(
            borderRadius: BorderRadius.circular(20),
            onTap: () => _selectFragment(fragment),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  Container(
                    width: 46,
                    height: 46,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: selected
                          ? accent
                          : accent.withValues(alpha: 0.14),
                    ),
                    child: Icon(
                      playing ? Icons.pause : Icons.play_arrow,
                      color: selected ? background : accent,
                    ),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          formatEventTimestamp(fragment.startedAt),
                          style: const TextStyle(
                            fontWeight: FontWeight.w600,
                            fontSize: 15,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          '${formatDuration(fragment.duration, roundUp: true)}'
                          ' · ${formatBytes(fragment.sizeBytes)}',
                          style: const TextStyle(
                            color: Colors.white54,
                            fontSize: 13,
                          ),
                        ),
                        const SizedBox(height: 6),
                        const Text(
                          'Обнаружено событие · тип не определён',
                          style: TextStyle(
                            color: Colors.white38,
                            fontSize: 11,
                          ),
                        ),
                      ],
                    ),
                  ),
                  if (selected && snapshot.isPreparing)
                    const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  else
                    IconButton(
                      onPressed: _deletingId == fragment.id
                          ? null
                          : () => _confirmDelete(fragment),
                      tooltip: 'Удалить фрагмент',
                      icon: _deletingId == fragment.id
                          ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.delete_outline, size: 20),
                    ),
                ],
              ),
            ),
          ),
          if (selected) _playerPanel(fragment, snapshot),
        ],
      ),
    );
  }

  Widget _playerPanel(AudioFragment fragment, PlaybackSnapshot snapshot) {
    final durationMs = snapshot.duration.inMilliseconds;
    final maxMs = durationMs > 0 ? durationMs.toDouble() : 1.0;

    final dragging = _draggingFragmentId == fragment.id;
    final rawPosition = dragging && _dragPositionMs != null
        ? _dragPositionMs!
        : snapshot.position.inMilliseconds.toDouble();
    final valueMs = rawPosition.clamp(0.0, maxMs);

    final displayPosition = Duration(milliseconds: valueMs.round());

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      child: Column(
        children: [
          SliderTheme(
            data: SliderTheme.of(context).copyWith(
              activeTrackColor: accent,
              thumbColor: accent,
              inactiveTrackColor: accent.withValues(alpha: 0.2),
              overlayColor: accent.withValues(alpha: 0.15),
            ),
            child: Slider(
              value: valueMs,
              max: maxMs,
              onChanged: (value) {
                setState(() {
                  _draggingFragmentId = fragment.id;
                  _dragPositionMs = value;
                });
              },
              onChangeEnd: (value) {
                _seek(Duration(milliseconds: value.round()));
              },
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8),
            child: Row(
              children: [
                Text(
                  formatDuration(displayPosition),
                  style: const TextStyle(color: Colors.white54, fontSize: 12),
                ),
                const Spacer(),
                Text(
                  formatDuration(snapshot.duration),
                  style: const TextStyle(color: Colors.white54, fontSize: 12),
                ),
              ],
            ),
          ),
          const SizedBox(height: 8),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              IconButton(
                onPressed: _player.stop,
                tooltip: 'Остановить',
                icon: const Icon(Icons.stop_rounded),
              ),
              const SizedBox(width: 12),
              Container(
                decoration: const BoxDecoration(
                  color: accent,
                  shape: BoxShape.circle,
                ),
                child: IconButton(
                  onPressed: _player.togglePlayPause,
                  tooltip: snapshot.isPlaying ? 'Пауза' : 'Слушать',
                  icon: Icon(
                    snapshot.isPlaying ? Icons.pause : Icons.play_arrow,
                    color: background,
                  ),
                ),
              ),
            ],
          ),
          if (snapshot.error != null) ...[
            const SizedBox(height: 8),
            _notice(
              icon: Icons.error_outline,
              color: Colors.redAccent,
              text: snapshot.error!,
            ),
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton(
                onPressed: _refreshAll,
                child: const Text('Обновить список'),
              ),
            ),
          ],
        ],
      ),
    );
  }
}
