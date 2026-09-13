// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.crypto

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.jose4j.jwk.EcJwkGenerator
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import org.jose4j.keys.EllipticCurves
import org.junit.jupiter.api.Test
import java.security.PrivateKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class TrustedListServiceTest {

    private val mapper = ObjectMapper()
    private val testClock: Clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)
    private val anchor = EcJwkGenerator.generateJwk(EllipticCurves.P256).also { it.keyId = "anchor-1" }
    private val attacker = EcJwkGenerator.generateJwk(EllipticCurves.P256).also { it.keyId = "anchor-1" }
    private val pidIssuerKey = EcJwkGenerator.generateJwk(EllipticCurves.P256).also { it.keyId = "pid-1" }

    private fun signedList(
        signer: PrivateKey = anchor.privateKey,
        exp: Long? = Instant.now(testClock).epochSecond + 3600,
    ): String {
        val issuers = mapper.createArrayNode().add(
            mapper.createObjectNode().apply {
                put("iss", "https://pid-issuer.cz")
                set<com.fasterxml.jackson.databind.JsonNode>(
                    "jwks",
                    mapper.readTree("""{"keys":[${pidIssuerKey.toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY)}]}"""),
                )
            },
        )
        val payload = mapper.createObjectNode().apply {
            put("iss", "https://lotl.scheme.eu")
            put("iat", Instant.now(testClock).epochSecond)
            exp?.let { put("exp", it) }
            set<com.fasterxml.jackson.databind.JsonNode>("trusted_issuers", issuers)
        }
        return JsonWebSignature().apply {
            this.payload = mapper.writeValueAsString(payload)
            key = signer
            keyIdHeaderValue = anchor.keyId
            setHeader("typ", "trustedlist+jwt")
            algorithmHeaderValue = AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256
        }.compactSerialization
    }

    private fun service(
        inlineList: String?,
        withAnchor: Boolean = true,
    ): Triple<TrustedListService, RefreshableTrustStore, WorkflowLivenessRecorder> {
        val store = mockk<RefreshableTrustStore>(relaxed = true)
        val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
        val metrics = mockk<DomainMetrics> {
            every { registerWorkflowLiveness(any(), any()) } returns liveness
        }
        val anchorJwks = if (withAnchor) {
            Optional.of("""{"keys":[${anchor.toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY)}]}""")
        } else {
            Optional.empty()
        }
        val svc = TrustedListService(
            url = Optional.empty(),
            inline = Optional.ofNullable(inlineList),
            anchorJwksJson = anchorJwks,
            trustStore = store,
            objectMapper = mapper,
            clock = testClock,
            domainMetrics = metrics,
        )
        svc.registerLiveness()
        return Triple(svc, store, liveness)
    }

    @Test
    fun `a list signed by the trust anchor is verified and its issuers pushed to the trust store`() {
        val (svc, store, liveness) = service(signedList())
        svc.refresh()
        val json = slot<String>()
        verify { store.replaceDynamicTrust(capture(json)) }
        assertThat(json.captured).contains("https://pid-issuer.cz")
        verify { liveness.recordSuccess() }
    }

    @Test
    fun `a list signed by the wrong key is rejected and the trust store is NOT updated`() {
        val (svc, store, liveness) = service(signedList(signer = attacker.privateKey))
        svc.refresh()
        verify(exactly = 0) { store.replaceDynamicTrust(any()) }
        verify(exactly = 0) { liveness.recordSuccess() }
    }

    @Test
    fun `an expired list is rejected`() {
        val (svc, store, _) = service(signedList(exp = Instant.now(testClock).epochSecond - 7200))
        svc.refresh()
        verify(exactly = 0) { store.replaceDynamicTrust(any()) }
    }

    @Test
    fun `a list with no exp is rejected (must be time-bounded)`() {
        val (svc, store, _) = service(signedList(exp = null))
        svc.refresh()
        verify(exactly = 0) { store.replaceDynamicTrust(any()) }
    }

    @Test
    fun `a list with no configured trust anchor is refused (fail-closed)`() {
        val (svc, store, _) = service(signedList(), withAnchor = false)
        svc.refresh()
        verify(exactly = 0) { store.replaceDynamicTrust(any()) }
    }

    @Test
    fun `no source configured is inert (static config only)`() {
        val (svc, store, _) = service(inlineList = null)
        svc.refresh()
        verify(exactly = 0) { store.replaceDynamicTrust(any()) }
    }
}
