// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.crypto

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pid.application.port.out.PidVerificationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jose4j.base64url.Base64Url
import org.jose4j.jwk.EcJwkGenerator
import org.jose4j.jwk.EllipticCurveJsonWebKey
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import org.jose4j.keys.EllipticCurves
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Crypto tests for the EUDI SD-JWT VC verifier. A self-contained test issuer signs a REAL ES256 PID
 * credential, so verification exercises genuine signature + disclosure-hash checks — no wallet, no boot.
 */
class EudiPresentationVerifierImplTest {

    private val mapper = ObjectMapper()
    private val issuerId = "https://test-issuer.openbank.local"
    private val testClock: Clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)

    /** A self-contained EUDI PID issuer: real EC P-256 key, issues genuinely-signed SD-JWT VCs. */
    private class TestIssuer(val iss: String, val mapper: ObjectMapper, val clock: Clock) {
        val jwk: EllipticCurveJsonWebKey = EcJwkGenerator.generateJwk(EllipticCurves.P256).also { it.keyId = "test-1" }

        /** The wallet holder's key — its public half goes into the VC `cnf`; it signs the KB-JWT. */
        val holderJwk: EllipticCurveJsonWebKey =
            EcJwkGenerator.generateJwk(EllipticCurves.P256).also { it.keyId = "holder-1" }

        fun publicJwksJson(): String = """{"keys":[${jwk.toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY)}]}"""

        /** Build a PID SD-JWT VC: name/birthdate selectively disclosed, sub/iss/exp in the signed JWT. */
        fun issuePidVc(
            sub: String = "CZ-PID-0001",
            given: String = "Jan",
            family: String = "Novak",
            birthdate: String = "1976-05-06",
            exp: Long = Instant.now(clock).epochSecond + 3600,
            signingKey: java.security.PrivateKey = jwk.privateKey,
            sdAlg: String? = "sha-256",
            bindHolder: Boolean = false,
        ): String {
            val disclosures = listOf(
                disclosure("given_name", given),
                disclosure("family_name", family),
                disclosure("birthdate", birthdate),
            )
            val sha = MessageDigest.getInstance("SHA-256")
            val sd = disclosures.map { Base64Url.encode(sha.digest(it.toByteArray(Charsets.US_ASCII))) }
            val payload = mapper.createObjectNode().apply {
                put("iss", iss)
                put("vct", "eu.europa.ec.eudi.pid.1")
                put("sub", sub)
                put("iat", Instant.now(clock).epochSecond)
                put("exp", exp)
                put("issuing_country", "CZ")
                sdAlg?.let { put("_sd_alg", it) }
                if (bindHolder) {
                    set<com.fasterxml.jackson.databind.JsonNode>(
                        "cnf",
                        mapper.createObjectNode().set(
                            "jwk",
                            mapper.readTree(holderJwk.toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY)),
                        ),
                    )
                }
                set<com.fasterxml.jackson.databind.JsonNode>(
                    "_sd",
                    mapper.createArrayNode().apply { sd.forEach { add(it) } },
                )
            }
            val jws = JsonWebSignature().apply {
                this.payload = mapper.writeValueAsString(payload)
                key = signingKey
                keyIdHeaderValue = jwk.keyId
                algorithmHeaderValue = AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256
            }
            return jws.compactSerialization + "~" + disclosures.joinToString("~") + "~"
        }

        /** A wallet key-binding JWT signed by the holder key, binding the RP's nonce + audience. */
        fun keyBindingJwt(
            nonce: String,
            audience: String,
            signingKey: java.security.PrivateKey = holderJwk.privateKey,
        ): String {
            val payload = mapper.createObjectNode().apply {
                put("nonce", nonce)
                put("aud", audience)
                put("iat", Instant.now(clock).epochSecond)
            }
            return JsonWebSignature().apply {
                this.payload = mapper.writeValueAsString(payload)
                key = signingKey
                algorithmHeaderValue = AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256
            }.compactSerialization
        }

        private fun disclosure(name: String, value: String): String {
            val arr = mapper.createArrayNode().apply {
                add("salt_$name")
                add(name)
                add(value)
            }
            return Base64Url.encode(mapper.writeValueAsString(arr).toByteArray(Charsets.UTF_8))
        }
    }

    private fun verifier(trustedIssuer: TestIssuer?): EudiPresentationVerifierImpl {
        val trustJson = if (trustedIssuer == null) {
            "[]"
        } else {
            """[{"iss":"${trustedIssuer.iss}","jwks":${trustedIssuer.publicJwksJson()}}]"""
        }
        return EudiPresentationVerifierImpl(trustJson, mapper, testClock)
    }

    @Test
    fun `a genuinely-signed PID credential from a trusted issuer verifies and yields the claims`() {
        val issuer = TestIssuer(issuerId, mapper, testClock)
        val claims = verifier(issuer).verify(issuer.issuePidVc(), null, null)

        assertThat(claims.subjectId).isEqualTo("sub:CZ-PID-0001")
        assertThat(claims.givenName).isEqualTo("Jan")
        assertThat(claims.familyName).isEqualTo("Novak")
        assertThat(claims.birthDate.toString()).isEqualTo("1976-05-06")
        assertThat(claims.issuingCountry).isEqualTo("CZ")
        assertThat(claims.levelOfAssurance).isEqualTo("HIGH")
    }

    @Test
    fun `an untrusted issuer is rejected (empty trust store fails closed)`() {
        val issuer = TestIssuer(issuerId, mapper, testClock)
        assertThatThrownBy { verifier(null).verify(issuer.issuePidVc(), null, null) }
            .isInstanceOf(PidVerificationException::class.java)
            .hasMessageContaining("issuer")
    }

    @Test
    fun `a credential signed by a different key than the trusted one is rejected`() {
        val trusted = TestIssuer(issuerId, mapper, testClock)
        val attacker = TestIssuer(issuerId, mapper, testClock) // same iss, different key
        // present an attacker-signed VC but trust only the genuine issuer's public key
        assertThatThrownBy { verifier(trusted).verify(attacker.issuePidVc(), null, null) }
            .isInstanceOf(PidVerificationException::class.java)
            .hasMessageContaining("signature")
    }

    @Test
    fun `an expired credential is rejected`() {
        val issuer = TestIssuer(issuerId, mapper, testClock)
        val expired = issuer.issuePidVc(exp = Instant.now(testClock).epochSecond - 7200)
        assertThatThrownBy { verifier(issuer).verify(expired, null, null) }
            .isInstanceOf(PidVerificationException::class.java)
            .hasMessageContaining("expired")
    }

    @Test
    fun `a tampered disclosure is not bound to _sd and its claim is dropped`() {
        val issuer = TestIssuer(issuerId, mapper, testClock)
        val vc = issuer.issuePidVc()
        // Replace the first disclosure with a forged one (valid base64url JSON, wrong hash → unbound).
        val forged = Base64Url.encode("""["salt_x","given_name","Mallory"]""".toByteArray(Charsets.UTF_8))
        val parts = vc.split("~").toMutableList()
        parts[1] = forged
        val tampered = parts.joinToString("~")
        // given_name's real disclosure is gone and the forged one is unbound → missing claim → reject.
        assertThatThrownBy { verifier(issuer).verify(tampered, null, null) }
            .isInstanceOf(PidVerificationException::class.java)
            .hasMessageContaining("given_name")
    }

    @Test
    fun `a credential declaring an unsupported _sd_alg is rejected`() {
        val issuer = TestIssuer(issuerId, mapper, testClock)
        // Genuinely signed, but declares a digest we don't implement (forward-compat eIDAS ARF guard).
        val vc = issuer.issuePidVc(sdAlg = "sha-512")
        assertThatThrownBy { verifier(issuer).verify(vc, null, null) }
            .isInstanceOf(PidVerificationException::class.java)
            .hasMessageContaining("_sd_alg")
    }

    // ── OpenID4VP holder key-binding (anti-replay) — the path the cross-device flow relies on ──

    @Test
    fun `a holder-bound presentation with a matching key-binding JWT verifies under a nonce`() {
        val issuer = TestIssuer(issuerId, mapper, testClock)
        val vc = issuer.issuePidVc(bindHolder = true)
        val kb = issuer.keyBindingJwt(nonce = "nonce-1", audience = "openbank-pid")
        val claims = verifier(issuer).verify("$vc$kb", "nonce-1", "openbank-pid")
        assertThat(claims.subjectId).isEqualTo("sub:CZ-PID-0001")
    }

    @Test
    fun `a key-binding JWT carrying the wrong nonce is rejected (replay defence)`() {
        val issuer = TestIssuer(issuerId, mapper, testClock)
        val vc = issuer.issuePidVc(bindHolder = true)
        val kb = issuer.keyBindingJwt(nonce = "stale-nonce", audience = "openbank-pid")
        assertThatThrownBy { verifier(issuer).verify("$vc$kb", "nonce-1", "openbank-pid") }
            .isInstanceOf(PidVerificationException::class.java)
            .hasMessageContaining("nonce")
    }

    @Test
    fun `a nonce-bound verification with no key-binding JWT is rejected`() {
        val issuer = TestIssuer(issuerId, mapper, testClock)
        // Holder-bound credential but the wallet supplied no KB-JWT (trailing ~) while a nonce is required.
        assertThatThrownBy { verifier(issuer).verify(issuer.issuePidVc(bindHolder = true), "nonce-1", "openbank-pid") }
            .isInstanceOf(PidVerificationException::class.java)
            .hasMessageContaining("key-binding")
    }

    @Test
    fun `a key-binding JWT signed by a non-holder key is rejected`() {
        val issuer = TestIssuer(issuerId, mapper, testClock)
        val attacker = TestIssuer(issuerId, mapper, testClock)
        val vc = issuer.issuePidVc(bindHolder = true)
        // KB-JWT signed by the attacker's holder key, not the one bound in the VC cnf.
        val kb = issuer.keyBindingJwt("nonce-1", "openbank-pid", signingKey = attacker.holderJwk.privateKey)
        assertThatThrownBy { verifier(issuer).verify("$vc$kb", "nonce-1", "openbank-pid") }
            .isInstanceOf(PidVerificationException::class.java)
            .hasMessageContaining("key-binding signature")
    }
}
