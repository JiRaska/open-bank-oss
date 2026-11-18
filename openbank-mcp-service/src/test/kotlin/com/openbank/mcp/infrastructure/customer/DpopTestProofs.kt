// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.customer

import com.openbank.libs.security.DpopProofVerifier
import org.jose4j.jwk.EcJwkGenerator
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jwk.PublicJsonWebKey
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwt.JwtClaims
import org.jose4j.jwt.NumericDate
import org.jose4j.keys.EllipticCurves
import org.jose4j.lang.HashUtil
import java.time.Instant
import java.util.UUID

/** Real RFC 9449 proofs, signed with jose4j, for the customer-channel tests. */
object DpopTestProofs {
    const val RESOURCE = "https://mcp.test.example/mcp"
    const val ISSUER = "https://kc.test.example/realms/openbank-customers"
    const val STAFF_ISSUER = "https://kc.test.example/realms/openbank"
    const val ACCESS_TOKEN = "customer-access-token-under-test"

    fun newKey(): PublicJsonWebKey = EcJwkGenerator.generateJwk(EllipticCurves.P256)

    fun jkt(key: PublicJsonWebKey): String = key.calculateBase64urlEncodedThumbprint(HashUtil.SHA_256)

    fun proof(
        key: PublicJsonWebKey,
        htu: String = RESOURCE,
        htm: String = "POST",
        iat: Instant = Instant.now(),
        jti: String = UUID.randomUUID().toString(),
        token: String = ACCESS_TOKEN,
    ): String {
        val claims = JwtClaims()
        claims.setStringClaim("htm", htm)
        claims.setStringClaim("htu", htu)
        claims.issuedAt = NumericDate.fromSeconds(iat.epochSecond)
        claims.jwtId = jti
        claims.setStringClaim("ath", DpopProofVerifier.accessTokenHash(token))
        val jws = JsonWebSignature()
        jws.payload = claims.toJson()
        jws.algorithmHeaderValue = AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256
        jws.setHeader("typ", "dpop+jwt")
        jws.headers.setObjectHeaderValue("jwk", key.toParams(JsonWebKey.OutputControlLevel.PUBLIC_ONLY))
        jws.key = key.privateKey
        return jws.compactSerialization
    }
}
