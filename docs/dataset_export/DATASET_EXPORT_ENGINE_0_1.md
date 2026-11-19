# Dataset Export Engine 0.1

## Назначение

Экспорт формирует локальный пакет `sleepy-sleep-domain-0.1` из существующих Sleepy event-фрагментов. Экспорт не меняет аудиоалгоритм, исходные WAV, MODEL 0.1 и настройки записи.

## Компоненты

- `DatasetExportEngine` — валидация запроса, проверка WAV, копирование, staging, манифест.
- `DatasetPackageDeleter` — удаление участника только из копий внутри пакета.
- `DatasetJson` — внутренний JSON writer и parser без внешних зависимостей.
- `AudioFragmentMetadata` — sidecar `event_*.wav.meta` с точными границами для новых записей.
- `MainActivity` — методы канала `com.sleepy.app.sleepy/recording`:
  - `exportDataset`;
  - `deleteDatasetParticipant`.
- `DatasetExportGateway` — Dart-обёртка над каналом.

## Входные данные

`DatasetExportRequest` содержит:

- `outputRoot` — каталог, где создаётся `sleepy-sleep-domain-0.1`;
- `sourceRecordingsRoot` — каталог исходных WAV;
- `participant`, `device`, `session`;
- `recordings` с точными `contentStartSample` и `contentEndSample` или с metadata sidecar;
- `exportedAtMillis`.

Экспорт отклоняется, если consent не `active`, отсутствует scope `dataset_research`, для несовершеннолетнего нет `guardianConsent`, ID не соответствует префиксам, WAV повреждён или границы не совпадают с числом сэмплов.

## Точные границы

`content_start_sample` и `content_end_sample` обязательны. Новые записи сохраняют их в `event_*.wav.meta` вместе с `sample_rate_hz`, `start_sample`, `end_sample`, `truncated` и размером WAV; экспорт читает sidecar, если границы не переданы вызывающей стороной. Legacy-файлы без точных metadata не экспортируются: ошибка `MISSING_CONTENT_BOUNDARIES`. Detector-границы опциональны, но если заданы, должны полностью лежать внутри content-границ.

## Транзакционность

1. Запрос валидируется и все WAV проверяются до создания пакета.
2. Пакет пишется во временный staging-каталог внутри `outputRoot`.
3. SHA-256 копии сверяется с исходником, затем исходник перехэшируется.
4. Staging переименовывается в итоговый пакет.
5. При существующем итоговом пакете сравниваются файлы и manifest без `created_at_utc`. Идентичный пакет переиспользуется, отличающийся даёт `OUTPUT_CONFLICT`.

## Удаление участника

`DatasetPackageDeleter.deleteParticipant(packageRoot, participantId)`:

- проверяет manifest и SHA-256 всех файлов до удаления;
- удаляет participant sidecar, audio-каталог участника, связанные session/recording/QC sidecar;
- удаляет device sidecar, только если он больше не используется другими сессиями;
- пересобирает manifest и README без идентификаторов удалённого участника;
- не удаляет исходные записи Sleepy;
- возвращает `integrityOk=false`, если целостность пакета после операции не подтверждена.

## Ограничения

- Нет consent UI: consent должен передаваться вызывающей стороной.
- Sidecar пишется только новыми версиями приложения; старые WAV остаются без точных границ.
- Sidecar не заменяет WAV: при потере sidecar экспорт отклоняется с `MISSING_CONTENT_BOUNDARIES`.
- Нет event/annotation/split sidecar: в экспорт 0.1 входят только participant, device, session, recording, QC, audio и manifest.
- Полные ночи, ASR, облако и сеть не используются.
- Пакет не является публичным релизом.

## Проверки

```powershell
cd C:\Users\JleS\sleepy\android
.\gradlew.bat testDebugUnitTest --tests "com.sleepy.app.sleepy.dataset.*" --console=plain --no-daemon

cd C:\Users\JleS\sleepy
flutter analyze
flutter test
```
