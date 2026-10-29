package com.mattintech.papercase.locker.transfer

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object P2pNetworkHelper {

    /**
     * Resolves the primary local IPv4 address of the device on the Wi-Fi/LAN interface.
     * Prioritizes common Wi-Fi interface names (wlan0, etc.) and private subnet ranges.
     */
    fun getLocalWifiIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            
            // Priority 1: Check known Wi-Fi interface names (wlan0, wlan1)
            for (intf in interfaces) {
                if (intf.name.startsWith("wlan", ignoreCase = true) && intf.isUp && !intf.isLoopback) {
                    val addresses = Collections.list(intf.inetAddresses)
                    for (addr in addresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress
                            if (!ip.isNullOrBlank() && !ip.startsWith("127.")) {
                                return ip
                            }
                        }
                    }
                }
            }

            // Priority 2: Any non-loopback IPv4 in private address spaces (192.168.x.x, 10.x.x.x, 172.16-31.x.x)
            for (intf in interfaces) {
                if (intf.isUp && !intf.isLoopback) {
                    val addresses = Collections.list(intf.inetAddresses)
                    for (addr in addresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress
                            if (!ip.isNullOrBlank() && isPrivateSubnet(ip)) {
                                return ip
                            }
                        }
                    }
                }
            }

            // Priority 3: Any non-loopback IPv4
            for (intf in interfaces) {
                if (intf.isUp && !intf.isLoopback) {
                    val addresses = Collections.list(intf.inetAddresses)
                    for (addr in addresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress
                            if (!ip.isNullOrBlank() && !ip.startsWith("127.")) {
                                return ip
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignored, will return null
        }
        return null
    }

    private fun isPrivateSubnet(ip: String): Boolean {
        return ip.startsWith("192.168.") ||
                ip.startsWith("10.") ||
                (ip.startsWith("172.") && isClassBPrivate(ip))
    }

    private fun isClassBPrivate(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size >= 2) {
            val secondOctet = parts[1].toIntOrNull() ?: return false
            return secondOctet in 16..31
        }
        return false
    }
}
