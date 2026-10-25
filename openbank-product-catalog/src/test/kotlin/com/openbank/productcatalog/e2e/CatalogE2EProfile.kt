// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.e2e

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the product-catalog E2E suite: real Postgres via the shared canonical
 * [PostgresTestResource] (ADR, issue #467) and nothing else — the service is self-contained
 * (no downstream REST clients, no Kafka), so the E2E gap here is the real HTTP surface against
 * real persistence, with the durable facts (catalog revisions, audit trail, change outbox)
 * asserted over plain JDBC.
 *
 * `%test` already disables the scheduler (the change-outbox relay stays down, so outbox rows the
 * journeys write remain PENDING and assertable) and sets `authz.enforce=false` (no OPA sidecar;
 * `@Authorize` is advisory and `@TestSecurity` mocks the identity).
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM (and one
 * container) across the whole suite.
 */
class CatalogE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(PostgresTestResource::class.java, mapOf("db" to "openbank_products_e2e")),
    )

    override fun runMainMethod(): Boolean = false
}
