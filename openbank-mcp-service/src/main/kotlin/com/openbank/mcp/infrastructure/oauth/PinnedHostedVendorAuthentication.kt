// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.DpopReplayStore
import com.openbank.libs.security.ReplayClaim
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.TokenAuthentication
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import org.jose4j.jwa.AlgorithmConstraints
import org.jose4j.jwk.JsonWebKeySet
import org.jose4j.jws.JsonWebSignature
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

/** Authentication configuration is supplied by the bank, never from incoming client metadata. */
internal class PinnedHostedVendorAuthentication(
    chatGptJwks: ByteArray,
    chatGptSha256: String,
    private val mapper: ObjectMapper,
    private val replayStore: DpopReplayStore,
    private val clock: Clock,
    private val claude: ClaudeCredential? = null,
) : HostedVendorAuthentication {
    private val chatGptKey = parsePinnedKey(chatGptJwks, chatGptSha256)

    class ClaudeCredential(secret: String, val expiresAt: Instant) {
        init {
            require(secret.length in MIN_CLAUDE_SECRET_LENGTH..MAX_CLAUDE_SECRET_LENGTH) {
                "Invalid sandbox OAuth client secret"
            }
            require(!expiresAt.isAfter(CLAUDE_EXCEPTION_END)) { "Sandbox client exception expired" }
        }

        private val expected = sha256(secret)

        fun matches(proof: String): Boolean = MessageDigest.isEqual(expected, sha256(proof))
    }

    override suspend fun authenticate(credentials: HostedVendorAuthentication.Credentials): Vendor? =
        when (credentials.clientId) {
            Vendor.CHATGPT.clientId -> authenticateChatGpt(credentials)
            Vendor.CLAUDE.clientId -> authenticateClaude(credentials)
            else -> null
        }

    private suspend fun authenticateChatGpt(credentials: HostedVendorAuthentication.Credentials): Vendor? {
        if (credentials.method != TokenAuthentication.PRIVATE_KEY_JWT) return null
        if (credentials.proof.length !in MIN_ASSERTION_LENGTH..MAX_ASSERTION_LENGTH) return null
        val verified = runCatching { verifyAssertion(credentials.proof) }.getOrNull() ?: return null
        val now = clock.instant()
        val jti = verifiedJti(verified, now) ?: return null
        val replay = replayStore.firstUse("hosted-oauth:chatgpt", HostedOAuthStart.digest(jti), REPLAY_LIFETIME, now)
        return Vendor.CHATGPT.takeIf { replay == ReplayClaim.FIRST_USE }
    }

    private fun authenticateClaude(credentials: HostedVendorAuthentication.Credentials): Vendor? {
        val approved = claude ?: return null
        if (credentials.method !in Vendor.CLAUDE.tokenAuthentication) return null
        if (credentials.proof.length !in 1..MAX_CLAUDE_SECRET_LENGTH) return null
        if (!clock.instant().isBefore(approved.expiresAt) || !approved.matches(credentials.proof)) return null
        return Vendor.CLAUDE
    }

    private fun verifyAssertion(assertion: String): JsonNode {
        val jws = JsonWebSignature()
        jws.setAlgorithmConstraints(AlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT, "RS256"))
        jws.compactSerialization = assertion
        check(jws.keyIdHeaderValue == chatGptKey.first)
        jws.key = chatGptKey.second
        check(jws.verifySignature())
        return mapper.readTree(jws.payload)
    }

    private fun verifiedJti(claims: JsonNode, now: Instant): String? {
        if (claims.path("iss").textValue() != Vendor.CHATGPT.clientId) return null
        if (claims.path("sub").textValue() != Vendor.CHATGPT.clientId) return null
        val aud = claims.path("aud")
        val audience = if (aud.isArray && aud.size() == 1) aud[0].textValue() else aud.textValue()
        if (audience !in AUDIENCES) return null
        if (!assertionTimingValid(claims, now.epochSecond)) return null
        return claims.path("jti").textValue()?.takeIf(JTI::matches)
    }

    private fun assertionTimingValid(claims: JsonNode, now: Long): Boolean {
        val issued = epoch(claims, "iat") ?: return false
        val expiry = epoch(claims, "exp") ?: return false
        if (issued !in (now - ASSERTION_MAX_AGE)..(now + CLOCK_SKEW)) return false
        if (expiry <= now || expiry <= issued || expiry > issued + ASSERTION_MAX_AGE) return false
        if (!claims.has("nbf")) return true
        return (epoch(claims, "nbf") ?: return false) <= now
    }

    private fun epoch(claims: JsonNode, name: String): Long? =
        claims.path(name).takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()

    private companion object {
        const val MIN_CLAUDE_SECRET_LENGTH = 32
        const val MAX_CLAUDE_SECRET_LENGTH = 1024
        const val MIN_ASSERTION_LENGTH = 100
        const val MAX_ASSERTION_LENGTH = 8192
        const val MIN_RSA_BITS = 2048
        const val ASSERTION_MAX_AGE = 60L
        const val CLOCK_SKEW = 5L
        val AUDIENCES = setOf(HostedOAuthComplete.ISSUER, "${HostedOAuthComplete.ISSUER}/token")
        val JTI = Regex("[A-Za-z0-9._~-]{1,128}")
        val REPLAY_LIFETIME: Duration = Duration.ofSeconds(65)
        val CLAUDE_EXCEPTION_END: Instant = Instant.parse("2026-12-24T00:00:00Z")

        fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())

        fun parsePinnedKey(bytes: ByteArray, expected: String): Pair<String, RSAPublicKey> {
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            require(MessageDigest.isEqual(actual, HexFormat.of().parseHex(expected))) {
                "Unexpected ChatGPT key digest"
            }
            val jwk = JsonWebKeySet(bytes.decodeToString()).jsonWebKeys.single()
            val key = jwk.key as? RSAPublicKey ?: error("Expected RSA verification key")
            require(jwk.algorithm == "RS256" && jwk.use == "sig" && key.modulus.bitLength() >= MIN_RSA_BITS)
            return checkNotNull(jwk.keyId) to key
        }
    }
}
