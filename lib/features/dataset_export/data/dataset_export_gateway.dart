import 'package:flutter/services.dart';

class DatasetExportGatewayException implements Exception {
  const DatasetExportGatewayException(this.message);

  final String message;

  @override
  String toString() => message;
}

class DatasetExportOutcome {
  const DatasetExportOutcome({
    required this.ok,
    this.packageRootPath,
    this.manifestPath,
    this.recordingCount = 0,
    this.warnings = const [],
    this.reusedExisting = false,
    this.code,
    this.message,
    this.details = const [],
  });

  final bool ok;
  final String? packageRootPath;
  final String? manifestPath;
  final int recordingCount;
  final List<String> warnings;
  final bool reusedExisting;
  final String? code;
  final String? message;
  final List<String> details;

  factory DatasetExportOutcome.fromMap(Map<String, dynamic>? map) {
    final source = map ?? const <String, dynamic>{};
    return DatasetExportOutcome(
      ok: source['ok'] == true,
      packageRootPath: source['packageRootPath'] as String?,
      manifestPath: source['manifestPath'] as String?,
      recordingCount: _asInt(source['recordingCount']),
      warnings: _stringList(source['warnings']),
      reusedExisting: source['reusedExisting'] == true,
      code: source['code'] as String?,
      message: source['message'] as String?,
      details: _stringList(source['details']),
    );
  }
}

class DatasetDeletionOutcome {
  const DatasetDeletionOutcome({
    required this.ok,
    this.deletedFiles = const [],
    this.remainingFiles = 0,
    this.integrityOk = false,
    this.warnings = const [],
    this.code,
    this.message,
    this.details = const [],
  });

  final bool ok;
  final List<String> deletedFiles;
  final int remainingFiles;
  final bool integrityOk;
  final List<String> warnings;
  final String? code;
  final String? message;
  final List<String> details;

  factory DatasetDeletionOutcome.fromMap(Map<String, dynamic>? map) {
    final source = map ?? const <String, dynamic>{};
    return DatasetDeletionOutcome(
      ok: source['ok'] == true,
      deletedFiles: _stringList(source['deletedFiles']),
      remainingFiles: _asInt(source['remainingFiles']),
      integrityOk: source['integrityOk'] == true,
      warnings: _stringList(source['warnings']),
      code: source['code'] as String?,
      message: source['message'] as String?,
      details: _stringList(source['details']),
    );
  }
}

class DatasetExportGateway {
  DatasetExportGateway({MethodChannel? channel})
    : _channel =
          channel ?? const MethodChannel('com.sleepy.app.sleepy/recording');

  final MethodChannel _channel;

  Future<DatasetExportOutcome> export(Map<String, Object?> request) async {
    try {
      final raw = await _channel.invokeMapMethod<String, dynamic>(
        'exportDataset',
        request,
      );
      return DatasetExportOutcome.fromMap(raw);
    } on MissingPluginException {
      throw const DatasetExportGatewayException('Канал экспорта недоступен');
    } on PlatformException catch (exception) {
      throw DatasetExportGatewayException(
        exception.message ?? 'Не удалось экспортировать датасет',
      );
    }
  }

  Future<DatasetDeletionOutcome> deleteParticipant({
    required String packageRootPath,
    required String participantId,
  }) async {
    try {
      final raw = await _channel.invokeMapMethod<String, dynamic>(
        'deleteDatasetParticipant',
        <String, Object?>{
          'packageRootPath': packageRootPath,
          'participantId': participantId,
        },
      );
      return DatasetDeletionOutcome.fromMap(raw);
    } on MissingPluginException {
      throw const DatasetExportGatewayException('Канал удаления недоступен');
    } on PlatformException catch (exception) {
      throw DatasetExportGatewayException(
        exception.message ?? 'Не удалось удалить участника',
      );
    }
  }
}

int _asInt(Object? value) => value is num ? value.toInt() : 0;

List<String> _stringList(Object? value) =>
    value is List ? value.whereType<String>().toList() : const [];
