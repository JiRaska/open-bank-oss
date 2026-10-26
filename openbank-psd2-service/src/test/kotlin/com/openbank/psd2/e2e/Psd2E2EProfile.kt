// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.e2e

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the psd2 E2E suite: real Postgres + Valkey (the shared
 * [PostgresRedisTestResource] — the Redis is the real idempotency store the Berlin write surface
 * dedupes against) plus [Psd2E2EWireMockResource] standing in for tpp-registry, consent-service
 * and the OIDC token endpoint over real HTTP. The suite proves the Berlin XS2A chain — QWAC
 * transport authorization on every `/v1` request, consent fail-closed validation on AIS/PIS, M2M
 * OIDC on the consent-service client — with the downstream request journal as the wire evidence.
 *
 * `authz.enforce=false`: the OPA-backed PDP is fail-closed and there is no sidecar in tests, so
 * every `@Authorize` route would otherwise answer 503 (same override the module's ITs carry).
 *
 * The module's [com.openbank.psd2.integration.FakeTppRegistryRestClient] is a `@Mock`, i.e. it
 * replaces TppRegistryRestClient in EVERY @QuarkusTest of the module. This suite exists to prove
 * the real registry round trip — EidasMtlsFilter → TppAuthorizationGuard (@Retry/@CircuitBreaker)
 * → the REST client — so the profile EXCLUDES the fake from bean discovery: with it gone, the
 * guard calls [Psd2E2EWireMockResource] over HTTP and the journal can count the fan-out.
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM (and one
 * container set) across the whole suite.
 */
class Psd2E2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(PostgresRedisTestResource::class.java, mapOf("db" to "openbank_psd2_e2e")),
        TestResourceEntry(Psd2E2EWireMockResource::class.java),
    )

    override fun getConfigOverrides() = mapOf(
        "authz.enforce" to "false",
        // QSEAL stays advisory (the default): the suite drives unsigned requests exactly like a
        // sandbox TPP; Psd2QsealEnforceIT covers the enforce=true rejection separately.
        "quarkus.arc.exclude-types" to "com.openbank.psd2.integration.FakeTppRegistryRestClient",
    )

    override fun runMainMethod(): Boolean = false
}
