// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import com.openbank.libs.security.DpopReplayStore
import com.openbank.libs.security.ReplayClaim
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.redis.client.Response
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * The shared customer-channel state could not answer (Valkey down, timed out, or an unexpected
 * reply). [CustomerChannelAdmission] maps it to 503 and NEVER admits: a store that cannot say
 * "not replayed" or "within budget" is treated as having said no (ADR-0383 P4, #16590).
 */
class SharedStoreUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** One Valkey command with a bounded wait; every failure becomes [SharedStoreUnavailableException]. */
internal class ValkeyCommands(private val redis: ReactiveRedisDataSource, private val timeout: Duration) {
    init {
        require(!timeout.isNegative && !timeout.isZero) { "shared-store timeout must be positive" }
    }

    @Suppress("TooGenericExceptionCaught", "SpreadOperator") // any client failure must fail closed
    suspend fun execute(command: String, vararg args: String): Response? = try {
        redis.execute(command, *args).ifNoItem().after(timeout).fail().awaitSuspending()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SharedStoreUnavailableException("customer-channel shared store unavailable ($command)", e)
    }
}

/**
 * Shared [DpopReplayStore] (ADR-0320 P2 / ADR-0383 P4): every mcp-service replica claims a
 * (`jkt`, `jti`) in the same Valkey, so a proof admitted by one pod is REPLAYED on every other one,
 * and a pod roll does not reopen the window.
 *
 * - [firstUse] is `SET key 1 NX PX ttl` — atomic: of any number of concurrent claims, across any
 *   number of pods, exactly one gets `OK`. TTL is the verifier's proof window
 *   ([com.openbank.libs.security.DpopPolicy.replayTtl]), counted on the Valkey clock.
 * - [isUsed] is `EXISTS key`, a peek that writes nothing.
 * - Fail-closed: a claim that cannot be answered is [ReplayClaim.UNAVAILABLE] (503); a peek that
 *   cannot be answered THROWS [SharedStoreUnavailableException] rather than returning `false`,
 *   because "not known to be replayed" would let the caller go on to spend budget against a store
 *   it already knows is down.
 * - No per-key quota: the in-memory store needs one to protect its own heap, Valkey's memory is
 *   bounded by the proof-key bucket that [CustomerChannelAdmission] spends BEFORE the claim.
 */
class ValkeyDpopReplayStore internal constructor(private val commands: ValkeyCommands) : DpopReplayStore {
    constructor(redis: ReactiveRedisDataSource, timeout: Duration) : this(ValkeyCommands(redis, timeout))

    override suspend fun firstUse(jkt: String, jti: String, ttl: Duration, now: Instant): ReplayClaim {
        val reply = try {
            commands.execute("SET", key(jkt, jti), "1", "NX", "PX", ttl.toMillis().coerceAtLeast(1).toString())
        } catch (_: SharedStoreUnavailableException) {
            return ReplayClaim.UNAVAILABLE
        }
        // SET NX answers OK when it wrote, a null reply when the key already existed.
        return if (reply != null && reply.toString() == "OK") ReplayClaim.FIRST_USE else ReplayClaim.REPLAYED
    }

    override suspend fun isUsed(jkt: String, jti: String, now: Instant): Boolean {
        val reply = commands.execute("EXISTS", key(jkt, jti))
            ?: throw SharedStoreUnavailableException("customer-channel shared store gave no EXISTS reply")
        return reply.toLong() > 0
    }

    companion object {
        const val KEY_PREFIX = "mcp:customer:dpop:"

        fun key(jkt: String, jti: String): String = "$KEY_PREFIX$jkt:$jti"
    }
}

/**
 * The ADR-0383 D5.3 budgets on a SHARED fixed window per key (Valkey), so N replicas give a caller
 * N × nothing extra. Same budgets and window shapes as the in-process [McpRateLimiter][
 * com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter]: proof key and (subject, client) get a
 * minute AND a day window, the per-client cap is minute-only.
 *
 * One window is one Lua call — `INCR` then, on the first hit, `PEXPIRE` — so a counter can never be
 * left without a TTL (the INCR-then-separate-EXPIRE race that a crash between the two would leave).
 * Every failure is [SharedStoreUnavailableException] (503), never "allowed".
 */
class ValkeyCustomerThrottles internal constructor(
    private val commands: ValkeyCommands,
    private val limits: Limits,
    private val clock: Clock,
) : CustomerChannelAdmission.Throttles {
    constructor(redis: ReactiveRedisDataSource, timeout: Duration, limits: Limits, clock: Clock) :
        this(ValkeyCommands(redis, timeout), limits, clock)

    data class Limits(val perMinute: Int, val perDay: Int, val sharedPerMinute: Int, val enabled: Boolean = true)

    override suspend fun proofKey(thumbprint: String): String? =
        windows("jkt:$thumbprint", limits.perMinute, limits.perDay)

    override suspend fun client(clientId: String): String? = windows("client:$clientId", limits.sharedPerMinute, null)

    override suspend fun subject(subject: String, clientId: String): String? =
        windows("subject:$subject|$clientId", limits.perMinute, limits.perDay)

    private suspend fun windows(key: String, perMinute: Int, perDay: Int?): String? {
        if (!limits.enabled) return null
        val now = clock.instant().epochSecond
        val minute = hit("$KEY_PREFIX$key:m:${now / MINUTE}", MINUTE)
        if (minute > perMinute) return BURST
        if (perDay != null && hit("$KEY_PREFIX$key:d:${now / DAY}", DAY) > perDay) return DAILY
        return null
    }

    private suspend fun hit(key: String, windowSeconds: Long): Long {
        val ttlMs = (windowSeconds + GRACE_SECONDS) * MILLIS
        val reply = commands.execute("EVAL", INCR_WITH_TTL, "1", key, ttlMs.toString())
            ?: throw SharedStoreUnavailableException("customer-channel shared store gave no counter reply")
        return reply.toLong()
    }

    companion object {
        const val KEY_PREFIX = "mcp:customer:rl:"
        private const val MINUTE = 60L
        private const val DAY = 86_400L
        private const val GRACE_SECONDS = 10L
        private const val MILLIS = 1000L
        private const val BURST = "rate limit exceeded"
        private const val DAILY = "daily call budget exhausted"

        /** Atomic: the counter and its expiry are set in one server-side step. */
        const val INCR_WITH_TTL =
            "local c = redis.call('INCR', KEYS[1]) " +
                "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end " +
                "return c"
    }
}
