# Формат экспорта Sleepy Sleep-Domain 0.1

## Структура пакета

```text
sleepy-sleep-domain-0.1/
  README.md
  manifest.json
  participants/<participant_id>.json
  devices/<device_id>.json
  sessions/<session_id>.json
  recordings/<recording_id>.json
  qc/<session_id>.qc.json
  audio/event_fragments/<participant_id>/<session_id>/<file>.wav
```

## Идентификаторы

- participant: `ptc_*`;
- device: `dev_*`;
- session: `ses_*`;
- recording: `rec_` + первые 24 hex SHA-256 от `session_id|sha256|content_start|content_end`.

`recording_id` не зависит от имени WAV и времени запуска. Исходный session ID получает префикс `ses_`, если его ещё нет.

## Participant

Поля: `schema_version`, `participant_id`, `created_at_utc`, `consent`, опционально `age_group`, `notes`.

`consent` содержит `consent_version`, `granted_at_utc`, `status`, `scopes`, `minor_involved`, `guardian_consent`.

## Device

Поля: `schema_version`, `device_id`, `created_at_utc`, `manufacturer`, `model`, `os_version`, `app_version`, `audio_source`, `sample_rate_hz`, `channels`, `bits_per_sample`, `microphone_position`, `placement_notes`, `calibration_status`, опционально `notes`.

## Session

Поля: `schema_version`, `session_id`, `participant_id`, `device_id`, `started_at_utc`, `ended_at_utc`, `timezone`, `utc_offset_minutes`, `night_index`, `recording_mode`, `room_type`, `session_status`, `consent_version`, `app_version`, `detector_config`, опционально `household_audio_risk`, `notes`.

`detector_config` берётся из фактического `RecordingConfig.DEFAULT`, а не из вымышленных значений.

## Recording

Поля: `schema_version`, `recording_id`, `session_id`, `asset_type`, `relative_path`, `format`, `sample_rate_hz`, `channels`, `bits_per_sample`, `duration_ms`, `started_at_utc`, `ended_at_utc`, `size_bytes`, `sha256`, `validation_status`, `is_truncated`, `content_start_sample`, `content_end_sample`, опционально `detector_start_sample`, `detector_end_sample`, `peak_dbfs`, `average_dbfs`, `notes`, обязательные `privacy_review_status`, `privacy_flags`.

Семантика границ: `[content_start_sample, content_end_sample)`; число сэмплов равно `content_end_sample - content_start_sample` и `data_bytes / 2`. Время: `started_at_utc` соответствует `fileStartedAtMillis`, `ended_at_utc` — его концу.

Для новых записей границы берутся из `event_*.wav.meta`. Сам sidecar не копируется в пакет: запись в пакете самодостаточна.

## QC

`qc/<session_id>.qc.json` содержит:

- `schema_version`, `session_id`, `status`;
- `recording_count`, `total_samples`, `total_duration_ms`;
- `truncated_recording_count`, `detector_metadata_recording_count`;
- `human_annotations_included: false`;
- `warnings`.

## Manifest

`manifest.json` содержит `dataset_id`, версии, `created_at_utc`, `frozen`, `export_engine_version`, `participant_ids`, `session_ids`, `recording_ids`, `human_annotations_included`, `detector_metadata_complete`, `consent_scopes`, `limitations` и `files`.

`files` — отсортированный список объектов `path`, `size_bytes`, `sha256`. Manifest не включает собственный хэш.

## Предупреждения

Экспорт добавляет предупреждения, если:

- у части записей нет detector-границ;
- не все записи прошли privacy review;
- человеческие аннотации отсутствуют;
- существующий пакет был переиспользован.
