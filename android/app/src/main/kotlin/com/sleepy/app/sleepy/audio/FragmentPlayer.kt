package com.sleepy.app.sleepy.audio

import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * Воспроизведение одного WAV-фрагмента через системный [MediaPlayer].
 *
 * Все методы вызываются из главного потока (обработчик MethodChannel):
 * MediaPlayer и его колбэки живут в главном Looper, поэтому блокировки
 * не нужны. Плеер не трогает файлы событий — только читает выбранный
 * пользователем фрагмент.
 */
internal class FragmentPlayer {

    private var player: MediaPlayer? = null
    private var prepared: Boolean = false
    private var pendingPlay: Boolean = false
    private var durationMs: Long = 0L
    private var lastError: String = ""
    private var currentPath: String? = null

    fun play(path: String) {
        releasePlayer()
        currentPath = path
        pendingPlay = true
        lastError = ""

        val created = MediaPlayer()

        try {
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            created.setDataSource(path)
        } catch (error: Exception) {
            created.release()
            fail("Не удалось открыть аудиофайл")
            return
        }

        created.setOnPreparedListener { mediaPlayer ->
            if (player !== mediaPlayer) return@setOnPreparedListener

            prepared = true
            durationMs = mediaPlayer.duration.toLong().coerceAtLeast(0L)

            if (pendingPlay) {
                pendingPlay = false
                mediaPlayer.start()
            }
        }

        created.setOnCompletionListener { mediaPlayer ->
            if (player !== mediaPlayer) return@setOnCompletionListener

            pendingPlay = false
            prepared = true
            durationMs = mediaPlayer.duration.toLong().coerceAtLeast(durationMs)
        }

        created.setOnErrorListener { mediaPlayer, _, _ ->
            if (player === mediaPlayer) {
                fail("Не удалось воспроизвести фрагмент")
            } else {
                mediaPlayer.release()
            }

            true
        }

        player = created

        try {
            created.prepareAsync()
        } catch (error: Exception) {
            fail("Не удалось подготовить аудиофайл")
        }
    }

    fun pause() {
        val current = player ?: return

        pendingPlay = false

        try {
            if (current.isPlaying) current.pause()
        } catch (_: IllegalStateException) {
            // Плеер уже освобождён или ещё не готов.
        }
    }

    fun resume() {
        val current = player

        if (current == null || !prepared) {
            currentPath?.let(::play)
            return
        }

        try {
            if (current.currentPosition >= durationMs - 150L) {
                current.seekTo(0)
            }

            current.start()
        } catch (_: IllegalStateException) {
            fail("Не удалось возобновить воспроизведение")
        }
    }

    fun stop() {
        val current = player

        if (current != null && prepared) {
            try {
                if (current.isPlaying) current.pause()
                current.seekTo(0)
            } catch (_: IllegalStateException) {
                // Плеер уже освобождён: состояние сбросит следующий запуск.
            }
        } else {
            releasePlayer()
        }

        lastError = ""
    }

    fun seekTo(positionMs: Long) {
        val current = player ?: return
        if (!prepared) return

        val limit = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        val target = positionMs.coerceIn(0L, limit)

        try {
            current.seekTo(target.toInt())
        } catch (_: IllegalStateException) {
            // Плеер освобождён: позиция просто не меняется.
        }
    }

    fun snapshot(): Map<String, Any> {
        val current = player
        var positionMs = 0L
        var playing = false
        var duration = durationMs

        if (current != null && prepared) {
            positionMs = try {
                current.currentPosition.toLong()
            } catch (_: IllegalStateException) {
                0L
            }

            playing = try {
                current.isPlaying
            } catch (_: IllegalStateException) {
                false
            }

            duration = try {
                current.duration.toLong().coerceAtLeast(0L)
            } catch (_: IllegalStateException) {
                durationMs
            }

            if (duration > 0L) durationMs = duration
        }

        if (duration > 0L) {
            positionMs = positionMs.coerceIn(0L, duration)
        } else {
            positionMs = positionMs.coerceAtLeast(0L)
        }

        return hashMapOf(
            "path" to (currentPath ?: ""),
            "prepared" to prepared,
            "isPlaying" to playing,
            "positionMs" to positionMs,
            "durationMs" to duration,
            "error" to lastError,
        )
    }

    fun release() {
        releasePlayer()
        currentPath = null
        durationMs = 0L
        lastError = ""
    }

    private fun fail(message: String) {
        releasePlayer()
        lastError = message
    }

    private fun releasePlayer() {
        val current = player ?: return
        player = null
        prepared = false
        pendingPlay = false

        try {
            current.reset()
        } catch (_: IllegalStateException) {
            // Игнорируем: освобождаем в любом случае.
        }

        current.release()
    }
}
