package com.tom.animacolab.data

import android.content.Context
import java.io.File

/**
 * App 私有图片库：出图完成后自动下载到这里（不进系统相册），
 * 用户点「保存」时才写入 MediaStore 相册；点「分享」也优先用本地字节（不再走隧道重下）。
 */
class LocalImageStore(context: Context) {

    private val dir: File = File(context.filesDir, "images").apply { mkdirs() }

    fun path(promptId: String, filename: String): File =
        File(dir, "${safe(promptId)}__${safe(filename)}")

    fun write(promptId: String, filename: String, bytes: ByteArray): Boolean =
        runCatching { path(promptId, filename).writeBytes(bytes) }.isSuccess && bytes.isNotEmpty()

    fun read(promptId: String, filename: String): ByteArray? =
        runCatching { path(promptId, filename).takeIf { it.exists() }?.readBytes() }.getOrNull()

    fun exists(promptId: String, filename: String): Boolean =
        path(promptId, filename).exists()

    fun totalCount(): Int = dir.listFiles()?.count { it.isFile } ?: 0

    private fun safe(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "img" }
}
