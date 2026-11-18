// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import com.openbank.libs.security.DpopProofVerifier
import com.openbank.libs.security.DpopReplayStore
import com.openbank.libs.security.InMemoryDpopReplayStore
import com.openbank.libs.security.Jose4jDpopProofParser
import com.openbank.mcp.infrastructure.mcp.CallerContextResolver
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.Optional
import kotlin.coroutines.cancellation.CancellationException

/**
 * The customer MCP channel switch (ADR-0383 / ADR-0385, #16590). OFF unless
 * `mcp.customer.enabled=true`; with it off, nothing on this channel is reachable and a
 * customers-realm token is refused.
 *
 * `@Startup` on purpose: `@ApplicationScoped` is lazy, and the allowlist check is a BOOT gate
 * (ADR-0385 D1 "mcp-service refuses to boot on one"). With the channel on, a missing issuer, a
 * missing allowlist or a malformed one throws here and the service does not start.
 *
 * **Shared state (ADR-0383 P4, #16590).** `mcp.customer.shared-store` selects where the DPoP replay
 * claims and the D5.3 budgets live: `valkey` (every replica claims in one Valkey,
 * [ValkeyDpopReplayStore] + [ValkeyCustomerThrottles]) or `memory` (the single-pod
 * [InMemoryDpopReplayStore] and the in-process limiter — tests and dev only). In a production
 * launch (`LaunchMode.NORMAL`) with the channel on, anything but `valkey` refuses to boot: with
 * `memory`, a second replica or a pod roll would accept a proof a first one already admitted.
 */
@Startup
@ApplicationScoped
class CustomerChannel @Inject constructor(
    @ConfigProperty(name = "mcp.customer.enabled") enabled: Optional<Boolean>,
    @ConfigProperty(name = "mcp.customer.issuer") issuer: Optional<String>,
    @ConfigProperty(name = "mcp.customer.allowlist") allowlistLocation: Optional<String>,
    @ConfigProperty(name = "mcp.customer.shared-store") sharedStore: Optional<String>,
    @ConfigProperty(name = "mcp.customer.shared-store-timeout-ms") sharedStoreTimeoutMs: Optional<Long>,
    redis: Instance<ReactiveRedisDataSource>,
    limiter: McpRateLimiter,
    launchMode: LaunchMode,
) {
    private var shared: SharedState? = null

    /**
     * Null when the channel is off; otherwise the admission policy loaded at boot. The allowlist is
     * validated first, the shared store selected second, so each refusal names its own cause.
     */
    val admission: CustomerChannelAdmission? = if (enabled.orElse(false)) {
        load(issuer, allowlistLocation) {
            selectStore(sharedStore, sharedStoreTimeoutMs, redis, limiter, launchMode).also { shared = it }.replayStore
        }
    } else {
        null
    }

    /** The shared D5.3 budgets in `valkey` mode; null = the caller uses the in-process limiter. */
    val sharedThrottles: CustomerChannelAdmission.Throttles? get() = shared?.throttles

    val enabled: Boolean get() = admission != null

    /** Where replay claims and budgets live, chosen once at boot. */
    internal class SharedState(
        val mode: String,
        val replayStore: DpopReplayStore,
        val throttles: CustomerChannelAdmission.Throttles?,
    )

    internal companion object {
        private val log = Logger.getLogger(CustomerChannel::class.java)
        private const val CLASSPATH_PREFIX = "classpath:"
        const val STORE_VALKEY = "valkey"
        const val STORE_MEMORY = "memory"

        /** The boot decision, separated so it is testable without a Quarkus launch. */
        fun selectStore(
            store: Optional<String>,
            timeoutMs: Optional<Long>,
            redis: Instance<ReactiveRedisDataSource>,
            limiter: McpRateLimiter,
            launchMode: LaunchMode,
        ): SharedState {
            val mode = store.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.orElseThrow {
                IllegalStateException("mcp.customer.enabled=true but mcp.customer.shared-store is not set")
            }
            check(mode == STORE_VALKEY || mode == STORE_MEMORY) {
                "mcp.customer.shared-store must be '$STORE_VALKEY' or '$STORE_MEMORY', was '$mode'"
            }
            check(mode == STORE_VALKEY || launchMode != LaunchMode.NORMAL) {
                "mcp.customer.enabled=true in a production launch requires mcp.customer.shared-store=" +
                    "$STORE_VALKEY: the in-memory DPoP replay store and budgets are per pod (ADR-0383 P4)"
            }
            if (mode == STORE_MEMORY) {
                return SharedState(mode, InMemoryDpopReplayStore(), null)
            }
            val timeout = timeoutMs.filter { it > 0 }.map(Duration::ofMillis).orElseThrow {
                IllegalStateException(
                    "mcp.customer.shared-store=valkey but mcp.customer.shared-store-timeout-ms is not set",
                )
            }
            check(redis.isResolvable) {
                "mcp.customer.shared-store=valkey but no Redis client is configured (quarkus.redis.hosts)"
            }
            val ds = redis.get()
            val limits = ValkeyCustomerThrottles.Limits(
                perMinute = limiter.callsPerMinute,
                perDay = limiter.callsPerDay,
                sharedPerMinute = limiter.sharedCallsPerMinute,
                enabled = limiter.enabled,
            )
            return SharedState(
                mode,
                ValkeyDpopReplayStore(ds, timeout),
                ValkeyCustomerThrottles(ds, timeout, limits, Clock.systemUTC()),
            )
        }

        @Suppress("TooGenericExceptionCaught") // any parser failure must become a boot refusal, see below
        fun load(
            issuer: Optional<String>,
            location: Optional<String>,
            replayStore: () -> DpopReplayStore,
        ): CustomerChannelAdmission {
            val iss = issuer.filter { it.isNotBlank() }.orElseThrow {
                IllegalStateException("mcp.customer.enabled=true but mcp.customer.issuer is not set")
            }
            val where = location.filter { it.isNotBlank() }.orElseThrow {
                IllegalStateException("mcp.customer.enabled=true but mcp.customer.allowlist is not set")
            }
            val text = read(where)
                ?: error("MCP client allowlist not found at '$where'")
            val allowlist = try {
                McpClientAllowlist.parse(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // IllegalArgumentException from our own checks, YAMLException (or anything else) from the
                // parser: every one is a clean refusal to boot naming the file, never a raw stack trace.
                throw IllegalStateException("MCP client allowlist at '$where' is malformed: ${e.message}", e)
            }
            // ADR-0385 D1: the allowlist and the staff-path audience check must name the SAME audience,
            // or a token admitted here would read as foreign there (and vice versa).
            check(allowlist.audience == CallerContextResolver.MCP_AUDIENCE) {
                "MCP client allowlist at '$where' declares audience '${allowlist.audience}', " +
                    "but this server is '${CallerContextResolver.MCP_AUDIENCE}'"
            }
            val store = replayStore()
            log.infof(
                "customer MCP channel enabled: %d declared client(s), %d enabled, resource %s, store %s",
                allowlist.clients.size,
                allowlist.clients.values.count { it.enabled },
                allowlist.resource,
                store.javaClass.simpleName,
            )
            val verifier = DpopProofVerifier(Jose4jDpopProofParser(), store)
            return CustomerChannelAdmission(iss, allowlist, verifier, Clock.systemUTC())
        }

        private fun read(location: String): String? = if (location.startsWith(CLASSPATH_PREFIX)) {
            val name = location.removePrefix(CLASSPATH_PREFIX).removePrefix("/")
            Thread.currentThread().contextClassLoader.getResourceAsStream(name)
                ?.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
        } else {
            Path.of(location).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
        }
    }
}
