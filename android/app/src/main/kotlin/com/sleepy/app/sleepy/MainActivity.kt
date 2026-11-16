package com.sleepy.app.sleepy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.sleepy.app.sleepy.audio.AudioFragmentCatalog
import com.sleepy.app.sleepy.audio.FragmentPlayer
import com.sleepy.app.sleepy.audio.RecordingStatusStore
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.sleepy.app.sleepy/recording"
        private const val PERMISSION_REQUEST_CODE = 501
    }

    private var pendingPermissionResult: MethodChannel.Result? = null

    private val fragmentPlayer = FragmentPlayer()

    private var storageExecutor: ExecutorService? = null

    @Volatile
    private var destroyed = false

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        storageExecutor?.shutdownNow()
        storageExecutor = Executors.newSingleThreadExecutor()
        destroyed = false

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).setMethodCallHandler { call, result ->

            when (call.method) {

                "requestMicrophonePermission" -> {
                    requestMicrophonePermission(result)
                }

                "startRecording" -> {
                    startRecording(result)
                }

                "stopRecording" -> {
                    stopRecording(result)
                }

                "getRecordingStatus" -> {
                    getRecordingStatus(result)
                }

                "listFragments" -> {
                    listFragments(result)
                }

                "playFragment" -> {
                    playFragment(call.argument<String>("path").orEmpty(), result)
                }

                "pausePlayback" -> {
                    fragmentPlayer.pause()
                    result.success(true)
                }

                "resumePlayback" -> {
                    fragmentPlayer.resume()
                    result.success(true)
                }

                "stopPlayback" -> {
                    fragmentPlayer.stop()
                    result.success(true)
                }

                "seekPlayback" -> {
                    val positionMs = call.argument<Number>("positionMs")?.toLong() ?: 0L
                    fragmentPlayer.seekTo(positionMs)
                    result.success(true)
                }

                "getPlaybackState" -> {
                    result.success(fragmentPlayer.snapshot())
                }

                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        storageExecutor?.shutdownNow()
        storageExecutor = null
        fragmentPlayer.release()
        super.onDestroy()
    }

    private fun requestMicrophonePermission(
        result: MethodChannel.Result
    ) {
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            result.success(true)
            return
        }

        if (pendingPermissionResult != null) {
            result.error(
                "PERMISSION_PENDING",
                "Запрос разрешения уже выполняется",
                null
            )
            return
        }

        pendingPermissionResult = result

        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        requestPermissions(
            permissions.toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val microphoneGranted =
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED

            pendingPermissionResult?.success(microphoneGranted)
            pendingPermissionResult = null
        }
    }

    private fun startRecording(result: MethodChannel.Result) {
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            result.error(
                "NO_MICROPHONE_PERMISSION",
                "Нет разрешения на использование микрофона",
                null
            )
            return
        }

        val intent = Intent(
            this,
            SleepRecordingService::class.java
        ).apply {
            action = SleepRecordingService.ACTION_START
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            result.success(true)
        } catch (exception: Exception) {
            result.error(
                "START_SERVICE_ERROR",
                exception.message,
                null
            )
        }
    }

    private fun stopRecording(result: MethodChannel.Result) {
        val intent = Intent(
            this,
            SleepRecordingService::class.java
        ).apply {
            action = SleepRecordingService.ACTION_STOP
        }

        try {
            startService(intent)
            result.success(true)
        } catch (exception: Exception) {
            result.error(
                "STOP_SERVICE_ERROR",
                exception.message,
                null
            )
        }
    }

    private fun getRecordingStatus(result: MethodChannel.Result) {
        val store = RecordingStatusStore(this)

        try {
            result.success(store.readStatus())
        } catch (exception: Exception) {
            result.error(
                "STATUS_READ_ERROR",
                exception.message ?: "Не удалось прочитать состояние записи",
                null
            )
        }
    }

    private fun listFragments(result: MethodChannel.Result) {
        val executor = storageExecutor

        if (executor == null || destroyed) {
            result.error(
                "STORAGE_UNAVAILABLE",
                "Хранилище недоступно",
                null
            )
            return
        }

        executor.execute {
            val payload = try {
                AudioFragmentCatalog
                    .list(recordingsDirectory())
                    .map { fragment ->
                        hashMapOf<String, Any>(
                            "id" to fragment.id,
                            "fileName" to fragment.fileName,
                            "filePath" to fragment.filePath,
                            "startedAtMillis" to fragment.startedAtMillis,
                            "durationMs" to fragment.durationMs,
                            "sizeBytes" to fragment.sizeBytes
                        )
                    }
            } catch (exception: Exception) {
                postToUi {
                    result.error(
                        "LIST_FRAGMENTS_ERROR",
                        exception.message ?: "Не удалось прочитать фрагменты",
                        null
                    )
                }
                return@execute
            }

            postToUi { result.success(payload) }
        }
    }

    private fun playFragment(path: String, result: MethodChannel.Result) {
        if (!isPlayableFragment(path)) {
            result.error(
                "FRAGMENT_UNAVAILABLE",
                "Файл фрагмента недоступен",
                null
            )
            return
        }

        fragmentPlayer.play(path)
        result.success(true)
    }

    private fun isPlayableFragment(path: String): Boolean {
        if (path.isBlank()) return false

        val file = File(path)
        if (!file.isFile) return false
        if (!file.name.endsWith(".wav", ignoreCase = true)) return false

        return try {
            val directory = recordingsDirectory().canonicalFile
            val canonical = file.canonicalFile
            canonical.parentFile == directory
        } catch (_: IOException) {
            false
        }
    }

    private fun recordingsDirectory(): File {
        return File(filesDir, SleepRecordingService.RECORDINGS_DIRECTORY)
    }

    private fun postToUi(block: () -> Unit) {
        if (destroyed) return

        runOnUiThread {
            if (!destroyed) block()
        }
    }
}
