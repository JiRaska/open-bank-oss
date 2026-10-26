// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.ok
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jwk.PublicJsonWebKey
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import java.time.Instant

/**
 * WireMock stand-in for the eIDAS Trusted List endpoint (ADR-0094) the
 * [com.openbank.pid.infrastructure.crypto.TrustedListService] pulls through the SSRF-safe
 * SafeHttpClient egress path (ADR-0320 P1: host allow-listed with the dynamic port, DNS pinned,
 * plain http flagged).
 *
 * The scheme operator's anchor key lives in [PidE2EKeys] (persisted, so every classloader phase
 * sees the SAME key); [serveSigned] emits a properly anchor-signed `trustedlist+jwt`,
 * [serveTampered] a list signed by the WRONG key (must never apply), so the suite can prove both
 * the widening refresh and the fail-closed keep-current-trust behaviour on the real HTTP wire.
 * The request journal doubles as the refresh liveness observation.
 *
 * This resource also delivers ALL key-derived configuration to the application through its
 * config map — signing key, static trust roots, anchor JWKS, allow-list — because the config
 * values must be byte-identical with the key material the test classes sign with, and the
 * profile's getConfigOverrides() runs in a different classloader phase (see [PidE2EKeys]).
 */
class PidE2EWireMockResource : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        val server = WireMockServer(WireMockConfiguration.options().dynamicPort())
        // The boot-time refresh fetches immediately at application start, so the INITIAL list must
        // be in place before the first stub answers: wallet B enters trust exclusively through
        // this signed list (the static config layer carries wallet A and the bank only).
        currentList = signList(listOf(PidE2EKeys.WALLET_B_ISS to PidE2EKeys.walletB), PidE2EKeys.anchorKey)
        server.start()
        // Track the mapping so restub() can REMOVE it: WireMock 3.x serves the OLDEST matching
        // stub, so a swap that only adds a new mapping silently keeps serving the boot list
        // (measured: the widened list with wallet C never reached the refresher).
        currentStub = server.stubFor(get(LIST_PATH).willReturn(ok(currentList)))
        Companion.server = server
        val port = server.port()
        return mapOf(
            "openbank.pid.eudi.trusted-list.url" to "http://localhost:$port$LIST_PATH",
            // ADR-0320 P1 egress allow-list: plain HTTP to loopback on THIS WireMock port only.
            // Without the explicit port the policy allows just the scheme default (80), and every
            // fetch is denied PORT_NOT_ALLOWED (measured during this suite's first red run).
            "openbank.pid.eudi.trusted-list.allowed-hosts" to "localhost:$port;http;private",
            "openbank.pid.eudi.trusted-list.anchor-jwks" to PidE2EKeys.anchorJwksJson(),
            // The bank as a PID issuer: a real EC P-256 signing JWK so EudiIssuerKey is enabled and
            // the issuance / status-list / request-object paths sign for real (fail-closed OFF).
            "openbank.pid.eudi.issuer.signing-key-jwk" to PidE2EKeys.bankIssuerPrivateJwkJson,
            "openbank.pid.eudi.issuer.issuer-id" to PidE2EKeys.BANK_ISSUER_ID,
            // Wallet issuer A enters through the STATIC trust layer; wallet B through the dynamic
            // signed list above — so the suite proves both trust paths and their merge semantics.
            "openbank.pid.eudi.trusted-issuers-json" to PidE2EKeys.staticTrustJson,
        )
    }

    override fun stop() {
        server?.stop()
        server = null
    }

    companion object {
        private const val LIST_PATH = "/e2e/trusted-list.jwt"
        private const val LIST_TTL_SECONDS = 3600L
        private val mapper = ObjectMapper()
        private var server: WireMockServer? = null
        private var currentList: String = ""
        private var currentStub: StubMapping? = null

        /** Install a properly anchor-signed list (no WireMock restart, keeps the journal intact). */
        fun serveSigned(vararg issuers: Pair<String, PublicJsonWebKey>) {
            currentList = signList(issuers.toList(), PidE2EKeys.anchorKey)
            restub()
        }

        /** A well-formed list signed by the WRONG key — the refresh must reject it (fail-closed). */
        fun serveTampered(vararg issuers: Pair<String, PublicJsonWebKey>) {
            currentList = signList(issuers.toList(), PidE2EKeys.walletC)
            restub()
        }

        fun listRequests(): Int = server?.findAll(getRequestedFor(urlEqualTo(LIST_PATH)))?.size ?: 0

        private fun restub() {
            val active = server ?: error("WireMock not running")
            // Remove the previous mapping explicitly — relying on match-order for an in-place swap
            // is version-sensitive, and a stale winning stub here would silently serve the OLD list
            // (observed: widening never applied, wallet C stayed untrusted for the full 30 s).
            currentStub?.let(active::removeStub)
            currentStub = active.stubFor(get(LIST_PATH).willReturn(ok(currentList)))
        }

        private fun signList(issuers: List<Pair<String, PublicJsonWebKey>>, signingKey: PublicJsonWebKey): String {
            val now = Instant.now().epochSecond
            val payload = mapper.createObjectNode().apply {
                put("iss", "e2e-scheme-operator")
                put("iat", now)
                // exp is MANDATORY for the verifier — a list with no expiry is rejected outright.
                put("exp", now + LIST_TTL_SECONDS)
                set<JsonNode>(
                    "trusted_issuers",
                    mapper.createArrayNode().apply {
                        issuers.forEach { (iss, key) ->
                            add(
                                mapper.createObjectNode().apply {
                                    put("iss", iss)
                                    set<JsonNode>(
                                        "jwks",
                                        mapper.readTree(
                                            """{"keys":[${key.toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY)}]}""",
                                        ),
                                    )
                                },
                            )
                        }
                    },
                )
            }
            return JsonWebSignature().apply {
                this.payload = mapper.writeValueAsString(payload)
                key = signingKey.privateKey
                keyIdHeaderValue = signingKey.keyId
                setHeader("typ", "trustedlist+jwt")
                algorithmHeaderValue = AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256
            }.compactSerialization
        }
    }
}
