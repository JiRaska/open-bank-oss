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
import java.time.Duration
import java.time.Instant
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
        store: DpopReplayStore = InMemoryDpopReplayStore(),
        at: Instant = now,
    ): DpopResult = runBlocking {
        DpopProofVerifier(parser, store).verify(
            DpopRequest(listOfNotNull(compact), method, requestUri, accessToken, boundJkt),
            at,
        )
    }

    private fun b64(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    /** Sign arbitrary header/payload JSON text (keeps duplicate members, odd types) with [ecKey]. */
    private fun rawSigned(header: String, payload: String, alg: String = "SHA256withECDSA"): String {
        val input = b64(header) + "." + b64(payload)
        val der = java.security.Signature.getInstance(alg).run {
            initSign(ecKey.privateKey)
            update(input.toByteArray())
            sign()
        }
        val concat = org.jose4j.jws.EcdsaUsingShaAlgorithm.convertDerToConcatenated(der, EC_P256_SIG_HALF)
        return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(concat)
    }

    private fun goodHeader() = """{"typ":"dpop+jwt","alg":"ES256","jwk":${ecKey.toJson()}}"""

    private fun goodPayload(extra: String = "") =
        """{"htm":"POST","htu":"$uri","iat":${now.epochSecond},"jti":"${UUID.randomUUID()}",""" +
            """"ath":"${DpopProofVerifier.accessTokenHash(token)}"$extra}"""

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
        val store = InMemoryDpopReplayStore()
        val compact = proof()
        assertThat(verify(compact, store = store)).isEqualTo(DpopResult.VALID)
        assertThat(verify(compact, store = store)).isEqualTo(DpopResult.REPLAYED)
    }

    @Test
    fun `raw signing helper produces a VALID proof (control for the raw negatives)`() {
        assertThat(verify(rawSigned(goodHeader(), goodPayload()))).isEqualTo(DpopResult.VALID)
    }

    @Test
    fun `replay boundary - iat at +60 accepted, replay at exactly +360 is REPLAYED`() {
        val store = InMemoryDpopReplayStore()
        val compact = proof(iat = now.plusSeconds(60))
        assertThat(verify(compact, store = store)).isEqualTo(DpopResult.VALID)
        assertThat(verify(compact, store = store, at = now.plusSeconds(360))).isEqualTo(DpopResult.REPLAYED)
    }

    @Test
    fun `store exhaustion surfaces as REPLAY_STORE_UNAVAILABLE`() {
        val store = InMemoryDpopReplayStore(maxEntries = 1)
        assertThat(verify(proof(), store = store)).isEqualTo(DpopResult.VALID)
        assertThat(verify(proof(), store = store)).isEqualTo(DpopResult.REPLAY_STORE_UNAVAILABLE)
    }

    @Test
    fun `key must suit the algorithm`() {
        // jose4j will not SIGN ES256 with a non-P-256 key, so these carry a real P-256 signature
        // under a header whose jwk is the wrong key type or curve.
        val signature = proof().substringAfterLast('.')
        fun es256WithJwk(jwk: PublicJsonWebKey) =
            b64("""{"typ":"dpop+jwt","alg":"ES256","jwk":${jwk.toJson()}}""") + "." + b64(goodPayload()) + "." +
                signature
        val rsa = RsaJwkGenerator.generateJwk(RSA_BITS)
        assertThat(verify(es256WithJwk(rsa), boundJkt = jkt(rsa)))
            .describedAs("ES256 header + RSA key").isEqualTo(DpopResult.BAD_SIGNATURE)
        val p384 = EcJwkGenerator.generateJwk(EllipticCurves.P384)
        assertThat(verify(es256WithJwk(p384), boundJkt = jkt(p384)))
            .describedAs("ES256 + P-384 key").isEqualTo(DpopResult.BAD_SIGNATURE)
        assertThat(verify(proof(alg = AlgorithmIdentifiers.RSA_PSS_USING_SHA256, signingKey = rsa.privateKey)))
            .describedAs("PS256 header + EC key").isEqualTo(DpopResult.BAD_SIGNATURE)
        val weak = RsaJwkGenerator.generateJwk(WEAK_RSA_BITS)
        assertThat(verify(proof(key = weak, alg = AlgorithmIdentifiers.RSA_PSS_USING_SHA256), boundJkt = jkt(weak)))
            .describedAs("RSA < 2048").isEqualTo(DpopResult.BAD_SIGNATURE)
        val edPair = java.security.KeyPairGenerator.getInstance("Ed448").generateKeyPair()
        val ed448 = PublicJsonWebKey.Factory.newPublicJwk(edPair.public).apply { privateKey = edPair.private }
        assertThat(verify(proof(key = ed448, alg = AlgorithmIdentifiers.EDDSA), boundJkt = jkt(ed448)))
            .describedAs("Ed448 under EdDSA").isEqualTo(DpopResult.BAD_SIGNATURE)
    }

    @Test
    fun `crit header is MALFORMED`() {
        val header = """{"typ":"dpop+jwt","alg":"ES256","crit":["exp"],"exp":1,"jwk":${ecKey.toJson()}}"""
        assertThat(verify(rawSigned(header, goodPayload()))).isEqualTo(DpopResult.MALFORMED)
    }

    @Test
    fun `duplicate header or claim members are MALFORMED`() {
        val dupHeader = """{"typ":"dpop+jwt","alg":"ES256","alg":"ES256","jwk":${ecKey.toJson()}}"""
        assertThat(verify(rawSigned(dupHeader, goodPayload()))).isEqualTo(DpopResult.MALFORMED)
        assertThat(verify(rawSigned(goodHeader(), goodPayload(""","htm":"GET"""")))).isEqualTo(DpopResult.MALFORMED)
    }

    @Test
    fun `non-string htm or htu and non-numeric iat are MALFORMED`() {
        val base = """"jti":"${UUID.randomUUID()}","ath":"${DpopProofVerifier.accessTokenHash(token)}""""
        listOf(
            """{"htm":1,"htu":"$uri","iat":${now.epochSecond},$base}""",
            """{"htm":"POST","htu":["$uri"],"iat":${now.epochSecond},$base}""",
            """{"htm":"POST","htu":"$uri","iat":"${now.epochSecond}",$base}""",
        ).forEach { assertThat(verify(rawSigned(goodHeader(), it))).describedAs(it).isEqualTo(DpopResult.MALFORMED) }
    }

    @Test
    fun `off-curve EC jwk is never VALID`() {
        val params = ecKey.toParams(JsonWebKey.OutputControlLevel.PUBLIC_ONLY).toMutableMap()
        val y = java.math.BigInteger(
            1,
            Base64.getUrlDecoder().decode(params["y"] as String),
        ).add(java.math.BigInteger.ONE)
        val yBytes = y.toByteArray().let {
            if (it.size >
                EC_COORD_BYTES
            ) {
                it.copyOfRange(it.size - EC_COORD_BYTES, it.size)
            } else {
                it
            }
        }
        params["y"] =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(EC_COORD_BYTES - yBytes.size) + yBytes)
        val header = """{"typ":"dpop+jwt","alg":"ES256","jwk":${org.jose4j.json.JsonUtil.toJson(params)}}"""
        assertThat(verify(rawSigned(header, goodPayload()), boundJkt = null))
            .isIn(DpopResult.MALFORMED, DpopResult.BAD_SIGNATURE)
    }

    private companion object {
        const val RSA_BITS = 2048
        const val HMAC_KEY_BYTES = 32
        const val WEAK_RSA_BITS = 1024
        const val EC_P256_SIG_HALF = 32
        const val EC_COORD_BYTES = 32
    }
}
