package io.github.zolarly.ukiyo.net

import io.github.zolarly.ukiyo.Prefs
import java.io.IOException
import java.util.Base64
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import org.json.JSONException
import org.json.JSONObject

data class Tokens(val access: String, val refresh: String, val username: String)

fun parseTokens(text: String): Tokens = try {
    val json = JSONObject(text)
    Tokens(json.getString("access_token"), json.getString("refresh_token"), json.getString("username"))
} catch (e: JSONException) {
    throw IOException("服务器响应格式不对", e)
}

internal fun jsonBody(o: JSONObject) = o.toString().toRequestBody("application/json".toMediaType())

/** JWT 中间一段是 base64url 编码的 JSON，取其中的 exp（秒）。不验证签名，只用来判断要不要提前续期。 */
fun jwtExpiry(token: String): Long = try {
    JSONObject(String(Base64.getUrlDecoder().decode(token.split('.')[1]))).optLong("exp")
} catch (e: Exception) {
    0
}

class Auth(private val prefs: Prefs, private val client: OkHttpClient) : Interceptor, Authenticator {
    override fun intercept(chain: Interceptor.Chain): Response {
        val access = prefs.access ?: return chain.proceed(chain.request())
        return chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $access").build())
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null
        val stale = response.request.header("Authorization")?.removePrefix("Bearer ")
        val access = renew(stale) ?: return null
        return response.request.newBuilder().header("Authorization", "Bearer $access").build()
    }

    /** 用 refresh token 换一对新令牌。别的线程已经换过（当前 access 和 stale 不同）就直接用新的。 */
    @Synchronized
    fun renew(stale: String?): String? {
        val current = prefs.access
        if (current != null && current != stale) return current
        val refresh = prefs.refresh ?: return null
        val request = Request.Builder()
            .url("${prefs.server}/api/auth/refresh")
            .post(jsonBody(JSONObject().put("refresh_token", refresh)))
            .build()
        client.newCall(request).execute().use { r ->
            val text = r.body.string()
            if (r.code == 401) {
                if (prefs.refresh == refresh) prefs.clearLogin()
                return null
            }
            if (!r.isSuccessful) throw ApiException(r.code, text.trim())
            val tokens = parseTokens(text)
            // 等响应期间用户可能已经退出登录（后台上传时会发生），这时不能把令牌再存回去
            if (prefs.refresh != refresh) return null
            prefs.saveLogin(tokens)
            return tokens.access
        }
    }

    fun freshAccess(): String? {
        val access = prefs.access ?: return renew(null)
        return if (jwtExpiry(access) - System.currentTimeMillis() / 1000 > 300) access else renew(access)
    }
}
