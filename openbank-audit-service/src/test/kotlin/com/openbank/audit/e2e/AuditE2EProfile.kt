// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.e2e

import com.openbank.audit.it.AuditKafkaTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the audit-service E2E suite: real Postgres + Redpanda (the shared
 * [AuditKafkaTestResource], which points `audit-events-in` at the ingestion topic with a
 * dedicated group and earliest reset). `authz.enforce=false` because an enforced @Authorize
 * request fails closed (pdp_unconfigured) with no OPA sidecar — same shape as the other E2E
 * suites. The suite proves broker → consumer → Postgres → HTTP: an audit event published on
 * Redpanda lands in the append-only `audit_entries` chain and reads back over the regulated
 * trail/evidence/actor routes.
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM (and one
 * container set) across the whole suite.
 */
class AuditE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(AuditKafkaTestResource::class.java),
    )

    override fun getConfigOverrides() = mapOf(
        "authz.enforce" to "false",
    )
}
