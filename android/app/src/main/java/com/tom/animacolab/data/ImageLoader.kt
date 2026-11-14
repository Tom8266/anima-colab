package com.tom.animacolab.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 网络图片加载：OkHttp 带 token 下载 + 内存 LruCache + 缩采样 */
class ImageLoader(private val client: ComfyClient) {

    private val memory = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 原始 PNG 字节缓存：同一张图在缩略图/结果卡/大图三种尺寸间复用，避免重复走隧道下载 */
    private val rawBytes = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    suspend fun load(url: String, token: String, targetW: Int = 0, targetH: Int = 0): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "$url|$targetW|$targetH"
            memory.get(key)?.let { return@withContext it }
            val bytes = rawBytes.get(url) ?: runCatching { client.getBytes(url, token) }
                .getOrNull()?.also { rawBytes.put(url, it) } ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@withContext null
            memory.put(key, bmp)
            bmp
        }

    /** 取原始 PNG 字节（优先命中缓存；自动保存/分享复用同一份，不重复走隧道下载） */
    suspend fun bytes(url: String, token: String): ByteArray? = withContext(Dispatchers.IO) {
        rawBytes.get(url) ?: runCatching { client.getBytes(url, token) }.getOrNull()?.also { rawBytes.put(url, it) }
    }

    /** 预取图片字节到缓存（成功出图后调用，图库/大图就不用再慢慢下载） */
    suspend fun prefetch(url: String, token: String) = withContext(Dispatchers.IO) {
        if (rawBytes.get(url) != null) return@withContext
        runCatching { client.getBytes(url, token) }.getOrNull()?.let { rawBytes.put(url, it) }
    }

    private fun sampleSize(w: Int, h: Int, targetW: Int, targetH: Int): Int {
        if (w <= 0 || h <= 0 || targetW <= 0 || targetH <= 0) return 1
        var sample = 1
        while (w / (sample * 2) >= targetW && h / (sample * 2) >= targetH) sample *= 2
        return sample
    }
}

/** 保存到系统相册 + 分享 */
object MediaSaver {

    fun savePng(context: Context, bytes: ByteArray, displayName: String): Uri? {
        val name = if (displayName.endsWith(".png", ignoreCase = true)) displayName else "$displayName.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AnimaColab")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return uri
    }

    fun share(context: Context, uri: Uri, title: String = "分享图片") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
