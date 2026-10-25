// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.e2e

import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the onboarding E2E suite: real Postgres + Redpanda via
 * [OnboardingKafkaTestResource]. The incoming channels' `group.id` / `auto.offset.reset` are set
 * from the resource (the YAML keys are inert — SmallRye quotes dotted leaf keys, #686), earliest
 * so a case opened before the consumer joined still projects, and each channel gets its own
 * group so the producer-side committed-offset assertions are unambiguous.
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM (and one
 * container set) across the whole suite.
 */
class OnboardingE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(OnboardingKafkaTestResource::class.java),
    )
}
