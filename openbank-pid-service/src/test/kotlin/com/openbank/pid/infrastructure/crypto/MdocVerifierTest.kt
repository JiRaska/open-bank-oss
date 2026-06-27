// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.crypto

import com.openbank.pid.application.port.out.PidVerificationException
import com.upokecenter.cbor.CBORObject
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jose4j.base64url.Base64Url
import org.jose4j.jwk.EcJwkGenerator
import org.jose4j.jwk.EllipticCurveJsonWebKey
import org.jose4j.keys.EllipticCurves
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * mdoc crypto: a self-contained issuer mints a REAL ISO 18013-5 IssuerSigned (CBOR + COSE_Sign1 over
 * the MSO) and the verifier accepts it (round-trip), so the CBOR/COSE implementation is proven against
 * a genuine signature + digest binding — no wallet, no boot.
 */
class MdocVerifierTest {

    private val issuer = EcJwkGenerator.generateJwk(EllipticCurves.P256).also { it.keyId = "mdoc-issuer" }
    private val attacker = EcJwkGenerator.generateJwk(EllipticCurves.P256).also { it.keyId = "mdoc-attacker" }
    private val ns = "eu.europa.ec.eudi.pid.1"
    private val testClock: Clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)

    private fun verifier(trusted: EllipticCurveJsonWebKey): MdocVerifier {
        val keys = mockk<TrustedIssuerKeys>()
        every { keys.allTrustedKeys() } returns listOf(trusted.publicKey)
        return MdocVerifier(keys, testClock)
    }

    private fun taggedItem(digestId: Int, name: String, value: String): CBORObject {
        val item = CBORObject.NewMap().apply {
            Add("digestID", digestId)
            Add("random", ByteArray(SALT_LEN) { it.toByte() })
            Add("elementIdentifier", name)
            Add("elementValue", value)
        }
        return CBORObject.FromObjectAndTag(CBORObject.FromObject(item.EncodeToBytes()), TAG_24)
    }

    /** Mint a base64url IssuerSigned mdoc. [tamper] flips a disclosed value AFTER digesting (forgery). */
    private fun issuedMdoc(
        signer: PrivateKey = issuer.privateKey,
        validUntil: Instant = Instant.now(testClock).plusSeconds(86_400),
        tamper: Boolean = false,
        includeValidity: Boolean = true,
    ): String {
        val items = listOf(
            taggedItem(0, "given_name", "Mads"),
            taggedItem(1, "family_name", "Mdoc"),
            taggedItem(2, "birth_date", "1985-12-31"),
        )
        val sha = MessageDigest.getInstance("SHA-256")
        val digests = CBORObject.NewMap()
        items.forEachIndexed { i, item -> digests.Add(i, CBORObject.FromObject(sha.digest(item.EncodeToBytes()))) }
        val mso = CBORObject.NewMap().apply {
            Add("version", "1.0")
            Add("digestAlgorithm", "SHA-256")
            Add("docType", ns)
            Add("valueDigests", CBORObject.NewMap().Add(ns, digests))
            if (includeValidity) {
                Add(
                    "validityInfo",
                    CBORObject.NewMap()
                        .Add("signed", Instant.now(testClock).toString())
                        .Add("validFrom", Instant.now(testClock).toString())
                        .Add("validUntil", validUntil.toString()),
                )
            }
        }
        val payload = CBORObject.FromObjectAndTag(CBORObject.FromObject(mso.EncodeToBytes()), TAG_24).EncodeToBytes()
        val protectedHeader = CBORObject.NewMap().Add(1, COSE_ES256).EncodeToBytes()
        val sigStructure = CBORObject.NewArray().apply {
            Add("Signature1")
            Add(protectedHeader)
            Add(ByteArray(0))
            Add(payload)
        }.EncodeToBytes()
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(signer)
            update(sigStructure)
            sign()
        }
        val issuerAuth = CBORObject.NewArray().apply {
            Add(protectedHeader)
            Add(CBORObject.NewMap())
            Add(payload)
            Add(signature)
        }
        val disclosed = if (tamper) {
            items.toMutableList().apply {
                this[0] = taggedItem(0, "given_name", "Mallory")
            }
        } else {
            items
        }
        val nameSpaces = CBORObject.NewMap().Add(ns, CBORObject.NewArray().apply { disclosed.forEach { Add(it) } })
        val issuerSigned = CBORObject.NewMap().apply {
            Add("nameSpaces", nameSpaces)
            Add("issuerAuth", issuerAuth)
        }
        return Base64Url.encode(issuerSigned.EncodeToBytes())
    }

    @Test
    fun `a genuinely-signed mdoc from a trusted issuer verifies and yields the PID claims`() {
        val claims = verifier(issuer).verify(issuedMdoc())
        assertThat(claims.givenName).isEqualTo("Mads")
        assertThat(claims.familyName).isEqualTo("Mdoc")
        assertThat(claims.birthDate.toString()).isEqualTo("1985-12-31")
        assertThat(claims.levelOfAssurance).isEqualTo("HIGH")
    }

    @Test
    fun `an mdoc signed by an untrusted key is rejected`() {
        assertThatThrownBy { verifier(issuer).verify(issuedMdoc(signer = attacker.privateKey)) }
            .isInstanceOf(PidVerificationException::class.java)
            .hasMessageContaining("not trusted")
    }

    @Test
    fun `an expired mdoc is rejected`() {
        val expired = issuedMdoc(validUntil = Instant.now(testClock).minusSeconds(7200))
        assertThatThrownBy { verifier(issuer).verify(expired) }
            .isInstanceOf(PidVerificationException::class.java)
            .hasMessageContaining("expired")
    }

    @Test
    fun `an mdoc with no validityInfo is rejected (must be time-bounded)`() {
        assertThatThrownBy { verifier(issuer).verify(issuedMdoc(includeValidity = false)) }
            .isInstanceOf(PidVerificationException::class.java)
            .hasMessageContaining("validityInfo")
    }

    @Test
    fun `a tampered element is not bound to valueDigests and its claim is dropped`() {
        // given_name's disclosed value is swapped after digesting → its digest no longer matches → dropped.
        assertThatThrownBy { verifier(issuer).verify(issuedMdoc(tamper = true)) }
            .isInstanceOf(PidVerificationException::class.java)
            .hasMessageContaining("given_name")
    }

    private companion object {
        const val TAG_24 = 24
        const val COSE_ES256 = -7
        const val SALT_LEN = 16
    }
}
