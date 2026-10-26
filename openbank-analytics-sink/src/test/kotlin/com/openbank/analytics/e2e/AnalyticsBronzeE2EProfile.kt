// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.e2e

import com.openbank.analytics.it.RedpandaTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Profile for the bronze-pipeline real-broker E2E: the module's own real Redpanda broker (which
 * also supplies the genuine `group.id=analytics-sink` / `auto.offset.reset=earliest` properties —
 * see [RedpandaTestResource]) plus the fake ClickHouse [FakeClickHouseResource] capturing inserts.
 *
 * [getConfigOverrides] sets the BUILD-time gate `openbank.analytics.sink.type=clickhouse`, which
 * is what selects the durable [com.openbank.analytics.infrastructure.sink.ClickHouseAnalyticsSink]
 * and [com.openbank.analytics.infrastructure.sink.ClickHouseDeadLetterSink] alternatives over
 * their logging fallbacks at augmentation time. Profile config overrides are applied while the
 * test application is (re)built for this profile, so the build-time condition sees the value —
 * exactly the mechanism Quarkus documents for testing build-time configuration. The ClickHouse
 * URL itself stays a runtime property supplied by the test resource, whose ephemeral port cannot
 * be known at profile-definition time; the sinks read it lazily on first write.
 */
class AnalyticsBronzeE2EProfile : QuarkusTestProfile {

    override fun disableGlobalTestResources() = true

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "openbank.analytics.sink.type" to "clickhouse",
    )

    override fun testResources() = listOf(
        TestResourceEntry(RedpandaTestResource::class.java),
        TestResourceEntry(FakeClickHouseResource::class.java),
    )
}
