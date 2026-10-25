// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.e2e

import com.openbank.vop.PostgresTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the vop E2E suite: real Postgres + Valkey (the shared
 * [PostgresTestResource] — Valkey is both a readiness dependency and the rate-limit store, so a
 * boot without it answers 503) plus [VopDownstreamWireMockResource] standing in for the two
 * name-resolution hops and the OIDC token endpoint. The suite proves the ADR-0171 verification
 * chain over the real HTTP surface with durable evidence rows in a real database.
 *
 * `authz.enforce=false`: the PDP is fail-closed, and without an OPA sidecar every `@Authorize`
 * route would answer 503 POLICY_DECISION_POINT_UNAVAILABLE.
 *
 * The rate limit is deliberately turned DOWN, not off: it is the enumeration control the threat
 * model calls load-bearing, so one journey proves it end to end. The limiter keys its fixed window
 * on the requester principal, and every journey uses a distinct `@TestSecurity` user — so a low
 * limit cannot contaminate journeys that never make more than a handful of calls.
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM (and one
 * container set) across the whole suite.
 */
class VopE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(PostgresTestResource::class.java),
        TestResourceEntry(VopDownstreamWireMockResource::class.java),
    )

    override fun getConfigOverrides() = mapOf(
        "authz.enforce" to "false",
        "openbank.vop.rate-limit.requests-per-minute" to "4",
    )

    override fun runMainMethod(): Boolean = false
}
