// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Outcome of checking one DPoP proof (RFC 9449) against one request — ADR-0320 P2, ADR-0383 D2.
 * Exactly one value is [VALID]; every other value is a rejection and the caller answers 401 with
 * `WWW-Authenticate: DPoP error="invalid_dpop_proof"` (or `invalid_token` for [JKT_MISMATCH]).
 */
enum class DpopResult {
    VALID,
    MISSING_PROOF,
    MALFORMED,
    BAD_SIGNATURE,
    ALG_NOT_ALLOWED,
    HTM_MISMATCH,
    HTU_MISMATCH,
    IAT_OUT_OF_WINDOW,
    ATH_MISMATCH,
    JKT_MISMATCH,
    REPLAYED,
    PRIVATE_KEY_IN_JWK,
}

/**
 * A DPoP proof JWT after JOSE parsing, before any decision. Produced by a [DpopProofParser]; every
 * field is nullable because the verifier, not the parser, decides what an absent value means.
 *
 * @property signatureValid true only if the JWS signature verified with the PUBLIC key in the
 *   `jwk` header under [alg]. A parser must leave it false when [alg] is not in
 *   [DpopProofVerifier.ALLOWED_ALGORITHMS] or when [jwkHasPrivateMaterial] is true.
 * @property jwkThumbprint RFC 7638 SHA-256 thumbprint (base64url, unpadded) of the `jwk` header.
 */
data class DpopProof(
    val typ: String?,
    val alg: String?,
    val jwkThumbprint: String?,
    val jwkHasPrivateMaterial: Boolean,
    val signatureValid: Boolean,
    val htm: String?,
    val htu: String?,
    val iat: Instant?,
    val jti: String?,
    val ath: String?,
)

/** JOSE front-end port. Implementations must never throw: anything unparsable is `null`. */
fun interface DpopProofParser {
    fun parse(compactJws: String): DpopProof?
}

/**
 * `jti` replay port. Shaped for a `SET key 1 NX PX ttl` store: [firstUse] returns true exactly once
 * per (`jkt`, `jti`) within [ttl], and false on every later call — including when the store cannot
 * answer, so an unavailable store rejects (fail closed).
 */
fun interface DpopReplayStore {
    suspend fun firstUse(jkt: String, jti: String, ttl: Duration): Boolean
}

/**
 * Freshness window for `iat`. A proof is accepted when `now - maxAge <= iat <= now + futureSkew`.
 * Replay entries are kept for `maxAge + futureSkew`, the whole span a proof can be accepted in.
 */
data class DpopPolicy(
    val futureSkew: Duration = Duration.ofSeconds(DEFAULT_SKEW_SECONDS),
    val maxAge: Duration = Duration.ofSeconds(DEFAULT_MAX_AGE_SECONDS),
) {
    init {
        require(!futureSkew.isNegative) { "futureSkew must not be negative" }
        require(!maxAge.isNegative && !maxAge.isZero) { "maxAge must be positive" }
    }

    val replayTtl: Duration get() = maxAge.plus(futureSkew)

    private companion object {
        const val DEFAULT_SKEW_SECONDS = 60L
        const val DEFAULT_MAX_AGE_SECONDS = 300L
    }
}

/**
 * The request a proof must be bound to.
 *
 * @property dpopHeaderValues every `DPoP` header value on the request; more than one is MALFORMED.
 * @property requestUri the EXTERNAL absolute URI the client called (behind a proxy, the forwarded
 *   one). Query and fragment are ignored, per RFC 9449 §4.3.
 * @property boundJkt the access token's `cnf.jkt`; null or blank never verifies.
 */
data class DpopRequest(
    val dpopHeaderValues: List<String>,
    val method: String,
    val requestUri: String,
    val accessToken: String,
    val boundJkt: String?,
)

/**
 * RFC 9449 resource-server proof check. Pure: the only I/O is the [replayStore] port and the only
 * clock is the `now` argument. Checks run cheapest-and-structural first and the replay claim runs
 * LAST, so a proof rejected for any other reason never consumes its `jti`.
 */
