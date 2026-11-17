// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import org.jose4j.jwa.AlgorithmConstraints
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jwk.PublicJsonWebKey
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwt.JwtClaims
import org.jose4j.lang.HashUtil
import org.jose4j.lang.JoseException
import java.time.Instant

/**
 * jose4j front-end for [DpopProofVerifier] — ADR-0320 P2.
 *
 * jose4j reaches services through `quarkus-oidc`; libs-runtime only compiles against it. Per
 * ADR-0320 rule 1 this is a PLAIN class — no CDI, JAX-RS or Jandex-visible annotation — so a
 * service without jose4j never loads it. A service opts in by constructing it from its own
 * `src/main` (the `@SenderConstrained` interceptor subclass, a later slice).
 *
 * Never throws: every parse or crypto failure yields `null` (MALFORMED) or `signatureValid=false`.
 */
class Jose4jDpopProofParser : DpopProofParser {
    override fun parse(compactJws: String): DpopProof? = try {
        parseOrThrow(compactJws)
    } catch (_: JoseException) {
        null
    } catch (_: org.jose4j.jwt.MalformedClaimException) {
        null
    } catch (_: org.jose4j.jwt.consumer.InvalidJwtException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ClassCastException) {
        null
    }

    private fun parseOrThrow(compactJws: String): DpopProof? {
        if (compactJws.length > MAX_PROOF_LENGTH || compactJws.count { it == '.' } != 2) return null
        val jws = JsonWebSignature()
        jws.setAlgorithmConstraints(AlgorithmConstraints.NO_CONSTRAINTS)
        jws.compactSerialization = compactJws
        val alg = jws.algorithmHeaderValue
        val rawJwk = jws.headers.getObjectHeaderValue(JWK_HEADER) as? Map<*, *> ?: return null
        val hasPrivate = PRIVATE_MEMBERS.any { rawJwk.containsKey(it) }
        val jwk = if (hasPrivate) null else publicJwk(rawJwk) ?: return null
        val claims = JwtClaims.parse(jws.unverifiedPayload)
        return DpopProof(
            typ = jws.getHeader(TYP_HEADER),
            alg = alg,
            jwkThumbprint = jwk?.calculateBase64urlEncodedThumbprint(HashUtil.SHA_256),
            jwkHasPrivateMaterial = hasPrivate,
            signatureValid = jwk != null && alg in DpopProofVerifier.ALLOWED_ALGORITHMS && verify(jws, jwk, alg),
            htm = claims.getStringClaimValue(HTM),
            htu = claims.getStringClaimValue(HTU),
            iat = claims.issuedAt?.let { Instant.ofEpochSecond(it.value) },
            jti = claims.jwtId,
            ath = claims.getStringClaimValue(ATH),
        )
    }

    private fun publicJwk(raw: Map<*, *>): PublicJsonWebKey? {
        val members: Map<String, Any> = raw.entries
            .mapNotNull { (k, v) -> if (k is String && v != null) k to v else null }
            .toMap()
        return JsonWebKey.Factory.newJwk(members) as? PublicJsonWebKey
    }

    private fun verify(jws: JsonWebSignature, jwk: PublicJsonWebKey, alg: String): Boolean = try {
        jws.setAlgorithmConstraints(AlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT, alg))
        jws.key = jwk.publicKey
        jws.verifySignature()
    } catch (_: JoseException) {
        false
    }

    private companion object {
        const val JWK_HEADER = "jwk"
        const val TYP_HEADER = "typ"
        const val HTM = "htm"
        const val HTU = "htu"
        const val ATH = "ath"
        const val MAX_PROOF_LENGTH = 8192

        /** JWK members that only a private or symmetric key carries (RFC 7518 §6.2.2, §6.3.2, §6.4). */
        val PRIVATE_MEMBERS = setOf("d", "p", "q", "dp", "dq", "qi", "oth", "k")
    }
}
