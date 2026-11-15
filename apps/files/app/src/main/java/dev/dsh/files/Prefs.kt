package dev.dsh.files

import android.content.Context

/** 本地配置：桥接地址 + 设备 cookie（配对成功后长期有效）。 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("dsh_files", Context.MODE_PRIVATE)

    var base: String
        get() = sp.getString("base", "") ?: ""
        set(value) = sp.edit().putString("base", value).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(value) = sp.edit().putString("token", value).apply()

    /** 备用地址（例如出门在外用的 Tailscale 地址）；主地址连不上时自动切过去。 */
    var altBase: String
        get() = sp.getString("alt_base", "") ?: ""
        set(value) = sp.edit().putString("alt_base", value).apply()

    /** 后台监听电脑推送（弹通知）的开关。 */
    var pushEnabled: Boolean
        get() = sp.getBoolean("push_enabled", true)
        set(value) = sp.edit().putBoolean("push_enabled", value).apply()

    /** 已通知过的最后 pushedAt（秒），避免重复弹通知。 */
    var pushSince: Long
        get() = sp.getLong("push_since", 0L)
        set(value) = sp.edit().putLong("push_since", value).apply()

    val paired: Boolean
        get() = token.isNotBlank() && base.isNotBlank()

    fun clearPairing() {
        sp.edit().remove("token").apply()
    }
}
