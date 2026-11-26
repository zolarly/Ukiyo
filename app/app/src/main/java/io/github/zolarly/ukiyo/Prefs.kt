package io.github.zolarly.ukiyo

import android.content.Context
import androidx.core.content.edit
import io.github.zolarly.ukiyo.net.Tokens

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ukiyo", Context.MODE_PRIVATE)

    var server: String
        get() = sp.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        set(value) {
            val v = value.trim().trimEnd('/')
            if (v != server) clearLogin()
            sp.edit { putString("server", v) }
        }

    val access get() = sp.getString("access", null)
    val refresh get() = sp.getString("refresh", null)
    val username get() = sp.getString("username", null)
    val loggedIn get() = refresh != null

    fun saveLogin(t: Tokens) = sp.edit {
        putString("access", t.access)
        putString("refresh", t.refresh)
        putString("username", t.username)
    }

    fun clearLogin() = sp.edit {
        remove("access")
        remove("refresh")
        remove("username")
    }
}
