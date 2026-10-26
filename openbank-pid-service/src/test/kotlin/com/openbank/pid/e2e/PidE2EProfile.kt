// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.e2e

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the pid E2E suite: real Postgres + real Redpanda (the shared
 * [PostgresRedpandaTestResource]) plus WireMock behind the eIDAS Trusted List URL. Every
 * pre-existing test in this module boots with the in-memory Kafka connector, an EMPTY issuer
 * signing key (issuance fail-closed off) and an empty trusted-issuers list (every presentation
 * fails closed) — so the OpenID4VCI issuance flow, the OpenID4VP cross-device flow and the
 * Trusted List refresh had never run end to end over HTTP. This profile exists to close exactly
 * that gap: the bank issuer key, the wallet trust roots and the list endpoint are all real
 * inputs the production adapters consume.
 *
 * The EUDI backing stores are the durable Postgres variants (`openbank.pid.eudi.persistence`
 * defaults to `postgres` at build time), so the offer rows, exchange rows, status-list
 * allocation counter and revocation bits are all JDBC-observable facts in the suite.
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM (and one
 * container set) across the whole suite.
 */
class PidE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(
            PostgresRedpandaTestResource::class.java,
            mapOf("db" to "openbank_pid_e2e"),
        ),
        TestResourceEntry(PidE2EWireMockResource::class.java),
    )

    override fun getConfigOverrides() = mapOf(
        // NOTE: every KEY-DERIVED value (issuer signing key, static trust roots, trusted-list
        // anchor + host allow-list) is delivered by PidE2EWireMockResource's config map instead of
        // here. getConfigOverrides() runs in a different classloader phase than the test classes,
        // so a companion-referenced key would be a DIFFERENT random key in each phase — the app
        // would verify against different material than the tests sign with (measured: identical
        // kid, "issuer signature invalid" on every genuine presentation).
        // Tight refresh so a list swap becomes observable within the test horizon. The @Scheduled
        // delayed="30s" is hard-coded in TrustedListService, so the suite waits for the first tick
        // via the WireMock request journal before swapping lists (see PidEudiE2E).
        "openbank.pid.eudi.trusted-list.refresh" to "1s",
        "openbank.outbox.dispatch-enabled" to "true",
        "openbank.outbox.poll-interval" to "1s",
        "openbank.outbox.initial-delay" to "1s",
    )
}
