// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.crypto

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.libs.security.EgressPolicy
import com.openbank.libs.security.EgressRequest
import com.openbank.libs.security.EgressResolver
import com.openbank.libs.security.SafeHttpClient
import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jose4j.jwa.AlgorithmConstraints
import org.jose4j.jwa.AlgorithmConstraints.ConstraintType
import org.jose4j.jwk.JsonWebKeySet
import org.jose4j.jwk.PublicJsonWebKey
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Optional

/**
 * eIDAS Trusted List trust framework for EUDI wallet verification (ADR-0094).
 *
 * Replaces a statically-configured, UNSIGNED allow-list of issuers with a **signed Trusted List** the
 * relying party fetches and verifies — trust derives from a configured **trust anchor's signature**,
 * not from raw config. Models the eIDAS LoTL → Trusted List hierarchy as a signed JWT
 * (`typ=trustedlist+jwt`) whose payload is `{iss, iat, exp, trusted_issuers:[{iss, jwks}]}` signed by
 * the scheme operator. The verified issuers are pushed into the verifier's [RefreshableTrustStore],
 * merged on top of the static base, and refreshed on a schedule (a revoked/rotated anchor propagates
 * without a restart). Fail-closed: any fetch/verify failure leaves the current trust store untouched —
 * a bad list never widens OR silently empties trust.
 *
 * Two sources: `url` (the production pull) or `inline` (a signed list provided directly via config,
 * for environments without a reachable list endpoint). Absent both ⇒ the framework is inert and only
 * the static `trusted-issuers-json` config is in effect.
 *
 * The `url` pull goes through [SafeHttpClient] (ADR-0320 P1): its host must be listed in
 * `openbank.pid.eudi.trusted-list.allowed-hosts` (empty by default, so a URL alone fetches nothing),
 * DNS is pinned, redirects are not followed and the body is capped.
 */
