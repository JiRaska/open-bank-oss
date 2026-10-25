// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.e2e

import com.openbank.risk.it.PostgresTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry
import io.smallrye.reactive.messaging.memory.InMemoryConnector

/**
 * Shared profile for the risk-engine E2E suite: real Postgres (the shared [PostgresTestResource])
 * plus [RiskDownstreamWireMockResource] standing in for ledger-service, lending-service and the
 * OIDC token endpoint over real HTTP. The suite proves the ADR-0314 snapshot lifecycle — the one
 * read that fans out to downstream services — with durable facts in a real database.
 *
 * `authz.enforce=false`: the PDP is fail-closed, and without an OPA sidecar every `@Authorize`
 * route would answer 503 POLICY_DECISION_POINT_UNAVAILABLE.
 *
 * `quarkus.scheduler.enabled=false`: the EOD snapshot scheduler and the risk-limit outbox
 * dispatcher must not run against the suite — every run and every outbox row in the assertions
 * was created by the HTTP call under test, not by a cron.
 *
 * All four Kafka channels switch to the in-memory connector: the deal/fixing consumers only feed
 * projections the snapshot journeys here do not read (treasury deals stay an empty book), and the
 * outbox relay must not touch a real broker.
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM (and one
 * container set) across the whole suite.
 */
class RiskE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(PostgresTestResource::class.java),
        TestResourceEntry(RiskDownstreamWireMockResource::class.java),
    )

    override fun getConfigOverrides() = mapOf(
        "authz.enforce" to "false",
        "quarkus.scheduler.enabled" to "false",
        // The module's ITs stub the downstream ports at the CDI level — FakeLedgerPort,
        // FakeLendingPort and the @Mock fixed Clock are all @Alternative @Priority(1), i.e. active
        // in EVERY @QuarkusTest of the module. This suite exists to prove the real HTTP wiring,
        // so the E2E profile EXCLUDES those types from bean discovery: the REST adapters and the
        // real clock are what gets tested.
        "quarkus.arc.exclude-types" to
            "com.openbank.risk.integration.FakeLedgerPort," +
            "com.openbank.risk.integration.FakeLendingPort," +
            "com.openbank.risk.integration.FixedClockProducer," +
            "com.openbank.risk.integration.FixedClockForIntegrationTests",
    ) + InMemoryConnector.switchOutgoingChannelsToInMemory("risk-limit-events-out") +
        InMemoryConnector.switchIncomingChannelsToInMemory(
            "fx-fixing-in",
            "cnb-policy-rate-in",
            "treasury-deal-in",
        )

    override fun runMainMethod(): Boolean = false
}
