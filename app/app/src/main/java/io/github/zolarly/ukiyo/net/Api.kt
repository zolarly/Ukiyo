package io.github.zolarly.ukiyo.net

import android.content.ContentResolver
import io.github.zolarly.ukiyo.Prefs
import io.github.zolarly.ukiyo.send.LocalFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class ApiException(val code: Int, message: String) : IOException(message)

data class CreatedShare(val url: String, val maxViews: Int)

data class RemoteFile(val name: String, val type: String, val size: Long)

data class ShareInfo(val files: List<RemoteFile>, val expiresAt: Long, val viewsLeft: Int?)

data class MyShare(
    val token: String,
    val url: String,
    val count: Int,
    val expiresAt: Long,
    val maxViews: Int,
    val viewsUsed: Int,
)

object Api {
    private lateinit var prefs: Prefs
    private lateinit var auth: Auth

    // 超时是"多久没有任何数据收发"，不是总时长，所以大文件慢慢传不会超时。
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val authed by lazy { client.newBuilder().addInterceptor(auth).authenticator(auth).build() }

    fun init(prefs: Prefs) {
        if (::prefs.isInitialized) return
        this.prefs = prefs
        auth = Auth(prefs, client)
    }

    suspend fun login(register: Boolean, username: String, password: String) {
        val path = if (register) "register" else "login"
        val request = Request.Builder()
            .url("${prefs.server}/api/auth/$path")
            .post(jsonBody(JSONObject().put("username", username).put("password", password)))
            .build()
        prefs.saveLogin(parseTokens(client.newCall(request).executeAsync().use { it.textOrThrow() }))
    }

    /** 先清本地令牌，再通知服务器作废 refresh token；通知失败也无所谓。 */
    fun logout() {
        val refresh = prefs.refresh ?: return
        val request = Request.Builder()
            .url("${prefs.server}/api/auth/logout")
            .post(jsonBody(JSONObject().put("refresh_token", refresh)))
            .build()
        prefs.clearLogin()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }

