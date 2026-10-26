// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.e2e

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Shared profile for the finrep E2E suite. finrep is stateless — no database, no Kafka — so the
 * ONLY out-of-process ports are the two downstream reads: ledger-service (shared `openbank-services`
 * identity) and risk-engine (finrep's OWN named `m2m` identity, ADR-0313 D6).
 * [FinrepE2EWireMockResource] stands in for both plus the Keycloak token endpoint, so the real
 * REST clients, their OIDC filters and the count-and-rethrow failure paths all cross a wire they
 * actually have to.
 *
 * The module's unit and pact tests cover the mappers and pin the request paths (ADR-0063); none of
 * them boots Quarkus with the real clients over HTTP — [com.openbank.finrep.FinrepBootSmokeTest]
 * boots but calls nothing downstream. This suite exists to prove the wiring between the resources
 * and the adapters, so the downstream request journal is the fan-out / zero-fan-out evidence.
 *
 * `openbank.finrep.risk-engine.enabled=true`: the risk-engine read is feature-flagged off by
 * default (renders C 02.00 as flagged gaps instead), and the E2E profile exists precisely to
 * prove the enabled path — the TIED_OUT run selection, the named-client identity and the
 * count-and-rethrow failure mapping would otherwise be unreachable in tests.
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM across the
 * whole suite.
 */
class FinrepE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        QuarkusTestProfile.TestResourceEntry(FinrepE2EWireMockResource::class.java),
    )

    override fun getConfigOverrides() = mapOf(
        "openbank.finrep.risk-engine.enabled" to "true",
    )

    override fun runMainMethod(): Boolean = false
}
