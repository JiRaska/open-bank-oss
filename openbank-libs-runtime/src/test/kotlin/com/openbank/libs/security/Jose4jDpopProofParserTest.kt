// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jose4j.jwk.EcJwkGenerator
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jwk.PublicJsonWebKey
import org.jose4j.jwk.RsaJwkGenerator
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwt.JwtClaims
import org.jose4j.jwt.NumericDate
import org.jose4j.keys.EllipticCurves
import org.jose4j.keys.HmacKey
import org.jose4j.lang.HashUtil
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** End to end through real JOSE: each [DpopResult] is produced from a real compact JWS. */
class Jose4jDpopProofParserTest {
    private val now = Instant.parse("2026-11-17T10:00:00Z")
    private val token = "access-token-value"
    private val uri = "https://api.example.org/mcp"
    private val ecKey: PublicJsonWebKey = EcJwkGenerator.generateJwk(EllipticCurves.P256)
    private val parser = Jose4jDpopProofParser()

    private fun proof(
        key: PublicJsonWebKey = ecKey,
        alg: String = AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256,
        typ: String? = "dpop+jwt",
        headerJwk: Map<String, Any>? = key.toParams(JsonWebKey.OutputControlLevel.PUBLIC_ONLY),
        htm: String = "POST",
        htu: String = uri,
        iat: Instant = now,
        jti: String? = UUID.randomUUID().toString(),
        ath: String? = DpopProofVerifier.accessTokenHash(token),
        signingKey: java.security.Key = key.privateKey,
    ): String {
        val claims = JwtClaims()
        claims.setStringClaim("htm", htm)
        claims.setStringClaim("htu", htu)
        claims.issuedAt = NumericDate.fromSeconds(iat.epochSecond)
        jti?.let { claims.jwtId = it }
        ath?.let { claims.setStringClaim("ath", it) }
        val jws = JsonWebSignature()
        jws.payload = claims.toJson()
        jws.algorithmHeaderValue = alg
        typ?.let { jws.setHeader("typ", it) }
        headerJwk?.let { jws.headers.setObjectHeaderValue("jwk", it) }
        jws.key = signingKey
        jws.isDoKeyValidation = false
        return jws.compactSerialization
    }

    private fun jkt(key: PublicJsonWebKey = ecKey) = key.calculateBase64urlEncodedThumbprint(HashUtil.SHA_256)

    private fun verify(
        compact: String?,
        boundJkt: String? = jkt(),
        method: String = "POST",
        requestUri: String = "$uri?cursor=2",
        accessToken: String = token,
        store: DpopReplayStore = InMemoryDpopReplayStore(clock = Clock.fixed(now, ZoneOffset.UTC)),
    ): DpopResult = runBlocking {
        DpopProofVerifier(parser, store).verify(
            DpopRequest(listOfNotNull(compact), method, requestUri, accessToken, boundJkt),
            now,
        )
    }

    @Test
    fun `RFC 9449 section 6_1 thumbprint vector`() {
        val jwk = PublicJsonWebKey.Factory.newPublicJwk(
            """{"kty":"EC","x":"l8tFrhx-34tV3hRICRDY9zCkDlpBhF42UQUfWVAWBFs",""" +
                """"y":"9VE4jf_Ok_o64zbTTlcuNJajHmt6v9TDVrU0CdvGRDA","crv":"P-256"}""",
        )
        assertThat(jkt(jwk)).isEqualTo("0ZcOCORZNYy-DWpqq30jZyJGHTN0d2HglBV3uiguA4I")
    }

    @Test
    fun `known-positive for each allowed algorithm`() {
        assertThat(verify(proof())).isEqualTo(DpopResult.VALID)
        val edPair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val ed = PublicJsonWebKey.Factory.newPublicJwk(edPair.public).apply { privateKey = edPair.private }
        assertThat(
            verify(proof(key = ed, alg = AlgorithmIdentifiers.EDDSA), boundJkt = jkt(ed)),
        ).isEqualTo(DpopResult.VALID)
        val rsa = RsaJwkGenerator.generateJwk(RSA_BITS)
        assertThat(verify(proof(key = rsa, alg = AlgorithmIdentifiers.RSA_PSS_USING_SHA256), boundJkt = jkt(rsa)))
            .isEqualTo(DpopResult.VALID)
    }

