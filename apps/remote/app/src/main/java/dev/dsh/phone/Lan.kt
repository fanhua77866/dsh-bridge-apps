package dev.dsh.phone

import java.net.Inet4Address
import java.net.NetworkInterface

/** 拿本机局域网 IPv4，用于猜桥接器地址。 */
object Lan {
    fun ipv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (t: Throwable) {
            null
        }
    }
}
