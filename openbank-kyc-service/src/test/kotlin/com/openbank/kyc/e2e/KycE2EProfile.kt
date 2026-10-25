// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.e2e

import com.openbank.kyc.it.KycKafkaTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the KYC E2E suite: real Postgres + Redpanda via [KycKafkaTestResource],
 * outbox dispatch off (rows stay PENDING for JDBC assertions), scheduler off, authz enforcement
 * off (no OPA sidecar in a test JVM — KycSecurityTest owns role gating), and the sandbox
 * auto-approve path off so every state change flows through the operator endpoints.
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM (and one
 * container set) across the whole suite.
 */
class KycE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(KycKafkaTestResource::class.java),
    )
}