    @Test
    fun `MISSING_PROOF`() {
        assertThat(verify(null)).isEqualTo(DpopResult.MISSING_PROOF)
    }

    @Test
    fun `MALFORMED for garbage, missing jwk, wrong typ and missing jti`() {
        listOf("not-a-jws", "a.b.c", "....", "e30.e30.").forEach {
            assertThat(verify(it)).describedAs(it).isEqualTo(DpopResult.MALFORMED)
        }
        assertThat(verify(proof(headerJwk = null))).isEqualTo(DpopResult.MALFORMED)
        assertThat(verify(proof(typ = "JWT"))).isEqualTo(DpopResult.MALFORMED)
        assertThat(verify(proof(jti = null))).isEqualTo(DpopResult.MALFORMED)
    }

    @Test
    fun `BAD_SIGNATURE when the payload is altered or signed by another key`() {
        val parts = proof().split('.')
        val forged = JwtClaims.parse(String(Base64.getUrlDecoder().decode(parts[1]))).apply {
            setStringClaim("htm", "GET")
        }
        val tampered =
            parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(forged.toJson().toByteArray()) +
                "." + parts[2]
        assertThat(verify(tampered, method = "GET")).isEqualTo(DpopResult.BAD_SIGNATURE)
        val other = EcJwkGenerator.generateJwk(EllipticCurves.P256)
        assertThat(verify(proof(signingKey = other.privateKey))).isEqualTo(DpopResult.BAD_SIGNATURE)
    }

    @Test
    fun `ALG_NOT_ALLOWED for none and HMAC`() {
        val unsigned = proof().split('.').let { (_, payload, _) ->
            val header = """{"typ":"dpop+jwt","alg":"none","jwk":${ecKey.toJson()}}"""
            Base64.getUrlEncoder().withoutPadding().encodeToString(header.toByteArray()) + "." + payload + "."
        }
        assertThat(verify(unsigned)).isEqualTo(DpopResult.ALG_NOT_ALLOWED)
        val hs = proof(alg = AlgorithmIdentifiers.HMAC_SHA256, signingKey = HmacKey(ByteArray(HMAC_KEY_BYTES) { 7 }))
        assertThat(verify(hs)).isEqualTo(DpopResult.ALG_NOT_ALLOWED)
    }

    @Test
    fun `PRIVATE_KEY_IN_JWK when the header carries d`() {
        val leaked = proof(headerJwk = ecKey.toParams(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE))
        assertThat(verify(leaked)).isEqualTo(DpopResult.PRIVATE_KEY_IN_JWK)
    }

    @Test
    fun `HTM_MISMATCH, HTU_MISMATCH, IAT_OUT_OF_WINDOW, ATH_MISMATCH, JKT_MISMATCH`() {
        assertThat(verify(proof(htm = "GET"))).isEqualTo(DpopResult.HTM_MISMATCH)
        assertThat(verify(proof(htu = "https://api.example.org/other"))).isEqualTo(DpopResult.HTU_MISMATCH)
        assertThat(verify(proof(iat = now.minus(Duration.ofMinutes(6))))).isEqualTo(DpopResult.IAT_OUT_OF_WINDOW)
        assertThat(verify(proof(iat = now.plus(Duration.ofMinutes(2))))).isEqualTo(DpopResult.IAT_OUT_OF_WINDOW)
        assertThat(verify(proof(), accessToken = "another-token")).isEqualTo(DpopResult.ATH_MISMATCH)
        assertThat(verify(proof(ath = null))).isEqualTo(DpopResult.ATH_MISMATCH)
        val other = EcJwkGenerator.generateJwk(EllipticCurves.P256)
        assertThat(verify(proof(), boundJkt = jkt(other))).isEqualTo(DpopResult.JKT_MISMATCH)
    }

    @Test
    fun `REPLAYED on the second presentation of one proof`() {
        val store = InMemoryDpopReplayStore(clock = Clock.fixed(now, ZoneOffset.UTC))
        val compact = proof()
        assertThat(verify(compact, store = store)).isEqualTo(DpopResult.VALID)
        assertThat(verify(compact, store = store)).isEqualTo(DpopResult.REPLAYED)
    }

    private companion object {
        const val RSA_BITS = 2048
        const val HMAC_KEY_BYTES = 32
    }
}
