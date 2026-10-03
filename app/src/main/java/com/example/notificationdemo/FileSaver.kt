package com.example.notificationdemo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出文件落盘。
 *
 * 网页的导出走的是 blob: URL，WebView 的 DownloadListener 拿到的地址系统下载器读不了，
 * 因此在 JS 侧把 Blob 读成 data URL 后交给这里写文件。
 */
object FileSaver {

    /**
     * 写入公共下载目录，返回可展示的相对路径或文件名。
     */
    fun saveDownload(context: Context, rawName: String, mime: String, dataUrl: String): String? {
        val base64 = extractBase64(dataUrl) ?: return null
        val bytes = try {
            android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            return null
        }
        if (bytes.isEmpty()) return null

        val name = sanitize(uniqueName(rawName, mime))
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, name, mime, bytes)
            } else {
                saveViaFile(context, name, bytes)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractBase64(dataUrl: String): String? {
        val idx = dataUrl.indexOf("base64,")
        return if (idx >= 0) dataUrl.substring(idx + 7) else null
    }

    private fun saveViaMediaStore(context: Context, name: String, mime: String, bytes: ByteArray): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            return saveViaFile(context, name, bytes)
        }
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return name
    }

    @Suppress("DEPRECATION")
    private fun saveViaFile(context: Context, name: String, bytes: ByteArray): String {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val out = File(dir, name)
        FileOutputStream(out).use { it.write(bytes) }
        return out.absolutePath
    }

    private fun uniqueName(raw: String, mime: String): String {
        val base = raw.ifBlank { "yann_export" }
        if (base.contains('.')) return base
        val ext = when {
            mime.contains("json") -> ".json"
            mime.contains("zip") -> ".zip"
            mime.contains("csv") -> ".csv"
            mime.contains("image/png") -> ".png"
            mime.contains("image/jpeg") -> ".jpg"
            else -> ".json"
        }
        return base + ext
    }

    private fun sanitize(name: String): String {
        val clean = name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5 ]"), "_")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dot = clean.lastIndexOf('.')
        return if (dot > 0) {
            clean.substring(0, dot) + "_" + stamp + clean.substring(dot)
        } else {
            clean + "_" + stamp
        }
    }

    /** 下载头像做通知大图标；失败返回 null，不影响通知本身 */
    fun loadBitmap(url: String): Bitmap? {
        return try {
            if (url.startsWith("data:")) {
                val b64 = extractBase64(url) ?: return null
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                decodeScaled(bytes)
            } else if (url.startsWith("http")) {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.instanceFollowRedirects = true
                conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    input.copyTo(out)
                    decodeScaled(out.toByteArray())
                }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        val target = 256
        val maxSide = maxOf(opts.outWidth, opts.outHeight)
        while (maxSide / sample > target * 2) sample *= 2
        val real = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real)
    }
}
