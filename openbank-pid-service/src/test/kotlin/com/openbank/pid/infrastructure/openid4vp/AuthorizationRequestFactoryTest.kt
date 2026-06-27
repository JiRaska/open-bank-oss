// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.openid4vp

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pid.infrastructure.openid4vci.EudiIssuerKey
import org.assertj.core.api.Assertions.assertThat
import org.jose4j.jwk.EcJwkGenerator
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jws.JsonWebSignature
import org.jose4j.keys.EllipticCurves
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class AuthorizationRequestFactoryTest {

    private val mapper = ObjectMapper()
    private val issuerKey = EcJwkGenerator.generateJwk(EllipticCurves.P256).also { it.keyId = "rp-1" }
    private val testClock: Clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)
    private val responseUri = "https://pid.example/api/v1/parties/eudi/presentation-responses"

    private fun factory(withKey: Boolean) = AuthorizationRequestFactory(
        clientId = "openbank-pid",
        responseUri = responseUri,
        requestObjectUriBase = "https://pid.example/api/v1/parties/eudi/request-objects",
        requestObjectTtlSeconds = 300,
        issuerKey = EudiIssuerKey(
            signingKeyJwk = if (withKey) {
                Optional.of(issuerKey.toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE))
            } else {
                Optional.empty()
            },
            issuerId = "openbank-pid",
        ),
        objectMapper = mapper,
        clock = testClock,
    )

    private fun params(uri: String): Map<String, String> {
        assertThat(uri).startsWith("openid4vp://authorize?")
        return uri.substringAfter("?").split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }
    }

    @Test
    fun `without an issuer key the request inlines the cross-device direct_post parameters`() {
        val p = params(factory(withKey = false).buildAuthorizationRequestUri("tx-42", "nonce-xyz"))
        assertThat(p["client_id"]).isEqualTo("openbank-pid")
        assertThat(p["response_type"]).isEqualTo("vp_token")
        assertThat(p["response_mode"]).isEqualTo("direct_post")
        assertThat(p["response_uri"]).isEqualTo(responseUri)
        assertThat(p["nonce"]).isEqualTo("nonce-xyz")
        assertThat(p["state"]).isEqualTo("tx-42")
        val pd = mapper.readTree(p["presentation_definition"])
        val paths = pd["input_descriptors"][0]["constraints"]["fields"].map { it["path"][0].asText() }
        assertThat(paths).containsExactlyInAnyOrder("$.given_name", "$.family_name", "$.birthdate")
    }

    @Test
    fun `with an issuer key the request uses request_uri mode (no inline params)`() {
        val f = factory(withKey = true)
        assertThat(f.signedRequestsAvailable).isTrue()
        val p = params(f.buildAuthorizationRequestUri("tx-7", "nonce-7"))
        assertThat(p["client_id"]).isEqualTo("openbank-pid")
        assertThat(p["request_uri"]).isEqualTo("https://pid.example/api/v1/parties/eudi/request-objects/tx-7")
        assertThat(p).doesNotContainKey("presentation_definition")
        assertThat(p).doesNotContainKey("nonce")
    }

    @Test
    fun `the signed Request Object is a JWS the wallet can verify, carrying the full request`() {
        val jwt = factory(withKey = true).buildRequestObject("tx-7", "nonce-7")!!
        // Verify the JWS signature against the issuer public key (what a wallet does via the issuer JWKS).
        val jws = JsonWebSignature().apply {
            compactSerialization = jwt
            key = issuerKey.publicKey
        }
        assertThat(jws.verifySignature()).isTrue()
        val payload = mapper.readTree(jws.payload)
        assertThat(payload["client_id"].asText()).isEqualTo("openbank-pid")
        assertThat(payload["response_mode"].asText()).isEqualTo("direct_post")
        assertThat(payload["nonce"].asText()).isEqualTo("nonce-7")
        assertThat(payload["state"].asText()).isEqualTo("tx-7")
        assertThat(payload["response_uri"].asText()).isEqualTo(responseUri)
        assertThat(payload.has("presentation_definition")).isTrue()
        assertThat(payload.has("exp")).isTrue()
    }

    @Test
    fun `no signed request object without an issuer key`() {
        val f = factory(withKey = false)
        assertThat(f.signedRequestsAvailable).isFalse()
        assertThat(f.buildRequestObject("tx", "n")).isNull()
    }
}
