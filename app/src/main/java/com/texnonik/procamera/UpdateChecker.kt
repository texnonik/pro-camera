package com.texnonik.procamera

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Новые версии: релизы GitHub с тегом build-N, где N совпадает с versionCode. */
object UpdateChecker {
    const val DOWNLOAD_URL = "https://github.com/texnonik/pro-camera/releases/latest/download/ProCamera.apk"
    private const val LATEST_URL = "https://api.github.com/repos/texnonik/pro-camera/releases/latest"

    /** Номер более новой сборки или null, если установлена последняя. Бросает исключение при ошибке сети. */
    suspend fun findNewerBuild(context: Context): Int? = withContext(Dispatchers.IO) {
        val connection = URL(LATEST_URL).openConnection() as HttpURLConnection
        val body = try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "ProCamera")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val latest = JSONObject(body).getString("tag_name").removePrefix("build-").toInt()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        latest.takeIf { it > PackageInfoCompat.getLongVersionCode(info) }
    }
}
