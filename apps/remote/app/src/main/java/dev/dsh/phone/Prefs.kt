package dev.dsh.phone

import android.content.Context
import android.content.SharedPreferences

/** 极简配置存储：桥接地址、配对状态、常亮偏好。 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("dsh_phone", Context.MODE_PRIVATE)

    var base: String
        get() = sp.getString("base", "") ?: ""
        set(value) = sp.edit().putString("base", value).apply()

    /** 备用地址（例如出门用的 Tailscale 地址）；主地址连不上时自动切过去。 */
    var altBase: String
        get() = sp.getString("alt_base", "") ?: ""
        set(value) = sp.edit().putString("alt_base", value).apply()

    var paired: Boolean
        get() = sp.getBoolean("paired", false)
        set(value) = sp.edit().putBoolean("paired", value).apply()

    var keepAwake: Boolean
        get() = sp.getBoolean("keep_awake", true)
        set(value) = sp.edit().putBoolean("keep_awake", value).apply()

    var lastPin: String
        get() = sp.getString("pin", "") ?: ""
        set(value) = sp.edit().putString("pin", value).apply()

    fun clearPairing() {
        sp.edit().putBoolean("paired", false).apply()
    }

    companion object {
        const val DEFAULT_PORT = 8799

        fun guessBase(): String {
            val ip = Lan.ipv4()
            return if (ip != null) "http://$ip:$DEFAULT_PORT" else "http://192.168.1.2:$DEFAULT_PORT"
        }
    }
}
