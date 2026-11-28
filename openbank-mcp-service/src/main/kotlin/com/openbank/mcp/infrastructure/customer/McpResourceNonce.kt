// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.mcp.infrastructure.customer

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Optional
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authenticates nonce challenges shared with the dedicated Keycloak read grant. */
internal class McpResourceNonce(secret: ByteArray) {
    private val key = SecretKeySpec(secret, ALGORITHM)
    private val random = SecureRandom()

    init {
        require(secret.size >= MIN_KEY_BYTES) { "MCP resource nonce key is too short" }
    }

    fun refreshIfRequired(nonce: String?, resource: String, thumbprint: String, now: Instant): String? {
        val fields = nonce?.takeIf { it.length <= MAX_NONCE_LENGTH }?.split('.')
        if (fields != null && valid(fields, resource, thumbprint, now.epochSecond)) return null
        val entropy = ByteArray(CHALLENGE_BYTES)
        random.nextBytes(entropy)
        val prefix = "v1.${now.epochSecond + TTL_SECONDS}.${BASE64.encodeToString(entropy)}"
        return "$prefix.${tag(resource, thumbprint, prefix)}"
    }

    private fun valid(fields: List<String>, resource: String, thumbprint: String, now: Long): Boolean {
        if (fields.size != FIELD_COUNT) return false
        val (version, expiry, challenge) = fields
        val providedTag = fields.last()
        if (version != "v1") return false
        val expires = expiry.toLongOrNull() ?: return false
        if (expires.toString() != expiry || expires !in (now + 1)..(now + MAX_FUTURE_SECONDS)) return false
        if (!CHALLENGE.matches(challenge) || !TAG.matches(providedTag)) return false
        val expected = tag(resource, thumbprint, "$version.$expiry.$challenge")
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            providedTag.toByteArray(Charsets.US_ASCII),
        )
    }

    private fun tag(resource: String, thumbprint: String, prefix: String): String {
        val input = listOf("mcp-resource-nonce/v1", resource, thumbprint, prefix).joinToString("\n")
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(key)
        return BASE64.encodeToString(mac.doFinal(input.toByteArray(Charsets.UTF_8)))
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val MIN_KEY_BYTES = 32
        const val CHALLENGE_BYTES = 16
        const val TTL_SECONDS = 60L
        const val MAX_FUTURE_SECONDS = 120L
        const val FIELD_COUNT = 4
        const val MAX_NONCE_LENGTH = 84
        val BASE64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val CHALLENGE = Regex("^[A-Za-z0-9_-]{22}$")
        val TAG = Regex("^[A-Za-z0-9_-]{43}$")
    }
}

@ApplicationScoped
class McpResourceNonceSettings(
    @ConfigProperty(name = "mcp.customer.bound-balance.enabled", defaultValue = "false") private val enabled: Boolean,
    @ConfigProperty(name = "mcp.customer.bound-balance.nonce-key") private val encodedKey: Optional<String>,
) {
    internal fun policy(): McpResourceNonce? {
        if (!enabled) return null
        val key = encodedKey.filter(String::isNotBlank).orElseThrow {
            IllegalStateException("bound balance delegation requires an MCP nonce key")
        }
        return McpResourceNonce(Base64.getUrlDecoder().decode(key))
    }
}
