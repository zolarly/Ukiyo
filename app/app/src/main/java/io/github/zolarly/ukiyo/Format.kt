package io.github.zolarly.ukiyo

fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "未知大小"
    bytes < 1 shl 20 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1048576.0)
}

fun formatRemaining(millis: Long): String {
    if (millis <= 0) return "已过期"
    val minutes = (millis + 59_999) / 60_000
    return if (minutes < 60) "还剩 $minutes 分" else "还剩 ${minutes / 60} 小时 ${minutes % 60} 分"
}
