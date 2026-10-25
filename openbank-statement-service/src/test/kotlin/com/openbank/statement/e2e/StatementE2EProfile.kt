// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.statement.e2e

import com.openbank.statement.it.PostgresTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry
import io.smallrye.reactive.messaging.memory.InMemoryConnector

/**
 * Shared profile for the statement E2E suite: real Postgres (the shared [PostgresTestResource])
 * plus [StatementDownstreamWireMockResource] standing in for all five downstream services and
 * both OIDC token endpoints. The suite proves the ADR-0035 period-close lifecycle over the real
 * HTTP surface with durable facts in a real database.
 *
 * `authz.enforce=false`: the PDP is fail-closed, and without an OPA sidecar every `@Authorize`
 * route would answer 503 POLICY_DECISION_POINT_UNAVAILABLE.
 *
 * Both Kafka channels switch to the in-memory connector: the close path publishes
 * `statement-events-out` via the transactional outbox (asserted over JDBC — the scheduler is
 * disabled under %test, so rows stay PENDING) and the app consumes `account-events-in` for the
 * account registry projection (a boot with the real connector and no broker leaves the readiness
 * messaging check DOWN).
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM (and one
 * container set) across the whole suite.
 */
class StatementE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(PostgresTestResource::class.java),
        TestResourceEntry(StatementDownstreamWireMockResource::class.java),
    )

    override fun getConfigOverrides() = mapOf(
        "authz.enforce" to "false",
    ) + InMemoryConnector.switchOutgoingChannelsToInMemory("statement-events-out") +
        InMemoryConnector.switchIncomingChannelsToInMemory("account-events-in")

    override fun runMainMethod(): Boolean = false
}
