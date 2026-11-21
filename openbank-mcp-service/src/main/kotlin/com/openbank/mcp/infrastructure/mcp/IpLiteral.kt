// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

/**
 * Strict IP-literal parser (#16847): a pure grammar, never `InetAddress.getByName`, so no input can
 * reach DNS. Returns the 4 or 16 address bytes, or null.
 *
 * - IPv4: exactly four decimal octets 0-255, no leading zeros, nothing else (`1.2.3.4:443` is null).
 * - IPv6: RFC 4291 text form, at most one `::`, 1-4 hex digits per group, an optional dotted IPv4
 *   tail; optional `[...]` only when BOTH brackets are present; an optional `%zone` is dropped.
 * - An IPv4-mapped IPv6 address (`::ffff:a.b.c.d`) is returned as its 4 IPv4 bytes.
 */
object IpLiteral {
    private const val IPV4_BYTES = 4
    private const val IPV6_GROUPS = 8
    private const val MAX_GROUP_HEX = 4
    private const val HEX = 16
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFF
    private const val MAPPED_PREFIX_BYTES = 10
    private const val MAPPED_MARKER = 0xFF
    private val OCTET = Regex("^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9][0-9]|[0-9])$")
    private val HEX_GROUP = Regex("^[0-9A-Fa-f]{1,$MAX_GROUP_HEX}$")
    private val ZONE = Regex("^[0-9A-Za-z._~-]+$")

    fun parse(text: String?): ByteArray? {
        var s = text?.trim() ?: return null
        val open = s.startsWith("[")
        val close = s.endsWith("]")
        if (open != close) return null
        if (open) s = s.substring(1, s.length - 1)
        if (s.isEmpty()) return null
        if (':' !in s) return if (open) null else ipv4(s)
        val zoneAt = s.indexOf('%')
        if (zoneAt >= 0) {
            if (!ZONE.matches(s.substring(zoneAt + 1))) return null
            s = s.substring(0, zoneAt)
        }
        return ipv6(s)?.let(::unmap)
    }

    private fun ipv4(s: String): ByteArray? {
        val parts = s.split('.')
        if (parts.size != IPV4_BYTES || parts.any { !OCTET.matches(it) }) return null
        return ByteArray(IPV4_BYTES) { parts[it].toInt().toByte() }
    }

    @Suppress("ReturnCount") // a grammar: each malformed shape is its own early refusal
    private fun ipv6(s: String): ByteArray? {
        val halves = s.split("::")
        if (halves.size > 2) return null
        val head = groups(halves[0], allowV4Tail = halves.size == 1) ?: return null
        val tail = if (halves.size == 2) groups(halves[1], allowV4Tail = true) ?: return null else emptyList()
        val units = head + tail
        val total = units.sumOf { it.size }
        if (halves.size == 1 && total != IPV6_GROUPS * 2) return null
        if (halves.size == 2 && total > (IPV6_GROUPS - 1) * 2) return null
        val out = ByteArray(IPV6_GROUPS * 2)
        var i = 0
        head.forEach { g -> g.forEach { out[i++] = it } }
        i = out.size - tail.sumOf { it.size }
        tail.forEach { g -> g.forEach { out[i++] = it } }
        return out
    }

    /** Each element is 2 bytes (a hex group) or 4 bytes (a trailing dotted IPv4). */
    private fun groups(part: String, allowV4Tail: Boolean): List<ByteArray>? {
        if (part.isEmpty()) return emptyList()
        val items = part.split(':')
        return items.mapIndexed { idx, g ->
            when {
                HEX_GROUP.matches(g) -> g.toInt(HEX).let {
                    byteArrayOf((it shr BYTE_BITS).toByte(), (it and BYTE_MASK).toByte())
                }
                allowV4Tail && idx == items.lastIndex && '.' in g -> ipv4(g) ?: return null
                else -> return null
            }
        }
    }

    private fun unmap(b: ByteArray): ByteArray {
        val mapped = (0 until MAPPED_PREFIX_BYTES).all { b[it] == 0.toByte() } &&
            (b[MAPPED_PREFIX_BYTES].toInt() and BYTE_MASK) == MAPPED_MARKER &&
            (b[MAPPED_PREFIX_BYTES + 1].toInt() and BYTE_MASK) == MAPPED_MARKER
        return if (mapped) b.copyOfRange(b.size - IPV4_BYTES, b.size) else b
    }

    /** An IP literal or CIDR (`addr/bits`); null for anything else, including hostnames. */
    class Cidr(val network: ByteArray, val bits: Int) {
        val isV6: Boolean get() = network.size > IPV4_BYTES

        fun contains(address: ByteArray): Boolean {
            if (address.size != network.size) return false
            val full = bits / BYTE_BITS
            if ((0 until full).any { address[it] != network[it] }) return false
            val rest = bits % BYTE_BITS
            if (rest == 0) return true
            val mask = (BYTE_MASK shl (BYTE_BITS - rest)) and BYTE_MASK
            return (address[full].toInt() and mask) == (network[full].toInt() and mask)
        }

        companion object {
            private val BITS = Regex("^[0-9]{1,3}$")

            fun parse(entry: String): Cidr? {
                val e = entry.trim()
                val slash = e.indexOf('/')
                val ip = IpLiteral.parse(if (slash >= 0) e.substring(0, slash) else e) ?: return null
                val max = ip.size * BYTE_BITS
                val bits = if (slash >= 0) {
                    e.substring(slash + 1).takeIf { BITS.matches(it) }?.toInt() ?: return null
                } else {
                    max
                }
                return if (bits in 0..max) Cidr(ip, bits) else null
            }
        }
    }
}
