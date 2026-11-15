package dev.dsh.recorder

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

data class MicDevice(
    val id: Int,
    val name: String,
    val typeLabel: String,
)

object AudioDevices {

    fun list(context: Context): List<MicDevice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        val wanted = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
        return runCatching {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.type in wanted }
                .map { info ->
                    MicDevice(
                        id = info.id,
                        name = info.productName?.toString()?.takeIf { it.isNotBlank() } ?: typeLabel(info.type),
                        typeLabel = typeLabel(info.type),
                    )
                }
                .distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    fun find(context: Context, id: Int): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return runCatching {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
        }.getOrNull()
    }

    fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "手机麦克风"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机麦"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 设备"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙麦"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙音频"
        AudioDeviceInfo.TYPE_TELEPHONY -> "通话麦克风"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM"
        else -> "输入设备($type)"
    }
}
