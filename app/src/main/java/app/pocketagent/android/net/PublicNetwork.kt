package app.pocketagent.android.net

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/** Enforced at the actual connection DNS lookup, including after redirects. */
internal class PublicOnlyDns(private val delegate: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
            throw UnknownHostException("拒绝访问内网/本机或保留地址：$hostname")
        }
        return addresses
    }
}

internal fun isPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    return when (bytes.size) {
        4 -> {
            val a = bytes[0]
            val b = bytes[1]
            a != 0 && a < 224 &&
                !(a == 100 && b in 64..127) && // CGNAT, including some metadata endpoints
                !(a == 192 && b == 0 && (bytes[2] == 0 || bytes[2] == 2)) &&
                !(a == 198 && (b in 18..19 || (b == 51 && bytes[2] == 100))) &&
                !(a == 203 && b == 0 && bytes[2] == 113)
        }
        // Only IPv6 global unicast; excludes ULA, transition/NAT64 and local ranges.
        16 -> (bytes[0] and 0xe0) == 0x20 &&
            !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] < 2) &&
            !(bytes.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8)) &&
            !(bytes[0] == 0x20 && bytes[1] == 0x02) // 6to4 can encode a private IPv4 address
        else -> false
    }
}