    suspend fun createShare(
        resolver: ContentResolver,
        files: List<LocalFile>,
        maxViews: Int,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): CreatedShare {
        withContext(Dispatchers.IO) { auth.freshAccess() } ?: throw ApiException(401, "登录已失效")
        val total = files.sumOf { it.size.coerceAtLeast(0) }
        val written = LongArray(files.size)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("max_views", maxViews.toString())
            .apply {
                files.forEachIndexed { i, file ->
                    addFormDataPart("file", file.name, UriBody(resolver, file) { n ->
                        written[i] = n
                        onProgress(written.sum(), total)
                    })
                }
            }
            .build()
        val request = Request.Builder().url("${prefs.server}/api/shares").post(body).build()
        val text = authed.newCall(request).executeAsync().use { it.textOrThrow() }
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IOException("服务器响应格式不对", e)
        }
        return try {
            CreatedShare(json.getString("url"), json.getInt("max_views"))
        } catch (e: JSONException) {
            throw IOException("服务器响应格式不对", e)
        }
    }

    suspend fun getShare(link: ShareLink): ShareInfo {
        val request = Request.Builder().url(link.apiUrl).build()
        val text = client.newCall(request).executeAsync().use { it.textOrThrow() }
        // JSONException 和 DateTimeParseException 都是 RuntimeException
        return try {
            val json = JSONObject(text)
            val files = json.getJSONArray("files")
            ShareInfo(
                files = (0 until files.length()).map { i ->
                    val f = files.getJSONObject(i)
                    RemoteFile(f.getString("name"), f.optString("type"), f.getLong("size"))
                },
                expiresAt = OffsetDateTime.parse(json.getString("expires_at")).toInstant().toEpochMilli(),
                viewsLeft = if (json.isNull("views_left")) null else json.getInt("views_left"),
            )
        } catch (e: RuntimeException) {
            throw IOException("服务器响应格式不对", e)
        }
    }

    suspend fun open(link: ShareLink): List<String> {
        val request = Request.Builder().url("${link.apiUrl}/open").post("".toRequestBody()).build()
        val text = client.newCall(request).executeAsync().use { it.textOrThrow() }
        // D1：和 getShare 一样，JSON 解析失败不能把 JSONException 原样抛出去。
        return try {
            val json = JSONObject(text)
            val files = json.getJSONArray("files")
            (0 until files.length()).map { files.getJSONObject(it).getString("url") }
        } catch (e: RuntimeException) {
            throw IOException("服务器响应格式不对", e)
        }
    }

    suspend fun download(url: String, to: File, onProgress: (Long) -> Unit) {
        val have = to.length()
        val request = Request.Builder().url(url)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        client.newCall(request).executeAsync().use { r ->
            if (!r.isSuccessful) r.textOrThrow()
            val append = r.code == 206
            if (append && r.header("Content-Range")?.startsWith("bytes $have-") != true) {
                to.delete() // 下次重试从头下载
                throw IOException("续传位置不对：${r.header("Content-Range")}")
            }
            withContext(Dispatchers.IO) {
                r.body.byteStream().use { input ->
                    FileOutputStream(to, append).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var written = if (append) have else 0L
                        onProgress(written)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n == -1) break
                            out.write(buffer, 0, n)
                            written += n
                            onProgress(written)
                        }
                    }
                }
            }
        }
    }

    suspend fun myShares(): List<MyShare> {
        val request = Request.Builder().url("${prefs.server}/api/me/shares").build()
        val text = authed.newCall(request).executeAsync().use { it.textOrThrow() }
        return try {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                MyShare(
                    token = o.getString("token"),
                    url = o.getString("url"),
                    count = o.getInt("count"),
                    expiresAt = OffsetDateTime.parse(o.getString("expires_at")).toInstant().toEpochMilli(),
                    maxViews = o.getInt("max_views"),
                    viewsUsed = o.getInt("views_used"),
                )
            }
        } catch (e: RuntimeException) {
            throw IOException("服务器响应格式不对", e)
        }
    }

    suspend fun deleteShare(token: String) {
        val request = Request.Builder().url("${prefs.server}/api/shares/$token").delete().build()
        authed.newCall(request).executeAsync().use { r ->
            if (!r.isSuccessful && r.code != 404) r.textOrThrow()
        }
    }

    private suspend fun Response.textOrThrow(): String = withContext(Dispatchers.IO) {
        val text = body.string()
        if (!isSuccessful) throw ApiException(code, text.trim())
        text
    }
}

/** 从 ContentResolver 边读边写到网络，原样传输字节。onWritten 收到的是这个文件已经写出的总字节数。 */
private class UriBody(
    private val resolver: ContentResolver,
    private val file: LocalFile,
    private val onWritten: (Long) -> Unit,
) : RequestBody() {
    override fun contentType() = file.type.toMediaTypeOrNull()

    // 不声明长度，走 chunked 传输：有些 provider 报告的 SIZE 和实际字节数不一致，声明了反而会让请求出错。
    // 代价是后端拿不到 Content-Length，会按 max_upload_mb 预占空间。
    override fun contentLength() = -1L

    override fun writeTo(sink: BufferedSink) {
        // 权限被收回时 openInputStream 抛 SecurityException，它在 OkHttp 线程上会直接让进程崩溃，所以转成 IOException。
        val input = try {
            resolver.openInputStream(file.uri)
        } catch (e: SecurityException) {
            null
        } ?: throw IOException("无法读取 ${file.name}")
        input.use {
            val buffer = ByteArray(64 * 1024)
            var written = 0L
            onWritten(0)
            while (true) {
                val n = it.read(buffer)
                if (n == -1) break
                sink.write(buffer, 0, n)
                written += n
                onWritten(written)
            }
        }
    }
}
