package dev.dsh.files

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf

/** 其他 App 通过系统分享进来的文件，等主界面消费后打开「发送到电脑」。 */
object ShareHub {
    val pending = mutableStateListOf<Uri>()

    fun addAll(uris: List<Uri>) {
        uris.forEach { uri ->
            if (uri.scheme != null && !pending.contains(uri)) {
                pending.add(uri)
            }
        }
    }
}
