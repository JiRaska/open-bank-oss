// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.infrastructure.openid4vci

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pid.application.port.out.PidVerificationException
import com.openbank.pid.infrastructure.crypto.EudiPresentationVerifierImpl
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jose4j.jwk.EcJwkGenerator
import org.jose4j.jwk.JsonWebKey
import org.jose4j.keys.EllipticCurves
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

/**
 * Issuance crypto: the bank mints a holder-bound PID SD-JWT VC and — the round-trip — the SAME verifier
 * that powers the relying-party flow accepts it. If issuance produced an invalid/unverifiable credential
 * this test fails, so issuer and verifier stay in lock-step.
 */
class CredentialIssuerServiceTest {

    private val mapper = ObjectMapper()
    private val issuerId = "https://test-pid-issuer.openbank.local"
    private val testClock: Clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)
    private val issuerKey = EcJwkGenerator.generateJwk(EllipticCurves.P256).also { it.keyId = "issuer-1" }
    private val holderKey = EcJwkGenerator.generateJwk(EllipticCurves.P256).also { it.keyId = "holder-1" }

    private fun eudiKey(withKey: Boolean = true) = EudiIssuerKey(
        signingKeyJwk = if (withKey) {
            Optional.of(issuerKey.toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE))
        } else {
            Optional.empty()
        },
        issuerId = issuerId,
    )

    private fun statusList(key: EudiIssuerKey) =
        StatusListService(key, mapper, InMemoryStatusListStore(), listId = "1", ttlSeconds = 3600, clock = testClock)

    private fun service(withKey: Boolean = true, status: StatusListService? = null): CredentialIssuerService {
        val key = eudiKey(withKey)
        return CredentialIssuerService(
            key,
            status ?: statusList(key),
            credentialTtlSeconds = 31_536_000,
            objectMapper = mapper,
            clock = testClock,
        )
    }

    private fun verifier() = EudiPresentationVerifierImpl(
        """[{"iss":"$issuerId","jwks":${service().publicJwksJson()}}]""",
        mapper,
        testClock,
    )

    private val claims = OfferedClaims(
        subjectId = "CZ-PID-ISSUED-42",
        givenName = "Eva",
        familyName = "Issued",
        birthdate = "1988-08-08",
    )

    @Test
    fun `an issued PID credential is accepted by the relying-party verifier (issue then verify round-trip)`() =
        runBlocking<Unit> {
            val vc = service().issuePidCredential(claims, holderKey)
            val verified = verifier().verify(vc, null, null)

            assertThat(verified.subjectId).isEqualTo("sub:CZ-PID-ISSUED-42")
            assertThat(verified.givenName).isEqualTo("Eva")
            assertThat(verified.familyName).isEqualTo("Issued")
            assertThat(verified.birthDate.toString()).isEqualTo("1988-08-08")
            assertThat(verified.issuer).isEqualTo(issuerId)
        }

    @Test
    fun `the issued credential is holder-bound to the wallet key (cnf carries the holder jwk)`(): Unit = runBlocking {
        val vc = service().issuePidCredential(claims, holderKey)
        // The issuer JWS payload must embed the holder's PUBLIC key in cnf.jwk (presented later via KB-JWT).
        val payloadJson = String(
            org.jose4j.base64url.Base64Url.decode(vc.substringBefore("~").split(".")[1]),
            Charsets.UTF_8,
        )
        val cnfX = mapper.readTree(payloadJson)["cnf"]["jwk"]["x"].asText()
        val holderX = mapper.readTree(holderKey.toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY))["x"].asText()
        assertThat(cnfX).isEqualTo(holderX)
    }

    @Test
    fun `issuance is disabled and fails closed when no signing key is configured`(): Unit = runBlocking {
        val disabled = service(withKey = false)
        assertThat(disabled.enabled).isFalse()
        assertThatThrownBy { runBlocking { disabled.issuePidCredential(claims, holderKey) } }
            .isInstanceOf(PidVerificationException::class.java)
    }

    @Test
    fun `an issued credential carries a status-list reference that can be revoked`(): Unit = runBlocking {
        val status = statusList(eudiKey())
        val vc = service(status = status).issuePidCredential(claims, holderKey)
        val verified = verifier().verify(vc, null, null)

        // The verifier surfaces the credential's status_list {uri, idx} so the resolver can check it.
        assertThat(verified.statusListUri).isEqualTo(status.statusListUri)
        val index = verified.statusListIndex!!
        assertThat(status.isRevoked(status.statusListUri, index)).isFalse()
        // Revoke that index — the same credential is now reported revoked (signature still valid).
        assertThat(status.revoke(index)).isTrue()
        assertThat(status.isRevoked(status.statusListUri, index)).isTrue()
        // A foreign status-list uri never resolves against our list.
        assertThat(status.isRevoked("https://other.example/status/1", index)).isFalse()
    }

    @Test
    fun `the signed status list token carries the bits=1 status_list claim`(): Unit = runBlocking {
        val status = statusList(eudiKey())
        status.revoke(status.allocate())
        val token = status.statusListToken()
        val payloadJson = String(org.jose4j.base64url.Base64Url.decode(token.split(".")[1]), Charsets.UTF_8)
        val node = mapper.readTree(payloadJson)
        assertThat(node["status_list"]["bits"].asInt()).isEqualTo(1)
        assertThat(node["status_list"]["lst"].asText()).isNotBlank()
        assertThat(node["iss"].asText()).isEqualTo(issuerId)
    }
}
