package com.sleepy.app.sleepy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.sleepy.app.sleepy/recording"
        private const val PERMISSION_REQUEST_CODE = 501

        private const val PREFS_NAME = "sleepy_recording"
    }

    private var pendingPermissionResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

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

                else -> {
                    result.notImplemented()
                }
            }
        }
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
        val preferences = getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        )

        val status = hashMapOf<String, Any>(
            "isRecording" to preferences.getBoolean("is_recording", false),
            "filePath" to preferences.getString("file_path", "").orEmpty(),
            "startedAtMillis" to preferences.getLong("started_at", 0L),
            "error" to preferences.getString("error", "").orEmpty()
        )

        result.success(status)
    }
}