package com.sleepy.app.sleepy.storage

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class SleepyLocalStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, DIRECTORY)

    fun readText(key: String): String? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return try {
            file.readText(Charsets.UTF_8)
        } catch (exception: IOException) {
            throw IllegalStateException(
                exception.message ?: "Не удалось прочитать локальные данные",
                exception,
            )
        }
    }

    fun writeText(key: String, value: String) {
        val file = fileFor(key)
        val parent = file.parentFile ?: throw IllegalArgumentException("Некорректный ключ")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IllegalStateException("Не удалось создать каталог данных")
        }

        val temporary = File(parent, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }

            if (!temporary.renameTo(file)) {
                file.delete()
                if (!temporary.renameTo(file)) {
                    temporary.copyTo(file, overwrite = true)
                    temporary.delete()
                }
            }
        } catch (exception: IOException) {
            temporary.delete()
            throw IllegalStateException(
                exception.message ?: "Не удалось сохранить локальные данные",
                exception,
            )
        }
    }

    private fun fileFor(key: String): File {
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH) {
            throw IllegalArgumentException("Некорректный ключ хранилища")
        }
        if (!KEY_PATTERN.matches(key)) {
            throw IllegalArgumentException("Ключ хранилища содержит недопустимые символы")
        }

        val file = File(root, key)
        val canonicalRoot = root.canonicalFile
        val canonicalFile = file.canonicalFile
        if (canonicalFile.parentFile != canonicalRoot) {
            throw IllegalArgumentException("Ключ хранилища выходит за пределы каталога")
        }
        return canonicalFile
    }

    companion object {
        private const val DIRECTORY = "sleepy_state"
        private const val MAX_KEY_LENGTH = 128
        private val KEY_PATTERN = Regex("[A-Za-z0-9._-]+")
    }
}
