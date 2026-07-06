// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.openid4vci

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jose4j.jwk.EcJwkGenerator
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jws.JsonWebSignature
import org.jose4j.keys.EllipticCurves
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

/**
 * Unit tests for [StatusListService] itself (ADR-0094 Token Status List), independent of the
 * [CredentialIssuerService] round-trip test — the allocate/revoke delegation to the store, the
 * fail-closed [StatusListService.enabled] flag, and the published URI/metadata shape.
 */
class StatusListServiceTest {

    private val mapper = ObjectMapper()
    private val issuerId = "https://test-pid-issuer.openbank.local"
    private val testClock: Clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)
    private val issuerKeyPair = EcJwkGenerator.generateJwk(EllipticCurves.P256).also { it.keyId = "issuer-1" }

    private fun eudiKey(withKey: Boolean = true) = EudiIssuerKey(
        signingKeyJwk = if (withKey) {
            Optional.of(issuerKeyPair.toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE))
        } else {
            Optional.empty()
        },
        issuerId = issuerId,
    )

    private fun service(
        withKey: Boolean = true,
        listId: String = "1",
        ttlSeconds: Long = 3600,
    ) = StatusListService(eudiKey(withKey), mapper, InMemoryStatusListStore(), listId, ttlSeconds, testClock)

    @Test
    fun `enabled mirrors whether an issuer signing key is configured`() {
        assertThat(service(withKey = true).enabled).isTrue()
        assertThat(service(withKey = false).enabled).isFalse()
    }

    @Test
    fun `statusListUri embeds the issuer id and list id`() {
        val svc = service(listId = "42")
        assertThat(svc.statusListUri).isEqualTo("$issuerId/api/v1/parties/eudi/status-lists/42")
        assertThat(svc.id).isEqualTo("42")
    }

    @Test
    fun `cacheTtlSeconds mirrors the configured ttl`() {
        assertThat(service(ttlSeconds = 900).cacheTtlSeconds).isEqualTo(900L)
    }

    @Test
    fun `allocate delegates to the store and returns increasing indices`(): Unit = runBlocking {
        val svc = service()
        assertThat(svc.allocate()).isZero()
        assertThat(svc.allocate()).isEqualTo(1L)
    }

    @Test
    fun `revoke and isRevoked round-trip through the store`(): Unit = runBlocking {
        val svc = service()
        val idx = svc.allocate()
        assertThat(svc.isRevoked(idx)).isFalse()
        assertThat(svc.revoke(idx)).isTrue()
        assertThat(svc.isRevoked(idx)).isTrue()
    }

    @Test
    fun `revoke of a never-allocated index fails`(): Unit = runBlocking {
        assertThat(service().revoke(999L)).isFalse()
    }

    @Test
    fun `CredentialStatusPort isRevoked only resolves for OUR statusListUri`(): Unit = runBlocking {
        val svc = service()
        val idx = svc.allocate()
        svc.revoke(idx)

        assertThat(svc.isRevoked(svc.statusListUri, idx)).isTrue()
        assertThat(svc.isRevoked("https://someone-else.example/status/1", idx)).isFalse()
    }

    @Test
    fun `statusListToken is a JWS signed by the issuer key with typ statuslist+jwt`(): Unit = runBlocking {
        val svc = service()
        val token = svc.statusListToken()

        val header = mapper.readTree(
            String(
                org.jose4j.base64url.Base64Url.decode(token.split(".")[0]),
                Charsets.UTF_8,
            ),
        )
        assertThat(header["typ"].asText()).isEqualTo("statuslist+jwt")

        val jws = JsonWebSignature().apply {
            compactSerialization = token
            key = issuerKeyPair.publicKey
        }
        assertThat(jws.verifySignature()).isTrue()
    }

    @Test
    fun `statusListToken carries iat and exp derived from the clock and ttl`(): Unit = runBlocking {
        val svc = service(ttlSeconds = 120)
        val token = svc.statusListToken()
        val payload = mapper.readTree(
            String(org.jose4j.base64url.Base64Url.decode(token.split(".")[1]), Charsets.UTF_8),
        )
        val expectedIat = testClock.instant().epochSecond
        assertThat(payload["iat"].asLong()).isEqualTo(expectedIat)
        assertThat(payload["exp"].asLong()).isEqualTo(expectedIat + 120)
        assertThat(payload["ttl"].asLong()).isEqualTo(120L)
        assertThat(payload["sub"].asText()).isEqualTo(svc.statusListUri)
    }
}
