# Отчёт проверок экспорта 0.1

Дата: 2026-11-19.

## Kotlin

```powershell
cd C:\Users\JleS\sleepy\android
.\gradlew.bat testDebugUnitTest --tests "com.sleepy.app.sleepy.dataset.*" --console=plain --no-daemon
```

Результат: 15/15 тестов прошли.

Покрыто:

- создание пакета и manifest;
- повторный экспорт и переиспользование;
- стабильность `recording_id`;
- отсутствие content-границ;
- несовпадение сэмплов и повреждённый WAV;
- consent, scope, guardian consent;
- файл вне каталога записей;
- дубликат записи;
- удаление участника и сохранение исходников;
- отказ при повреждённом пакете;
- удаление отсутствующего участника;
- JSON parser.

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.sleepy.app.sleepy.audio.*" --console=plain --no-daemon
```

Результат: все audio-тесты прошли, включая `AudioLongRunTest`.

Полный прогон `testDebugUnitTest`: 166/166 тестов прошли, включая `AudioFragmentMetadataTest`, sidecar-тесты writer/catalog и `AudioLongRunTest`. Ранее в combined-прогоне `AudioLongRunTest.recorderSavesEveryEventAcrossFourHoursAndAllWavsValidate` нестабильно получал 19 и 22 сохранённых фрагмента вместо 24; в последнем прогоне не воспроизвелось. Тест остаётся потенциально нестабильным; аудиоалгоритм не изменялся.

Добавлено покрытие metadata sidecar: точные `start_sample`, `end_sample`, `content_start_sample`, `content_end_sample`, `truncated`, `sample_rate_hz`, соответствие размера WAV, удаление sidecar при abort и очистка устаревшего sidecar при повторном имени.

## Flutter

```powershell
cd C:\Users\JleS\sleepy
flutter analyze
flutter test
```

Результат: `flutter analyze` — без замечаний; `flutter test` — 54/54.

## JSON Schema

Сгенерированный fixture-пакет проверен Python `jsonschema` 4.26.0 против:

- `participant.schema.json`;
- `device.schema.json`;
- `session.schema.json`;
- `recording.schema.json`.

Результат: 4 файла, 0 ошибок.

## APK

```powershell
flutter build apk --debug
```

Результат: `C:\Users\JleS\sleepy\build\app\outputs\flutter-apk\app-debug.apk`, 173195554 байта. `apkanalyzer` подтвердил наличие пакета `com.sleepy.app.sleepy.dataset` в APK.

## Устройство

На `8LR8KN7HEAHUOZEY` (Android 15/API 35) установлен новый debug APK. Streamed install блокировался MIUI, установка выполнена через push install. После установки созданы 2 новых фрагмента; оба получили `.wav.meta` с корректными `samples`, `content_start_sample`, `content_end_sample` и `size_bytes`, каталог показал оба файла.

## Не выполнено

- Runtime-экспорт пакета на устройстве: UI экспорта отсутствует.
- Валидация на реальных Sleepy-записях: у существующих WAV нет точных content-границ.
- Проверка event/annotation/split sidecar: они не входят в экспорт 0.1.
