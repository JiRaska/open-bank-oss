// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.e2e

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the wealth E2E suite: real Postgres + real Redpanda (the shared
 * [PostgresRedpandaTestResource]). Every pre-existing test in this module switches
 * `wealth-events-out` to the in-memory connector, so no test had ever let the outbox dispatcher
 * relay a row across a real broker — this profile exists to close exactly that gap: the holding
 * row, its outbox row and the Kafka record become three independently observable facts that must
 * agree.
 *
 * The dispatcher schedule is tightened (1 s poll, 1 s initial delay) so a lifecycle event becomes
 * a wire fact within the test horizon, and dispatch stays explicitly on. The canary principal is
 * named trusted so the synthetic-taint journey can prove the taint reaches the broker record.
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM (and one
 * container set) across the whole suite.
 */
class WealthE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(
            PostgresRedpandaTestResource::class.java,
            mapOf("db" to "openbank_wealth_e2e"),
        ),
    )

    override fun getConfigOverrides() = mapOf(
        "openbank.outbox.dispatch-enabled" to "true",
        "openbank.outbox.poll-interval" to "1s",
        "openbank.outbox.initial-delay" to "1s",
        // The synthetic-taint E2E journey: only this principal may taint rows; everyone else in
        // the suite stays untrusted on purpose.
        "openbank.synthetic.trusted-principals" to CANARY,
    )

    companion object {
        const val CANARY = "service-account-openbank-synthetic-canary"
    }
}
