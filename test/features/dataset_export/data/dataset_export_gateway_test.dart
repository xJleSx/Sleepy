import 'package:flutter_test/flutter_test.dart';
import 'package:sleepy/features/dataset_export/data/dataset_export_gateway.dart';

void main() {
  test('parses successful export outcome', () {
    final outcome = DatasetExportOutcome.fromMap({
      'ok': true,
      'packageRootPath': '/tmp/package',
      'manifestPath': '/tmp/package/manifest.json',
      'recordingCount': 2,
      'warnings': ['privacy review'],
      'reusedExisting': false,
    });

    expect(outcome.ok, isTrue);
    expect(outcome.packageRootPath, '/tmp/package');
    expect(outcome.manifestPath, '/tmp/package/manifest.json');
    expect(outcome.recordingCount, 2);
    expect(outcome.warnings, ['privacy review']);
    expect(outcome.reusedExisting, isFalse);
  });

  test('parses failed export outcome', () {
    final outcome = DatasetExportOutcome.fromMap({
      'ok': false,
      'code': 'MISSING_CONTENT_BOUNDARIES',
      'message': 'no boundaries',
      'details': ['event_1.wav'],
    });

    expect(outcome.ok, isFalse);
    expect(outcome.code, 'MISSING_CONTENT_BOUNDARIES');
    expect(outcome.message, 'no boundaries');
    expect(outcome.details, ['event_1.wav']);
  });

  test('parses deletion outcome', () {
    final outcome = DatasetDeletionOutcome.fromMap({
      'ok': true,
      'deletedFiles': ['participants/ptc_demo.json'],
      'remainingFiles': 1,
      'integrityOk': true,
      'warnings': [],
    });

    expect(outcome.ok, isTrue);
    expect(outcome.deletedFiles, ['participants/ptc_demo.json']);
    expect(outcome.remainingFiles, 1);
    expect(outcome.integrityOk, isTrue);
  });

  test('parses missing map as failure', () {
    final export = DatasetExportOutcome.fromMap(null);
    final deletion = DatasetDeletionOutcome.fromMap(null);

    expect(export.ok, isFalse);
    expect(export.recordingCount, 0);
    expect(deletion.ok, isFalse);
    expect(deletion.integrityOk, isFalse);
  });
}