class DpopProofVerifier(
    private val parser: DpopProofParser,
    private val replayStore: DpopReplayStore,
    private val policy: DpopPolicy = DpopPolicy(),
) {
    suspend fun verify(request: DpopRequest, now: Instant): DpopResult {
        val values = request.dpopHeaderValues
        if (values.isEmpty() || values.all { it.isBlank() }) return DpopResult.MISSING_PROOF
        if (values.size != 1) return DpopResult.MALFORMED
        val proof = parser.parse(values.single().trim()) ?: return DpopResult.MALFORMED
        return structural(proof) ?: binding(proof, request, now) ?: replay(proof)
    }

    private fun structural(proof: DpopProof): DpopResult? = when {
        proof.typ != TYP -> DpopResult.MALFORMED
        proof.alg !in ALLOWED_ALGORITHMS -> DpopResult.ALG_NOT_ALLOWED
        proof.jwkHasPrivateMaterial -> DpopResult.PRIVATE_KEY_IN_JWK
        proof.jwkThumbprint.isNullOrBlank() -> DpopResult.MALFORMED
        proof.jti.isNullOrBlank() || proof.jti.length > MAX_JTI_LENGTH -> DpopResult.MALFORMED
        !proof.signatureValid -> DpopResult.BAD_SIGNATURE
        else -> null
    }

    private fun binding(proof: DpopProof, request: DpopRequest, now: Instant): DpopResult? {
        val expectedHtu = normaliseHtu(request.requestUri)
        val iat = proof.iat
        return when {
            proof.htm == null || proof.htm != request.method -> DpopResult.HTM_MISMATCH
            expectedHtu == null || proof.htu == null || normaliseHtu(proof.htu) != expectedHtu ->
                DpopResult.HTU_MISMATCH
            iat == null || iat.isAfter(now.plus(policy.futureSkew)) || iat.isBefore(now.minus(policy.maxAge)) ->
                DpopResult.IAT_OUT_OF_WINDOW
            !constantTimeEquals(proof.ath, accessTokenHash(request.accessToken)) -> DpopResult.ATH_MISMATCH
            !constantTimeEquals(proof.jwkThumbprint, request.boundJkt) -> DpopResult.JKT_MISMATCH
            else -> null
        }
    }

    private suspend fun replay(proof: DpopProof): DpopResult {
        val first = replayStore.firstUse(checkNotNull(proof.jwkThumbprint), checkNotNull(proof.jti), policy.replayTtl)
        return if (first) DpopResult.VALID else DpopResult.REPLAYED
    }

    companion object {
        const val TYP = "dpop+jwt"

        /** Asymmetric only: no `none`, no HMAC (RFC 9449 §4.2). */
        val ALLOWED_ALGORITHMS: Set<String> = setOf("ES256", "EdDSA", "PS256")

        private const val MAX_JTI_LENGTH = 256
        private const val HTTP_DEFAULT_PORT = 80
        private const val HTTPS_DEFAULT_PORT = 443
        private val HTU_SCHEMES = setOf("https", "http")

        /** `ath` value for [accessToken]: base64url(SHA-256(ASCII token)), unpadded. Null if blank. */
        fun accessTokenHash(accessToken: String): String? {
            if (accessToken.isBlank()) return null
            val digest = MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray(StandardCharsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        /**
         * RFC 9449 §4.3 comparison form: absolute http(s) URI, scheme and host lower-cased, default
         * port dropped, empty path as `/`, query and fragment removed. Null when not comparable.
         */
        fun normaliseHtu(uri: String): String? {
            val parsed = try {
                URI(uri.trim()).normalize()
            } catch (_: URISyntaxException) {
                return null
            }
            val scheme = parsed.scheme?.lowercase()
            val host = parsed.host?.lowercase()
            if (scheme !in HTU_SCHEMES || host.isNullOrEmpty() || parsed.rawUserInfo != null) return null
            val defaultPort = if (scheme == "https") HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT
            val port = parsed.port.takeUnless { it == -1 || it == defaultPort }
            val path = parsed.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/"
            return buildString {
                append(scheme).append("://").append(host)
                if (port != null) append(':').append(port)
                append(path)
            }
        }

        private fun constantTimeEquals(a: String?, b: String?): Boolean {
            if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
            return MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))
        }
    }
}
