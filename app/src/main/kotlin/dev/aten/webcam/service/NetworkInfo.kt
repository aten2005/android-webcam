package dev.aten.webcam.service

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.SocketException

object NetworkInfo {
    /** Addresses a client could use to reach this device, IPv4 first. Link-local IPv6 is left out. */
    fun localAddresses(): List<String> {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: SocketException) {
            emptyList()
        }
        return interfaces
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isMulticastAddress }
            .sortedBy { if (it is Inet4Address) 0 else 1 }
            .mapNotNull { address ->
                when (address) {
                    is Inet6Address -> address.hostAddress?.substringBefore('%')
                    else -> address.hostAddress
                }
            }
            .distinct()
    }

    fun urlFor(address: String, port: Int): String =
        if (address.contains(':')) "https://[$address]:$port" else "https://$address:$port"
}
