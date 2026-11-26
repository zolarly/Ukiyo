package io.github.zolarly.ukiyo.send

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

data class LocalFile(val uri: Uri, val name: String, val size: Long, val type: String)

fun ContentResolver.describe(uri: Uri): LocalFile {
    var name = uri.lastPathSegment ?: "file"
    var size = -1L
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    return LocalFile(uri, name, size, getType(uri) ?: "application/octet-stream")
}
