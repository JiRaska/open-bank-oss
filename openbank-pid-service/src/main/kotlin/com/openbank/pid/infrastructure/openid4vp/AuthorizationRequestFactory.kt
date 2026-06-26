// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.infrastructure.openid4vp

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pid.infrastructure.openid4vci.EudiIssuerKey
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant

/**
 * Builds the OpenID4VP authorization request a wallet consumes (eIDAS 2.0 / ADR-0094).
 *
 * Cross-device profile: `response_type=vp_token`, `response_mode=direct_post` — the wallet POSTs its
 * presentation to [responseUri]. The request carries a single-use [nonce] (anti-replay; the wallet
 * binds it into the key-binding JWT) and a `state` (the transaction id, echoed back so the RP can
 * correlate the out-of-band response). [audience] (== [clientId]) is what the verifier enforces on
 * the KB-JWT `aud`.
 *
 * Two delivery modes: when an issuer signing key is configured, the authorization request is a
 * **signed Request Object** (JAR / RFC 9101) the wallet fetches via `request_uri` and verifies against
 * the issuer JWKS — tamper-evident, required by stricter wallets. Otherwise the parameters are inlined
 * in the `openid4vp://` URI (still functional, but unsigned). The presentation_definition (DIF) requests
 * exactly the PID core attributes pid resolves on — given_name, family_name, birthdate.
 */
@ApplicationScoped
class AuthorizationRequestFactory(
    @ConfigProperty(name = "openbank.pid.eudi.client-id", defaultValue = "openbank-pid")
    val clientId: String,
    @ConfigProperty(
        name = "openbank.pid.eudi.response-uri",
        defaultValue = "http://localhost:8105/api/v1/parties/eudi/presentation-responses",
    )
    private val responseUri: String,
    @ConfigProperty(
        name = "openbank.pid.eudi.request-object-uri-base",
        defaultValue = "http://localhost:8105/api/v1/parties/eudi/request-objects",
    )
    private val requestObjectUriBase: String,
    @ConfigProperty(name = "openbank.pid.eudi.request-object-ttl-seconds", defaultValue = "300")
    private val requestObjectTtlSeconds: Long,
    private val issuerKey: EudiIssuerKey,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    /** The audience the wallet must bind into its key-binding JWT (`aud`) — this RP's client id. */
    val audience: String get() = clientId

    /** True when the RP can serve a signed Request Object (an issuer key is configured). */
    val signedRequestsAvailable: Boolean get() = issuerKey.enabled

    fun buildAuthorizationRequestUri(transactionId: String, nonce: String): String = if (issuerKey.enabled) {
        // request_uri mode: the wallet dereferences the signed Request Object (JAR / RFC 9101).
        "openid4vp://authorize?client_id=${encode(clientId)}" +
            "&request_uri=${encode("$requestObjectUriBase/$transactionId")}"
    } else {
        val params = linkedMapOf(
            "client_id" to clientId,
            "response_type" to "vp_token",
            "response_mode" to "direct_post",
            "response_uri" to responseUri,
            "nonce" to nonce,
            "state" to transactionId,
            "presentation_definition" to objectMapper.writeValueAsString(presentationDefinition(transactionId)),
        )
        "openid4vp://authorize?" + params.entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
    }

    /**
     * The signed Request Object (JAR) for [transactionId] — a JWT carrying the full authorization
     * request, signed by the issuer key so the wallet can verify it was not tampered with. Null when
     * no issuer key is configured (the inline-URI mode is used instead).
     */
    fun buildRequestObject(transactionId: String, nonce: String): String? {
        if (!issuerKey.enabled) return null
        val now = Instant.now(clock).epochSecond
        val payload = objectMapper.createObjectNode().apply {
            put("iss", clientId)
            // EUDIW ARF convention for an RP→wallet Request Object: aud is the self-issued wallet iss.
            put("aud", "https://self-issued.me/v2")
            put("jti", java.util.UUID.randomUUID().toString())
            put("client_id", clientId)
            put("response_type", "vp_token")
            put("response_mode", "direct_post")
            put("response_uri", responseUri)
            put("nonce", nonce)
            put("state", transactionId)
            put("iat", now)
            put("exp", now + requestObjectTtlSeconds)
            set<com.fasterxml.jackson.databind.JsonNode>(
                "presentation_definition",
                presentationDefinition(transactionId),
            )
        }
        return issuerKey.sign(objectMapper.writeValueAsString(payload), typ = "oauth-authz-req+jwt")
    }

    private fun presentationDefinition(id: String) = objectMapper.createObjectNode().apply {
        put("id", "pid-$id")
        set<com.fasterxml.jackson.databind.JsonNode>(
            "input_descriptors",
            objectMapper.createArrayNode().add(
                objectMapper.createObjectNode().apply {
                    put("id", "eu.europa.ec.eudi.pid.1")
                    set<com.fasterxml.jackson.databind.JsonNode>(
                        "format",
                        objectMapper.createObjectNode().set(
                            "vc+sd-jwt",
                            objectMapper.createObjectNode().set<com.fasterxml.jackson.databind.JsonNode>(
                                "sd-jwt_alg_values",
                                objectMapper.createArrayNode().add("ES256").add("EdDSA"),
                            ),
                        ),
                    )
                    set<com.fasterxml.jackson.databind.JsonNode>(
                        "constraints",
                        objectMapper.createObjectNode().set(
                            "fields",
                            objectMapper.createArrayNode().apply {
                                PID_CLAIMS.forEach { claim -> add(field(claim)) }
                            },
                        ),
                    )
                },
            ),
        )
    }

    private fun field(claim: String) = objectMapper.createObjectNode().apply {
        set<com.fasterxml.jackson.databind.JsonNode>(
            "path",
            objectMapper.createArrayNode().add("$.$claim"),
        )
        put("intent_to_retain", false)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        val PID_CLAIMS = listOf("given_name", "family_name", "birthdate")
    }
}
