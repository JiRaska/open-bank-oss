// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.e2e

import com.openbank.settlement.it.BalanceServiceWireMockResource
import com.openbank.settlement.it.SettlementKafkaTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the settlement E2E suite: real Postgres + Redpanda (the shared
 * [SettlementKafkaTestResource], outbox dispatch on so `SETTLEMENT_STATE_CHANGED` reaches the
 * broker) and the WireMock balance/ledger stand-in so the REST clients boot against real HTTP.
 * Authorization enforcement stays off — RBAC remains, and the enforced maker/checker journey is
 * the FourEyesFlowIT's subject; this suite proves origination idempotence, guard sondy, read-back
 * and the outbox wire fact over the whole module in one profile.
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM (and one
 * container set) across the whole suite.
 */
class SettlementE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(SettlementKafkaTestResource::class.java),
        TestResourceEntry(BalanceServiceWireMockResource::class.java),
    )

    override fun getConfigOverrides() = mapOf(
        "authz.enforce" to "false",
        "authz.four-eyes.enforce" to "false",
    )
}
