package com.sleepy.app.sleepy.audio

enum class RecordingProfile(val storageValue: String, val displayName: String) {
    STANDARD("STANDARD", "Стандартный"),
    HIGH("HIGH", "Высокая чувствительность"),
    VERY_HIGH("VERY_HIGH", "Очень высокая чувствительность"),
    ;

    companion object {
        fun fromStorage(value: String?): RecordingProfile =
            entries.firstOrNull { it.storageValue == value } ?: STANDARD
    }
}

object RecordingEventMetadata {
    const val EVENT_CLASS_NORMAL = "EVENT"
    const val EVENT_CLASS_WEAK = "WEAK_CANDIDATE"
    const val REVIEW_STATUS_UNREVIEWED = "UNREVIEWED"
}
