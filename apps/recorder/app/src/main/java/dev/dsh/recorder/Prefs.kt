package dev.dsh.recorder

import android.content.Context

/** 本地设置：录音参数 + 桥接器地址/设备 cookie。 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("dsh_recorder", Context.MODE_PRIVATE)

    var base: String
        get() = sp.getString("base", "http://192.168.1.100:8799") ?: "http://192.168.1.100:8799"
        set(value) = sp.edit().putString("base", value).apply()

    /** 备用地址：出门在外用电脑的 Tailscale 地址。 */
    var altBase: String
        get() = sp.getString("alt_base", "http://100.64.0.1:8799") ?: "http://100.64.0.1:8799"
        set(value) = sp.edit().putString("alt_base", value).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(value) = sp.edit().putString("token", value).apply()

    var lastPin: String
        get() = sp.getString("pin", "000000") ?: "000000"
        set(value) = sp.edit().putString("pin", value).apply()

    /** 上传到电脑时使用的目标目录；留空 = 桥接器默认（<workspace>/手机上传）。 */
    var uploadDir: String
        get() = sp.getString("upload_dir", "") ?: ""
        set(value) = sp.edit().putString("upload_dir", value).apply()

    var sampleRate: Int
        get() = sp.getInt("sample_rate", 48000)
        set(value) = sp.edit().putInt("sample_rate", value).apply()

    /** 指定输入设备 id；-1 = 系统默认。 */
    var deviceId: Int
        get() = sp.getInt("device_id", -1)
        set(value) = sp.edit().putInt("device_id", value).apply()

    /** 说完多久没声音就自动结束本句。 */
    var silenceMs: Int
        get() = sp.getInt("silence_ms", 800)
        set(value) = sp.edit().putInt("silence_ms", value).apply()

    /** 一句话至少要说这么久才算数（防止咳嗽/杂音触发）。 */
    var minSpeechMs: Int
        get() = sp.getInt("min_speech_ms", 300)
        set(value) = sp.edit().putInt("min_speech_ms", value).apply()

    var autoContinue: Boolean
        get() = sp.getBoolean("auto_continue", true)
        set(value) = sp.edit().putBoolean("auto_continue", value).apply()

    /** 识别模型：small 快（默认）；medium 更准但 CPU 上句末对齐慢。 */
    var asrModel: String
        get() = sp.getString("asr_model", "small") ?: "small"
        set(value) = sp.edit().putString("asr_model", value).apply()

    /** 错字提示模式：loose 不标红 / normal 标红 / strict 标红且更严。 */
    var asrStrict: String
        get() = sp.getString("asr_strict", "loose") ?: "loose"
        set(value) = sp.edit().putString("asr_strict", value).apply()

    /** 实时识别（电脑 ASR）：打开后用电脑识别逐字状态，连不上自动退回估算。 */
    var asrEnabled: Boolean
        get() = sp.getBoolean("asr_enabled", true)
        set(value) = sp.edit().putBoolean("asr_enabled", value).apply()

    var beep: Boolean
        get() = sp.getBoolean("beep", true)
        set(value) = sp.edit().putBoolean("beep", value).apply()

    val paired: Boolean
        get() = token.isNotBlank() && base.isNotBlank()

    fun clearPairing() {
        sp.edit().remove("token").apply()
    }
}