@ApplicationScoped
class TrustedListService(
    @ConfigProperty(name = "openbank.pid.eudi.trusted-list.url")
    private val url: Optional<String>,
    @ConfigProperty(name = "openbank.pid.eudi.trusted-list.inline")
    private val inline: Optional<String>,
    @ConfigProperty(name = "openbank.pid.eudi.trusted-list.anchor-jwks")
    private val anchorJwksJson: Optional<String>,
    private val trustStore: RefreshableTrustStore,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
    @ConfigProperty(name = "openbank.pid.eudi.trusted-list.allowed-hosts")
    private val allowedHosts: Optional<String>,
) {
    /** Visible for testing: lets a unit test pin a stub host to loopback. */
    internal var resolver: EgressResolver = EgressResolver.SYSTEM

    private var liveness: WorkflowLivenessRecorder? = null

    private val anchor: JsonWebKeySet? = anchorJwksJson
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "{}" }
        .map { runCatching { JsonWebKeySet(it) }.getOrNull() }
        .orElse(null)

    private val httpClient: SafeHttpClient by lazy {
        SafeHttpClient(
            EgressPolicy.fromConfig(allowedHosts.orElse("").split(',')),
            resolver = resolver,
            connectTimeout = Duration.ofSeconds(HTTP_TIMEOUT_SECONDS),
            readTimeout = Duration.ofSeconds(HTTP_TIMEOUT_SECONDS),
            callTimeout = Duration.ofSeconds(HTTP_TIMEOUT_SECONDS),
            maxResponseBytes = MAX_LIST_BYTES,
        )
    }

    private val sigAlgConstraints = AlgorithmConstraints(
        ConstraintType.PERMIT,
        AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256,
        AlgorithmIdentifiers.EDDSA,
    )

    @Suppress("UnusedParameter") // the StartupEvent is the CDI trigger; its value is not needed
    fun onStart(@Observes startup: StartupEvent) = refresh()

    /**
     * Liveness is registered only when a list source is configured: with neither `url` nor `inline`
     * the refresh never runs to [WorkflowLivenessRecorder.recordSuccess], so a registered gauge would
     * age forever and `WorkflowLivenessStale` would fire for a workflow that is deliberately off.
     */
    @PostConstruct
    fun registerLiveness() {
        if (!sourceConfigured()) return
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    private fun sourceConfigured(): Boolean =
        listOf(inline, url).any { opt -> opt.map { it.isNotBlank() }.orElse(false) }

    @Scheduled(every = "\${openbank.pid.eudi.trusted-list.refresh:1h}", delayed = "30s")
    @Blocking
    fun refresh() {
        val source = sourceJwt() ?: return // framework inert — static config only
        if (anchor == null) {
            Log.warn("EUDI Trusted List: a list is configured but no trust anchor — refusing to apply it (fail-closed)")
            return
        }
        val issuersJson = verifyAndExtract(source) ?: return // failure already logged; keep current trust
        trustStore.replaceDynamicTrust(issuersJson)
        liveness?.recordSuccess()
        Log.info("EUDI Trusted List: trust store refreshed from the signed list")
    }

    private fun sourceJwt(): String? {
        inline.map { it.trim() }.filter { it.isNotEmpty() }.orElse(null)?.let { return it }
        val listUrl = url.map { it.trim() }.filter { it.isNotEmpty() }.orElse(null) ?: return null
        return runCatching {
            val response = httpClient.send(EgressRequest(method = "GET", url = listUrl))
            if (response.status in HTTP_OK_MIN..HTTP_OK_MAX) response.bodyAsString().trim() else null
        }.getOrElse {
            Log.warnf("EUDI Trusted List: fetch from %s failed: %s", listUrl, it.message)
            null
        }
    }

    /** Verify the list JWS against the trust anchor, check temporal validity, return its trusted_issuers JSON. */
    private fun verifyAndExtract(listJwt: String): String? {
        val jws = JsonWebSignature()
        if (runCatching { jws.compactSerialization = listJwt }.isFailure) {
            Log.warn("EUDI Trusted List: not a parseable JWS")
            return null
        }
        val keys = anchor!!
        val kid = jws.keyIdHeaderValue
        val jwk = (if (kid != null) keys.findJsonWebKey(kid, null, null, null) else keys.jsonWebKeys.firstOrNull())
            as? PublicJsonWebKey
        if (jwk == null) {
            Log.warn("EUDI Trusted List: no trust-anchor key matches the list signature")
            return null
        }
        jws.key = jwk.publicKey
        jws.setAlgorithmConstraints(sigAlgConstraints)
        if (!runCatching { jws.verifySignature() }.getOrDefault(false)) {
            Log.warn("EUDI Trusted List: signature does NOT verify against the trust anchor — rejected")
            return null
        }
        val payload = runCatching { objectMapper.readTree(jws.payload) }.getOrNull() ?: return null
        val now = Instant.now(clock).epochSecond
        // `exp` is MANDATORY: a list with no expiry would be trusted forever, so a compromised list
        // could never be retired by a fresh refresh — eIDAS Trusted Lists must be time-bounded.
        val exp = payload["exp"]?.takeIf { it.isNumber }?.asLong() ?: run {
            Log.warn("EUDI Trusted List: missing exp — rejected (lists must be time-bounded)")
            return null
        }
        if (now > exp + CLOCK_SKEW_SECONDS) {
            Log.warn("EUDI Trusted List: expired — rejected")
            return null
        }
        val issuers = payload["trusted_issuers"]?.takeIf { it.isArray } ?: run {
            Log.warn("EUDI Trusted List: no trusted_issuers array")
            return null
        }
        return objectMapper.writeValueAsString(issuers)
    }

    private companion object {
        const val HTTP_TIMEOUT_SECONDS = 5L
        const val CLOCK_SKEW_SECONDS = 120L
        const val MAX_LIST_BYTES = 2 * 1024 * 1024
        const val HTTP_OK_MIN = 200
        const val HTTP_OK_MAX = 299
        const val WORKFLOW_NAME = "pid-trusted-list-refresh"
        val EXPECTED_INTERVAL: Duration = Duration.ofHours(1)
    }
}
