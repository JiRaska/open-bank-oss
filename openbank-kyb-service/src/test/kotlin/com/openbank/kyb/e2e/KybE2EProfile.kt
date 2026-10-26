// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.e2e

import com.openbank.kyb.it.PostgresTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry
import io.smallrye.reactive.messaging.memory.InMemoryConnector

/**
 * Shared profile for the kyb E2E suite: real Postgres (the shared [PostgresTestResource]) plus
 * [KybE2EWireMockResource] standing in for ARES, party-service, document-service and the OIDC
 * token endpoint over real HTTP. The suite proves the ADR-0284 onboarding chain — the one flow
 * that fans out to a public register and two platform services — with durable facts in a real
 * database and a downstream request journal as the wire evidence.
 *
 * `authz.enforce=false`: the PDP is fail-closed, and without an OPA sidecar every `@Authorize`
 * route would answer 503 POLICY_DECISION_POINT_UNAVAILABLE (same override the module's %test
 * profile already carries).
 *
 * The module's ITs stub the out-of-process ports at the CDI level — StubAresAdapter,
 * StubPartyGateway and StubDocumentGateway are all @Alternative @Priority(1), i.e. active in
 * EVERY @QuarkusTest of the module. This suite exists to prove the real HTTP wiring, so the E2E
 * profile EXCLUDES those types from bean discovery: the real registry adapter (with its
 * @Retry/@CircuitBreaker), the real party-service/document-service REST clients (with their OIDC
 * filters and taint propagation) are what gets tested.
 *
 * Both Kafka channels switch to the in-memory connector: the outbox relay must not touch a real
 * broker, and the PARTY_CREATED consumer only feeds cases this suite does not read.
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM (and one
 * container set) across the whole suite.
 */
class KybE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(PostgresTestResource::class.java),
        TestResourceEntry(KybE2EWireMockResource::class.java),
    )

    override fun getConfigOverrides() = mapOf(
        "authz.enforce" to "false",
        // Build-time flag: the E2E WireMock resource stands in for the token endpoint, which
        // requires the OIDC client feature on. %test no longer disables it (see
        // application.yaml); pinning it here too keeps augmentation and runtime in agreement.
        "quarkus.oidc-client.enabled" to "true",
        // The module's ITs stub the downstream ports at the CDI level — see the class KDoc.
        "quarkus.arc.exclude-types" to
            "com.openbank.kyb.it.StubAresAdapter," +
            "com.openbank.kyb.it.StubPartyGateway," +
            "com.openbank.kyb.it.StubDocumentGateway",
    ) + InMemoryConnector.switchOutgoingChannelsToInMemory("kyb-events-out") +
        InMemoryConnector.switchIncomingChannelsToInMemory("party-events-in")

    override fun runMainMethod(): Boolean = false
}
