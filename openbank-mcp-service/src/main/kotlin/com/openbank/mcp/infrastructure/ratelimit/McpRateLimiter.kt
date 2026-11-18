// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.ratelimit

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-caller rate limit and concurrency cap on `tools/call` (#2409, ADR-0383 D5).
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
 * **It runs BEFORE the policy decision.** The PDP is an out-of-process call, so a limiter placed
 * after it lets a flood of calls that are going to be denied anyway cost one PDP round trip each,
 * unbounded. Keyed by the resolved caller identity, so one noisy caller cannot spend another's
 * window. A request with NO resolvable caller shares a single small anonymous bucket
 * ([checkAnonymous]), so unauthenticated floods are bounded too without a per-source key the
 * caller could rotate. [tryAcquire] / [release] additionally cap concurrent in-flight calls per
 * caller, which the time windows cannot: a slow tool call holds a connection for seconds.
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
 * The in-flight counters are in-process for the same reason, with the same replica-count caveat:
 * with N replicas the effective caps are N times the configured values.
 */
@ApplicationScoped
class McpRateLimiter @Inject constructor(
    @ConfigProperty(name = "openbank.mcp.rate-limit.enabled", defaultValue = "true")
    var enabled: Boolean,
    @ConfigProperty(name = "openbank.mcp.rate-limit.calls-per-minute", defaultValue = DEFAULT_PER_MINUTE_STR)
    var callsPerMinute: Int,
    /** Mirrors the `mcp-anonymous` charter's `limits.runs_per_day` (agents.yaml). */
    @ConfigProperty(name = "openbank.mcp.rate-limit.calls-per-day", defaultValue = DEFAULT_PER_DAY_STR)
    var callsPerDay: Int,
    /** Small shared per-minute budget for requests with no resolvable caller (all of them, together). */
    @ConfigProperty(name = "openbank.mcp.rate-limit.anonymous-calls-per-minute", defaultValue = DEFAULT_ANON_STR)
    var anonymousCallsPerMinute: Int,
    /** Max concurrent in-flight tool calls per caller; the excess is throttled like a burst. */
    @ConfigProperty(name = "openbank.mcp.rate-limit.max-in-flight", defaultValue = DEFAULT_IN_FLIGHT_STR)
    var maxInFlight: Int,
) {

    /** Not injected: no `Clock` producer bean exists in this service. Overridden by tests. */
    var clock: Clock = Clock.systemUTC()

    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val inFlight = ConcurrentHashMap<String, Int>()

    /**
     * Consumes one slot for [agentId] and reports whether the call is within both windows.
     *
     * Fails CLOSED by construction: there is no store to be unavailable, and an unexpected agent id
     * simply gets its own fresh window rather than bypassing the check.
     */
    fun check(agentId: String): Outcome = consume("c:$agentId", callsPerMinute)

    /**
     * Consumes one slot from the single shared bucket for requests with no resolvable caller. The
     * key lives in a different namespace from [check]'s, so no caller id can collide with it.
     */
    fun checkAnonymous(): Outcome = consume(ANONYMOUS_KEY, anonymousCallsPerMinute)

    /**
     * Consumes one slot of a coarse SHARED bucket (e.g. one customer-MCP client across all its
     * users and proof keys, ADR-0383 D5.3) with its own, larger per-minute budget. Own key namespace.
     */
    fun checkShared(key: String): Outcome = consume("s:$key", sharedCallsPerMinute)

    /** Per-minute budget of a [checkShared] bucket. Not injected: overridden by tests. */
    var sharedCallsPerMinute: Int = DEFAULT_SHARED_PER_MINUTE

    /**
     * Reserves one in-flight slot for [agentId]; false when the caller already holds
     * [maxInFlight]. Every true MUST be paired with exactly one [release] (use try/finally).
     */
    fun tryAcquire(agentId: String): Boolean {
        if (!enabled) return true
        var acquired = false
        inFlight.compute(agentId) { _, current ->
            val held = current ?: 0
            if (held < maxInFlight) {
                acquired = true
                held + 1
            } else {
                held
            }
        }
        return acquired
    }

    /** Returns a slot taken by a successful [tryAcquire]; drops the entry at zero. */
    fun release(agentId: String) {
        if (!enabled) return
        inFlight.computeIfPresent(agentId) { _, held -> if (held <= 1) null else held - 1 }
    }

    /**
     * True for the FIRST throttled request of [agentId] (null = the anonymous bucket) in the current
     * minute, so a flood writes at most one audit row per window per bucket; every request is still
     * metered by the caller. This keeps a throttled flood from becoming an unbounded audit-write
     * amplifier.
     */
    fun shouldAuditThrottle(agentId: String?): Boolean {
        val now = Instant.now(clock).epochSecond
        val key = agentId?.let { "c:$it" } ?: ANONYMOUS_KEY
        // Refusal-only traffic reaches this without ever calling consume(): prune here too, or a
        // flood of distinct refused subjects grows the map without bound (review of #16721).
        pruneExpired(now)
        return hit("$key|t|${now / SECONDS_PER_MINUTE}") == 1L
    }

    private fun consume(key: String, perMinute: Int): Outcome {
        if (!enabled) return Outcome.ALLOWED
        val now = Instant.now(clock).epochSecond
        val minuteKey = "$key|m|${now / SECONDS_PER_MINUTE}"
        val dayKey = "$key|d|${now / SECONDS_PER_DAY}"
        pruneExpired(now)
        val minute = hit(minuteKey)
        val day = hit(dayKey)
        return when {
            minute > perMinute -> Outcome.THROTTLED_BURST
            day > callsPerDay -> Outcome.THROTTLED_DAILY
            else -> Outcome.ALLOWED
        }
    }

    /** Live window counters held; for the bounded-growth test. */
    internal fun trackedWindows(): Int = counters.size

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
        val liveThrottle = "|t|${now / SECONDS_PER_MINUTE}"
        counters.keys.removeIf { !it.endsWith(liveMinute) && !it.endsWith(liveDay) && !it.endsWith(liveThrottle) }
    }

    enum class Outcome(val reason: String?) {
        ALLOWED(null),
        THROTTLED_BURST("rate limit exceeded"),
        THROTTLED_DAILY("daily call budget exhausted"),
    }

    /** Reason text for a call refused by the in-flight cap (same throttled error family). */
    val inFlightReason: String get() = "too many concurrent calls"

    companion object {
        /** Coarse per-client cap on the customer channel's pre-verify path (ADR-0383 D5.3). */
        const val DEFAULT_SHARED_PER_MINUTE = 600

        /** The documented defaults, for code that builds a limiter outside CDI (tests). */
        fun withDefaults() = McpRateLimiter(
            enabled = true,
            callsPerMinute = DEFAULT_PER_MINUTE,
            callsPerDay = DEFAULT_PER_DAY,
            anonymousCallsPerMinute = DEFAULT_ANON,
            maxInFlight = DEFAULT_IN_FLIGHT,
        )

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

        private const val DEFAULT_ANON = 10
        const val DEFAULT_ANON_STR = "10"

        private const val DEFAULT_IN_FLIGHT = 4
        const val DEFAULT_IN_FLIGHT_STR = "4"

        private const val ANONYMOUS_KEY = "anonymous"

        private const val SECONDS_PER_MINUTE = 60L
        private const val SECONDS_PER_DAY = 86_400L

        /** Above this many tracked windows, [pruneExpired] sweeps the closed ones. */
        private const val MAX_TRACKED_WINDOWS = 10_000
    }
}
