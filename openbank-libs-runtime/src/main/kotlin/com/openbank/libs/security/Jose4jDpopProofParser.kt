// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import org.jose4j.jwa.AlgorithmConstraints
import org.jose4j.jwk.EllipticCurveJsonWebKey
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jwk.OctetKeyPairJsonWebKey
import org.jose4j.jwk.PublicJsonWebKey
import org.jose4j.jwk.RsaJsonWebKey
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwt.JwtClaims
import org.jose4j.keys.EllipticCurves
import org.jose4j.lang.HashUtil
import java.math.BigInteger
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECFieldFp
import java.time.Instant

/**
 * jose4j front-end for [DpopProofVerifier] — ADR-0320 P2.
 *
 * jose4j reaches services through `quarkus-oidc` (the Quarkus BOM resolves 0.9.6); libs-runtime
 * only compiles against it. Per ADR-0320 rule 1 this is a PLAIN class — no CDI, JAX-RS or
 * Jandex-visible annotation — so a service without jose4j never loads it. A service opts in by
 * constructing it from its own `src/main` (the `@SenderConstrained` interceptor subclass, a later
 * slice).
 *
 * Fail-closed rules on top of RFC 9449:
 * - A `crit` header, duplicate JSON members (rejected by jose4j's JSON parser), a non-string
 *   `htm`/`htu`/`jti`/`ath` or a non-numeric `iat` all parse to `null` (MALFORMED).
 * - The key must suit the algorithm: ES256 needs an on-curve EC P-256 key, PS256 an RSA key of at
 *   least 2048 bits, EdDSA an OKP Ed25519 key (Ed448 is refused). Any mismatch leaves
 *   `signatureValid=false` (BAD_SIGNATURE).
 * - jose4j's algorithm constraints PERMIT only [DpopProofVerifier.ALLOWED_ALGORITHMS] at all times.
 *
 * Never throws: every parse or crypto failure yields `null` or `signatureValid=false`.
 */
class Jose4jDpopProofParser : DpopProofParser {
    // The port contract is "never throws": any jose4j or JCA failure must read as MALFORMED.
    @Suppress("TooGenericExceptionCaught")
    override fun parse(compactJws: String): DpopProof? = try {
        parseOrThrow(compactJws)
    } catch (_: org.jose4j.lang.JoseException) {
        null
    } catch (_: org.jose4j.jwt.MalformedClaimException) {
        null
    } catch (_: org.jose4j.jwt.consumer.InvalidJwtException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun parseOrThrow(compactJws: String): DpopProof? {
        if (compactJws.length > MAX_PROOF_LENGTH || compactJws.count { it == '.' } != 2) return null
        val jws = JsonWebSignature()
        jws.setAlgorithmConstraints(ALLOWED_CONSTRAINTS)
        jws.compactSerialization = compactJws
        if (jws.headers.getObjectHeaderValue(CRIT_HEADER) != null) return null
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
            signatureValid = jwk != null &&
                alg in DpopProofVerifier.ALLOWED_ALGORITHMS &&
                keySuits(alg, jwk) &&
                verify(jws, jwk, alg),
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

    private fun keySuits(alg: String, jwk: PublicJsonWebKey): Boolean = when (alg) {
        "ES256" ->
            jwk is EllipticCurveJsonWebKey &&
                jwk.curveName == EllipticCurves.P_256 &&
                onCurve(jwk.publicKey as ECPublicKey)
        "PS256" -> jwk is RsaJsonWebKey && (jwk.publicKey as RSAPublicKey).modulus.bitLength() >= MIN_RSA_BITS
        "EdDSA" -> jwk is OctetKeyPairJsonWebKey && jwk.subtype == OctetKeyPairJsonWebKey.SUBTYPE_ED25519
        else -> false
    }

    /** y² ≡ x³ + ax + b (mod p): an off-curve point is the invalid-curve attack's input. */
    private fun onCurve(key: ECPublicKey): Boolean {
        val params = key.params
        val p = (params.curve.field as? ECFieldFp)?.p ?: return false
        val x = key.w.affineX
        val y = key.w.affineY
        if (!inField(x, p) || !inField(y, p)) return false
        val lhs = y.modPow(TWO, p)
        val rhs = x.modPow(THREE, p).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        return lhs == rhs
    }

    private fun inField(v: BigInteger, p: BigInteger) = v.signum() >= 0 && v < p

    private fun verify(jws: JsonWebSignature, jwk: PublicJsonWebKey, alg: String): Boolean = try {
        jws.setAlgorithmConstraints(AlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT, alg))
        jws.key = jwk.publicKey
        jws.verifySignature()
    } catch (_: org.jose4j.lang.JoseException) {
        false
    }

    private companion object {
        const val JWK_HEADER = "jwk"
        const val TYP_HEADER = "typ"
        const val CRIT_HEADER = "crit"
        const val HTM = "htm"
        const val HTU = "htu"
        const val ATH = "ath"
        const val MAX_PROOF_LENGTH = 8192
        const val MIN_RSA_BITS = 2048
        val TWO: BigInteger = BigInteger.TWO
        val THREE: BigInteger = BigInteger.valueOf(3)

        val ALLOWED_CONSTRAINTS = AlgorithmConstraints(
            AlgorithmConstraints.ConstraintType.PERMIT,
            "ES256",
            "EdDSA",
            "PS256",
        ).also { check(DpopProofVerifier.ALLOWED_ALGORITHMS == setOf("ES256", "EdDSA", "PS256")) }

        /** JWK members that only a private or symmetric key carries (RFC 7518 §6.2.2, §6.3.2, §6.4). */
        val PRIVATE_MEMBERS = setOf("d", "p", "q", "dp", "dq", "qi", "oth", "k")
    }
}
