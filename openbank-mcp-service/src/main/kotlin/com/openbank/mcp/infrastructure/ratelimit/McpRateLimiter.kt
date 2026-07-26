// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.ratelimit

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-acting-agent rate limit on `tools/call` (#2409).
 *
 * **Why this is a control and not throughput management.** Every MCP read tool fans out into
 * consent-service *and* one of account / balance / transaction-service (see
 * `infrastructure/read/RealAccountReadPort`), so an unthrottled MCP surface is an amplification
 * vector into four services, three of them on the money path. Authorization cannot bound it: a
 * governed agent is *supposed* to be able to read the accounts its consent grants, so the only
 * thing separating "an agent answering a question" from "an agent draining a consent's whole
 * transaction history in a loop" is the rate. The `mcp-anonymous` charter already declares
 * `limits: { runs_per_day: 1000 }` in `openbank-libs/governance/agents.yaml`; until now nothing
 * read it, and the declared limit was documentation.
 *
 * Two windows, because they answer different questions: the per-minute window bounds the burst
 * (the amplification/DoS shape), the per-day window is the charter's `runs_per_day` budget.
 *
 * **In-process, not Valkey — deliberately, and with an expiry date.** `VopRateLimiter` is
 * Valkey-backed because vop-service runs multiple replicas, where a local counter gives an
 * attacker `limit × replicas`. `openbank-mcp-service` runs `replicas: 1`
 * (`openbank-infra/gitops/components/mcp/mcp-service.yaml`), so today the two are exactly
 * equivalent and the local counter buys the control without adding a store, a secret, an egress
 * NetworkPolicy and a shared-state single point to a surface that has none. That equivalence is a
 * property of the replica count, NOT of the design: the day mcp-service scales past one replica
 * this must move to the shared window, which is why the counter lives behind this class rather
 * than inline in the endpoint. The limit is also reset by a pod roll — acceptable for a burst
 * bound, not for a hard daily budget, which is the second reason the Valkey swap is on the table.
 */
@ApplicationScoped
class McpRateLimiter {

    /** Not injected: no `Clock` producer bean exists in this service. Overridden by tests. */
    var clock: Clock = Clock.systemUTC()

    @ConfigProperty(name = "openbank.mcp.rate-limit.enabled", defaultValue = "true")
    var enabled: Boolean = true

    @ConfigProperty(name = "openbank.mcp.rate-limit.calls-per-minute", defaultValue = DEFAULT_PER_MINUTE_STR)
    var callsPerMinute: Int = DEFAULT_PER_MINUTE

    /** Mirrors the `mcp-anonymous` charter's `limits.runs_per_day` (agents.yaml). */
    @ConfigProperty(name = "openbank.mcp.rate-limit.calls-per-day", defaultValue = DEFAULT_PER_DAY_STR)
    var callsPerDay: Int = DEFAULT_PER_DAY

    private val counters = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Consumes one slot for [agentId] and reports whether the call is within both windows.
     *
     * Fails CLOSED by construction: there is no store to be unavailable, and an unexpected agent id
     * simply gets its own fresh window rather than bypassing the check.
     */
    fun check(agentId: String): Outcome {
        if (!enabled) return Outcome.ALLOWED
        val now = Instant.now(clock).epochSecond
        val minuteKey = "$agentId|m|${now / SECONDS_PER_MINUTE}"
        val dayKey = "$agentId|d|${now / SECONDS_PER_DAY}"
        pruneExpired(now)
        val minute = hit(minuteKey)
        val day = hit(dayKey)
        return when {
            minute > callsPerMinute -> Outcome.THROTTLED_BURST
            day > callsPerDay -> Outcome.THROTTLED_DAILY
            else -> Outcome.ALLOWED
        }
    }

    private fun hit(key: String): Long = counters.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()

    /**
     * Drops counters for windows that have already closed, so the map cannot grow without bound on
     * a long-lived pod. Only ELAPSED windows are removed — never a live one, which would hand a
     * caller a fresh budget by filling the map (an eviction policy that forgets current usage is a
     * rate limiter with a bypass).
     */
    private fun pruneExpired(now: Long) {
        if (counters.size <= MAX_TRACKED_WINDOWS) return
        val liveMinute = "|m|${now / SECONDS_PER_MINUTE}"
        val liveDay = "|d|${now / SECONDS_PER_DAY}"
        counters.keys.removeIf { !it.endsWith(liveMinute) && !it.endsWith(liveDay) }
    }

    enum class Outcome(val reason: String?) {
        ALLOWED(null),
        THROTTLED_BURST("rate limit exceeded"),
        THROTTLED_DAILY("daily call budget exhausted"),
    }

    companion object {
        /**
         * A judgement call with no production traffic behind it — tune from the tool-call metrics,
         * not from taste. An agent answering one user question makes single-digit tool calls; 60/min
         * leaves an order of magnitude of headroom while making a scripted sweep of a consent's
         * transaction history impractical.
         */
        private const val DEFAULT_PER_MINUTE = 60
        const val DEFAULT_PER_MINUTE_STR = "60"

        /** The charter's `runs_per_day`. */
        private const val DEFAULT_PER_DAY = 1000
        const val DEFAULT_PER_DAY_STR = "1000"

        private const val SECONDS_PER_MINUTE = 60L
        private const val SECONDS_PER_DAY = 86_400L

        /** Above this many tracked windows, [pruneExpired] sweeps the closed ones. */
        private const val MAX_TRACKED_WINDOWS = 10_000
    }
}
