package io.github.zolarly.ukiyo.net

data class ShareLink(val server: String, val token: String) {
    val apiUrl get() = "$server/api/shares/$token"
}

private val linkRe = Regex("""(https?://[A-Za-z0-9.-]+(?::\d+)?)/s/([A-Za-z0-9_-]{22})(?![A-Za-z0-9_-])""")

/** 从任意文字（比如整条微信消息）里找出第一个分享链接。 */
fun findShareLink(text: String): ShareLink? =
    linkRe.find(text)?.let { ShareLink(it.groupValues[1], it.groupValues[2]) }
