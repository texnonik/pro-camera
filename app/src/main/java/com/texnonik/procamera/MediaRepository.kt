package com.texnonik.procamera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Та же папка, куда пишет стандартная камера. */
const val CAMERA_RELATIVE_PATH = "DCIM/Camera"

data class MediaItem(
    val uri: Uri,
    val isVideo: Boolean,
    val mime: String,
    val dateAdded: Long,
    val durationMs: Long,
)

object MediaRepository {
    private const val TAG = "MediaRepository"

    fun newFileName(prefix: String): String =
        prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** ContentValues для записи файла в DCIM/Camera через MediaStore. */
    fun contentValues(name: String, mime: String, ext: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, CAMERA_RELATIVE_PATH)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
            dir.mkdirs()
            put(MediaStore.MediaColumns.DATA, File(dir, name + ext).absolutePath)
        }
    }

    suspend fun load(context: Context): List<MediaItem> = withContext(Dispatchers.IO) {
        (query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false) +
            query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true))
            .sortedByDescending { it.dateAdded }
    }

    suspend fun latest(context: Context): MediaItem? = load(context).firstOrNull()

    private fun query(context: Context, collection: Uri, video: Boolean): List<MediaItem> {
        val projection = buildList {
            add(BaseColumns._ID)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.MIME_TYPE)
            if (video) add("duration")
        }.toTypedArray()

        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to arrayOf("$CAMERA_RELATIVE_PATH%")
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("%/DCIM/Camera/%")
        }

        val result = mutableListOf<MediaItem>()
        try {
            context.contentResolver.query(
                collection, projection, selection, args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(BaseColumns._ID)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val durCol = if (video) c.getColumnIndex("duration") else -1
                while (c.moveToNext()) {
                    result += MediaItem(
                        uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                        isVideo = video,
                        mime = c.getString(mimeCol) ?: if (video) "video/mp4" else "image/jpeg",
                        dateAdded = c.getLong(dateCol),
                        durationMs = if (durCol >= 0) c.getLong(durCol) else 0L,
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed", e)
        }
        return result
    }
}
