// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import java.security.SecureRandom
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The audit-window key for a caller with no token subject (#16847): a keyed hash of the client's
 * NETWORK, not of its exact address.
 *
 * - IPv4 is keyed by the full address (/32); IPv4-mapped IPv6 is IPv4 ([IpLiteral]).
 * - IPv6 is keyed by its /64 prefix: one client controls a whole /64, so keying by the full
 *   address would let it rotate addresses and earn one audit row per address.
 * - HMAC-SHA256 under a key drawn at boot, so the in-memory key is not a dictionary-reversible
 *   address hash. Keys are only compared within one process lifetime, so a per-boot key suffices.
 *
 * The input is chosen by [ClientAddressResolver] — never Quarkus' leftmost `X-Forwarded-For`.
 * Anything that is not a strict IP literal keys as [UNKNOWN]; nothing here can reach DNS.
 */
object ClientAddressKey {
    const val UNKNOWN = "ip:unknown"
    private const val IPV6_PREFIX_BYTES = 8
    private const val IPV6_ALLOCATION_BYTES = 6
    private const val IPV4_ALLOCATION_BYTES = 3
    private const val V4_ALLOCATION: Byte = 24
    private const val V6_ALLOCATION: Byte = 48
    private const val KEY_BYTES = 32
    private const val ALGORITHM = "HmacSHA256"
    private const val V4: Byte = 4
    private const val V6: Byte = 6
    private val bootKey = SecretKeySpec(ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes), ALGORITHM)

    fun of(address: String?): String {
        val bytes = IpLiteral.parse(address) ?: return UNKNOWN
        val network = if (bytes.size > V4) {
            byteArrayOf(V6) + bytes.copyOf(IPV6_PREFIX_BYTES)
        } else {
            byteArrayOf(V4) + bytes
        }
        return hash("ip:", network)
    }

    /**
     * The key of the ALLOCATION the address sits in — IPv6 /48, IPv4 /24 — which is what one party
     * can cheaply hold many networks of. [ClientAddressResolver] caps distinct [of] keys per
     * allocation, so one allocation cannot spend the global per-minute key budget (#16847 follow-up).
     */
    fun allocationOf(address: String?): String {
        val bytes = IpLiteral.parse(address) ?: return UNKNOWN
        val network = if (bytes.size > V4) {
            byteArrayOf(V6_ALLOCATION) + bytes.copyOf(IPV6_ALLOCATION_BYTES)
        } else {
            byteArrayOf(V4_ALLOCATION) + bytes.copyOf(IPV4_ALLOCATION_BYTES)
        }
        return hash("net:", network)
    }

    private fun hash(prefix: String, network: ByteArray): String {
        val mac = Mac.getInstance(ALGORITHM).apply { init(bootKey) }
        return prefix + HexFormat.of().formatHex(mac.doFinal(network))
    }
}
