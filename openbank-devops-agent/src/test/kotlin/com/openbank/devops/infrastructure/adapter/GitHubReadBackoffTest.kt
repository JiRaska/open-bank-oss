// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.adapter

import com.openbank.libs.security.EgressResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class GitHubReadBackoffTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private fun response(status: Int, vararg headers: Pair<String, String>) =
        EgressResponse(status, headers.associate { it.first to listOf(it.second) }, byteArrayOf())

    @Test
    fun `healthy quota does not pause reads`() {
        assertThat(GitHubReadBackoff.until(response(200), now)).isNull()
        assertThat(
            GitHubReadBackoff.until(
                response(200, "x-ratelimit-limit" to "1000", "x-ratelimit-remaining" to "201"),
                now,
            ),
        )
            .isNull()
    }

    @Test
    fun `low quota preserves a fifth of the shared upstream allowance until reset`() {
        val reset = now.plusSeconds(3600)
        val response = response(
            200,
            "x-ratelimit-limit" to "1000",
            "x-ratelimit-remaining" to "200",
            "x-ratelimit-reset" to reset.epochSecond.toString(),
        )
        assertThat(GitHubReadBackoff.until(response, now)).isEqualTo(reset)
    }

    @Test
    fun `throttled responses honor the later retry and reset deadlines`() {
        val response = response(
            429,
            "retry-after" to "7200",
            "x-ratelimit-reset" to now.plusSeconds(3600).epochSecond.toString(),
        )
        assertThat(GitHubReadBackoff.until(response, now)).isEqualTo(now.plusSeconds(7200))
        assertThat(GitHubReadBackoff.until(response(403, "retry-after" to "Thu, 1 Jan 2026 01:00:00 GMT"), now))
            .isEqualTo(now.plusSeconds(3600))
    }

    @Test
    fun `missing or malformed throttle metadata cannot trigger immediate retries`() {
        assertThat(GitHubReadBackoff.until(response(429, "retry-after" to "bad", "x-ratelimit-reset" to "bad"), now))
            .isEqualTo(now.plusSeconds(900))
    }
}
