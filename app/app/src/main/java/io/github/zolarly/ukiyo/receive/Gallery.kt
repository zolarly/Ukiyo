package io.github.zolarly.ukiyo.receive

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.io.IOException

/** 只读缓存文件生成小图，不改动文件；不支持的格式或解码失败都返回 null。 */
fun thumbnail(file: File, type: String): Bitmap? = try {
    val size = Size(400, 400)
    when {
        type.startsWith("image/") -> ThumbnailUtils.createImageThumbnail(file, size, null)
        type.startsWith("video/") -> ThumbnailUtils.createVideoThumbnail(file, size, null)
        else -> null
    }
} catch (e: Exception) {
    // 解码器可能抛各种异常，缩略图失败不能让下载失败。
    null
}

/** 把缓存文件按原始字节复制进 MediaStore。照片和视频放进 DCIM/Ukiyo，其他文件放进 Download/Ukiyo。 */
fun saveToGallery(resolver: ContentResolver, file: File, name: String, type: String) {
    val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
    val (collection, dir) = when {
        type.startsWith("image/") -> MediaStore.Images.Media.getContentUri(volume) to Environment.DIRECTORY_DCIM
        type.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volume) to Environment.DIRECTORY_DCIM
        else -> MediaStore.Downloads.getContentUri(volume) to Environment.DIRECTORY_DOWNLOADS
    }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, type.ifEmpty { "application/octet-stream" })
        put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Ukiyo")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(collection, values) ?: throw IOException("无法写入相册")
    try {
        val out = resolver.openOutputStream(uri) ?: throw IOException("无法写入相册")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}
