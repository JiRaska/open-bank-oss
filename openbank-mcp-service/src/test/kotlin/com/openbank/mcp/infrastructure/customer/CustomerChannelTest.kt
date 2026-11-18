// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.customer

import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import io.mockk.every
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.runtime.LaunchMode
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional

/** The boot gate (ADR-0385 D1): with the channel on, a bad allowlist means the bean cannot be built. */
class CustomerChannelTest {

    private fun channel(
        enabled: Boolean?,
        issuer: String?,
        location: String?,
        store: String? = CustomerChannel.STORE_MEMORY,
        launchMode: LaunchMode = LaunchMode.TEST,
        redis: Instance<ReactiveRedisDataSource> = noRedis(),
        settings: CustomerStoreSettings = settings(store),
    ) = CustomerChannel(
        Optional.ofNullable(enabled),
        Optional.ofNullable(issuer),
        Optional.ofNullable(location),
        settings,
        redis,
        McpRateLimiter.withDefaults(),
        launchMode,
    )

    private fun settings(
        store: String?,
        timeoutMs: Long? = TIMEOUT_MS,
        hosts: String? = HOSTS,
        secret: String? = SECRET,
    ) = CustomerStoreSettings(
        Optional.ofNullable(store),
        Optional.ofNullable(timeoutMs),
        Optional.ofNullable(hosts),
        Optional.ofNullable(secret),
    )

    private fun withRedis(): Instance<ReactiveRedisDataSource> {
        val ds = mockk<ReactiveRedisDataSource>(relaxed = true)
        return mockk {
            every { isResolvable } returns true
            every { get() } returns ds
        }
    }

    private fun valkey(settings: CustomerStoreSettings) = channel(
        true,
        DpopTestProofs.ISSUER,
        ALLOWLIST,
        launchMode = LaunchMode.NORMAL,
        redis = withRedis(),
        settings = settings,
    )

