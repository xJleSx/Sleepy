package com.sleepy.app.sleepy.audio

/**
 * Жизненный цикл одной записи, отделённый от Android-службы.
 *
 * Контроллер решает, когда можно начать новую сессию, что делать с
 * повторным запуском и остановкой, и как вести себя, если поток захвата
 * завис. Все побочные действия (foreground-уведомление, WakeLock,
 * остановка службы, watchdog) передаются лямбдами, поэтому логику можно
 * проверить обычными JVM-тестами.
 *
 * Гарантии:
 * - одновременно активна не более одна сессия: повторный [start] при
 *   работающей сессии игнорируется;
 * - [stop] не блокирует вызывающий поток: он только просит сессию
 *   остановиться, а тяжёлую работу делает поток захвата;
 * - watchdog не освобождает WakeLock и не разрешает новый запуск, пока
 *   поток захвата действительно жив;
 * - запоздавший колбэк старой сессии не трогает новую.
 */
internal class RecordingSessionController<S : RecordingSessionHandle>(
    private val status: RecordingStatusSink,
    private val createSession: (onFinished: (S) -> Unit) -> S?,
    private val startForeground: () -> Unit,
    private val stopForeground: () -> Unit,
    private val acquireWakeLock: () -> Unit,
    private val releaseWakeLock: () -> Unit,
    private val requestStopService: () -> Unit,
    private val cancelWatchdog: () -> Unit,
    private val maxWatchdogRetries: Int = MAX_WATCHDOG_RETRIES,
) {

    private var session: S? = null
    private var running = false
    private var watchdogRetries = 0

    val isRunning: Boolean
        get() = running

    /**
     * Запускает мониторинг. Повторный вызов при активной сессии ничего не
     * делает и не создаёт второй поток захвата.
     */
    fun start() {
        cleanupFinishedSession()

        if (running) return

        cancelWatchdog()

        try {
            startForeground()
        } catch (error: Exception) {
            status.saveFatalError(
                error.message ?: "Не удалось запустить службу мониторинга",
            )
            requestStopService()
            return
        }

        val newSession = try {
            createSession(::onSessionFinished)
        } catch (error: Exception) {
            status.saveFatalError(
                error.message ?: "Не удалось создать сессию записи",
            )
            stopForeground()
            requestStopService()
            return
        }

        if (newSession == null) {
            stopForeground()
            requestStopService()
            return
        }

        val started = try {
            newSession.start()
        } catch (error: Exception) {
            status.saveFatalError(
                error.message ?: "Не удалось запустить запись",
            )
            false
        }

        if (!started) {
            stopForeground()
            requestStopService()
            return
        }

        session = newSession
        running = true
        watchdogRetries = 0

        try {
            acquireWakeLock()
        } catch (_: Exception) {
            status.reportWarning("Не удалось удержать устройство активным")
        }
    }

    /**
     * Просит активную сессию остановиться.
     *
     * @return `true`, если службе нужно запланировать watchdog: поток
     * захвата ещё должен закрыть файлы и освободить микрофон.
     */
    fun stop(): Boolean {
        if (!running) {
            // Сессия могла умереть вместе с процессом: сбрасываем
            // устаревшее состояние, иначе интерфейс «зависнет» в записи.
            if (status.isRecording()) {
                status.markStopped()
            }
            stopForeground()
            requestStopService()
            return false
        }

        session?.stop()
        cancelWatchdog()
        watchdogRetries = 0
        return true
    }

    /**
     * Проверка watchdog. Возвращает `true`, если проверку нужно повторить
     * позже: поток захвата всё ещё жив и закрывает файлы.
     *
     * Пока поток жив, ресурсы не освобождаются и новый запуск невозможен.
     */
    fun onWatchdog(): Boolean {
        if (!running) return false

        val current = session

        if (current != null && current.isActive) {
            current.stop()

            watchdogRetries += 1
            if (watchdogRetries <= maxWatchdogRetries) {
                status.reportWarning(
                    "Остановка записи затягивается: поток захвата ещё работает",
                )
                return true
            }

            status.markInterrupted(
                "Не удалось корректно остановить запись: поток захвата не завершился",
            )
            // Ссылку на сессию не сбрасываем: пока поток жив, он держит
            // микрофон, и второй запуск недопустим.
            stopForeground()
            requestStopService()
            return false
        }

        finishCurrentSession()
        return false
    }

    /**
     * Колбэк завершившейся сессии. Должен вызываться в том же потоке, что
     * и остальные методы контроллера (в службе — главный поток).
     */
    fun onSessionFinished(finished: S) {
        // Запоздавший колбэк от предыдущей сессии не должен трогать новую.
        if (session !== finished) return

        cancelWatchdog()

        if (!running) return

        finishCurrentSession()
    }

    /** Освобождает ресурсы при уничтожении службы. */
    fun onDestroy() {
        cancelWatchdog()

        val current = session
        session = null
        running = false
        watchdogRetries = 0

        current?.stop()
        releaseWakeLock()
    }

    /**
     * Закрывает окно между завершением потока захвата и колбэком
     * [onSessionFinished]: без этого быстрый повторный запуск мог бы быть
     * отклонён как «сессия уже идёт».
     */
    private fun cleanupFinishedSession() {
        val current = session ?: return
        if (current.isActive) return

        cancelWatchdog()
        session = null
        running = false
        watchdogRetries = 0
        releaseWakeLock()
    }

    private fun finishCurrentSession() {
        cancelWatchdog()
        session = null
        running = false
        watchdogRetries = 0

        releaseWakeLock()
        stopForeground()
        requestStopService()
    }

    companion object {
        private const val MAX_WATCHDOG_RETRIES = 2
    }
}
