// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Proves the rate-limit properties are actually APPLIED by Arc, not merely declared: a Kotlin
 * default on a @ConfigProperty constructor parameter makes Arc build the bean through the
 * synthetic constructor and silently ignore the configured value. Values here differ from every
 * default, so a limiter that kept its fallbacks would fail.
 */
@QuarkusTest
@TestProfile(McpRateLimiterConfigIT.Overrides::class)
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
class McpRateLimiterConfigIT {

    class Overrides : QuarkusTestProfile {
        // Literals, not computed: the profile loads in a different classloader from the test.
        override fun getConfigOverrides() = mapOf(
            "openbank.mcp.rate-limit.calls-per-minute" to "7",
            "openbank.mcp.rate-limit.calls-per-day" to "77",
            "openbank.mcp.rate-limit.anonymous-calls-per-minute" to "3",
            "openbank.mcp.rate-limit.max-in-flight" to "2",
        )
    }

    @Inject
    lateinit var limiter: McpRateLimiter

    @Test
    fun `configured rate limit values reach the injected limiter`() {
        assertThat(limiter.callsPerMinute).isEqualTo(7)
        assertThat(limiter.callsPerDay).isEqualTo(77)
        assertThat(limiter.anonymousCallsPerMinute).isEqualTo(3)
        assertThat(limiter.maxInFlight).isEqualTo(2)
    }
}