    @Test
    fun `valkey with a resolvable bean but NO quarkus_redis_hosts refuses to start`() {
        assertThatThrownBy { valkey(settings(CustomerChannel.STORE_VALKEY, hosts = null)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no Redis client is configured")
    }

    @Test
    fun `valkey without the key HMAC secret, or with a short one, refuses to start`() {
        assertThatThrownBy { valkey(settings(CustomerChannel.STORE_VALKEY, secret = null)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("key-hmac-secret is not set")
        assertThatThrownBy { valkey(settings(CustomerChannel.STORE_VALKEY, secret = "too-short")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least 32 bytes")
    }

    @Test
    fun `the shared-store timeout must be set and within 1 to 2000 ms`() {
        assertThatThrownBy { valkey(settings(CustomerChannel.STORE_VALKEY, timeoutMs = null)) }
            .hasMessageContaining("shared-store-timeout-ms is not set")
        listOf(0L, -5L, 2001L).forEach { bad ->
            assertThatThrownBy { valkey(settings(CustomerChannel.STORE_VALKEY, timeoutMs = bad)) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("between 1 and 2000")
        }
        assertThat(valkey(settings(CustomerChannel.STORE_VALKEY, timeoutMs = 2000L)).enabled).isTrue()
    }

    private fun noRedis(): Instance<ReactiveRedisDataSource> =
        mockk<Instance<ReactiveRedisDataSource>> { every { isResolvable } returns false }

    private fun enabledIn(
        launchMode: LaunchMode,
        store: String?,
        redis: Instance<ReactiveRedisDataSource> = noRedis(),
    ) = channel(true, DpopTestProofs.ISSUER, ALLOWLIST, store = store, launchMode = launchMode, redis = redis)

    @Test
    fun `a production launch refuses the in-memory store, and an unset one`() {
        assertThatThrownBy { enabledIn(LaunchMode.NORMAL, CustomerChannel.STORE_MEMORY) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("requires mcp.customer.shared-store=valkey")
        assertThatThrownBy { enabledIn(LaunchMode.NORMAL, null) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("shared-store is not set")
    }

    @Test
    fun `valkey without a configured Redis client refuses to start, in any launch mode`() {
        assertThatThrownBy { enabledIn(LaunchMode.NORMAL, CustomerChannel.STORE_VALKEY) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no Redis client")
        assertThatThrownBy { enabledIn(LaunchMode.TEST, CustomerChannel.STORE_VALKEY) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no Redis client")
    }

    @Test
    fun `valkey with a Redis client boots in production and shares its budgets`() {
        val ds = mockk<ReactiveRedisDataSource>(relaxed = true)
        val redis = mockk<Instance<ReactiveRedisDataSource>> {
            every { isResolvable } returns true
            every { get() } returns ds
        }
        val c = enabledIn(LaunchMode.NORMAL, "Valkey", redis)
        assertThat(c.enabled).isTrue()
        assertThat(c.sharedThrottles).isInstanceOf(ValkeyCustomerThrottles::class.java)
    }

    @Test
    fun `an unknown store name refuses to start, and memory is allowed outside production`() {
        assertThatThrownBy { enabledIn(LaunchMode.TEST, "postgres") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("must be 'valkey' or 'memory'")
        val dev = enabledIn(LaunchMode.DEVELOPMENT, CustomerChannel.STORE_MEMORY)
        assertThat(dev.enabled).isTrue()
        assertThat(dev.sharedThrottles).isNull()
    }

    private companion object {
        const val TIMEOUT_MS = 250L
        const val HOSTS = "redis://valkey.test:6379"
        const val SECRET = "0123456789abcdef0123456789abcdef-test-only"
        const val ALLOWLIST = "classpath:test-mcp-client-allowlist.yaml"
    }

    @Test
    fun `off by default - absent flag means no admission and no allowlist read`() {
        assertThat(channel(null, null, "classpath:does-not-exist.yaml").enabled).isFalse()
        assertThat(channel(false, null, null).admission).isNull()
    }

    @Test
    fun `enabled with a valid classpath allowlist loads it`() {
        val c = channel(true, DpopTestProofs.ISSUER, "classpath:test-mcp-client-allowlist.yaml")
        assertThat(c.enabled).isTrue()
        assertThat(c.admission!!.allowlist.admitted("mcp-local-test")).isNotNull()
    }

    @Test
    fun `enabled with a missing allowlist refuses to start`() {
        assertThatThrownBy { channel(true, DpopTestProofs.ISSUER, "classpath:does-not-exist.yaml") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not found")
        assertThatThrownBy { channel(true, DpopTestProofs.ISSUER, null) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `enabled with a malformed allowlist refuses to start`(@TempDir dir: Path) {
        val file = Files.writeString(dir.resolve("bad.yaml"), "schema_version: 1\nclients: []\n")
        assertThatThrownBy { channel(true, DpopTestProofs.ISSUER, file.toString()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("malformed")
    }

    @Test
    fun `enabled without an issuer refuses to start`() {
        assertThatThrownBy { channel(true, " ", "classpath:test-mcp-client-allowlist.yaml") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("issuer")
    }

    @Test
    fun `an allowlist naming another audience refuses to start`(@TempDir dir: Path) {
        val text = javaClass.classLoader.getResource("test-mcp-client-allowlist.yaml")!!.readText()
            .replace("audience: openbank-mcp-service", "audience: openbank-app")
        val file = Files.writeString(dir.resolve("aud.yaml"), text)
        assertThatThrownBy { channel(true, DpopTestProofs.ISSUER, file.toString()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("audience")
    }

    @Test
    fun `a YAML syntax error is a clean boot refusal, not a parser exception`(@TempDir dir: Path) {
        val file = Files.writeString(dir.resolve("broken.yaml"), "schema_version: 1\nclients: [ {client_id: a\n")
        assertThatThrownBy { channel(true, DpopTestProofs.ISSUER, file.toString()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("malformed")
    }
}
