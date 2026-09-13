// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.agent.application

import com.openbank.agent.application.port.out.PublishedStylePort
import com.openbank.agent.infrastructure.client.PublishedStyleDto
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

private class FakePublishedStylePort(private val response: () -> PublishedStyleDto) : PublishedStylePort {
    val callCount = AtomicInteger(0)
    override suspend fun fetch(personaKey: String): PublishedStyleDto {
        callCount.incrementAndGet()
        return response()
    }
}

/** A [Clock] whose `instant()` is set explicitly per test step — no real waiting for TTL tests. */
private class MutableClock(private var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

class PublishedStyleProviderTest {

    private val published = PublishedStyleDto(
        personaKey = "ui-assistant",
        styleVersion = 3,
        tone = "neutral",
        formality = "formal",
        formOfAddress = "vykání",
    )

    @Test
    fun `a successful fetch returns the published style and caches it within the TTL`() {
        val port = FakePublishedStylePort { published }
        val clock = MutableClock(Instant.parse("2026-09-11T09:00:00Z"))
        val provider = PublishedStyleProvider(port, clock)

        val first = runBlocking { provider.currentStyle("ui-assistant") }
        clock.advance(Duration.ofMinutes(1))
        val second = runBlocking { provider.currentStyle("ui-assistant") }

        assertThat(first).isInstanceOf(ResolvedStyle.Published::class.java)
        assertThat((first as ResolvedStyle.Published).style.styleVersion).isEqualTo(3)
        assertThat(first.version).isEqualTo("3")
        assertThat(second).isEqualTo(first)
        // Second call is served from cache — the port is not hit again within the TTL.
        assertThat(port.callCount.get()).isEqualTo(1)
    }

    @Test
    fun `once the TTL expires, a fresh fetch is made`() {
        val port = FakePublishedStylePort { published }
        val clock = MutableClock(Instant.parse("2026-09-11T09:00:00Z"))
        val provider = PublishedStyleProvider(port, clock)

        runBlocking { provider.currentStyle("ui-assistant") }
        clock.advance(Duration.ofMinutes(6))
        runBlocking { provider.currentStyle("ui-assistant") }

        assertThat(port.callCount.get()).isEqualTo(2)
    }

    @Test
    fun `a fetch failure with no prior cache returns null — there is no baseline to fall back to`() {
        val port = FakePublishedStylePort { throw IOException("connection refused") }
        val provider = PublishedStyleProvider(port, Clock.systemUTC())

        val result = runBlocking { provider.currentStyle("ui-assistant") }

        assertThat(result).isNull()
    }

    @Test
    fun `once the TTL expires, a fetch failure falls back to the last known-good value, not null`() {
        var shouldFail = false
        val port = FakePublishedStylePort {
            if (shouldFail) throw IOException("timeout") else published
        }
        val clock = MutableClock(Instant.parse("2026-09-11T09:00:00Z"))
        val provider = PublishedStyleProvider(port, clock)

        val warm = runBlocking { provider.currentStyle("ui-assistant") }
        assertThat(warm).isInstanceOf(ResolvedStyle.Published::class.java)

        shouldFail = true
        clock.advance(Duration.ofMinutes(6))
        val degraded = runBlocking { provider.currentStyle("ui-assistant") }

        assertThat(degraded).isEqualTo(warm)
        assertThat(degraded).isNotNull()
    }
}
