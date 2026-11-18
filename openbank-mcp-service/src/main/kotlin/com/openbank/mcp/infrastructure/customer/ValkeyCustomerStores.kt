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
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException

/**
 * The shared customer-channel state could not answer (Valkey down, timed out, or an unexpected
 * reply). [CustomerChannelAdmission] maps it to 503 and NEVER admits: a store that cannot say
 * "not replayed" or "within budget" is treated as having said no (ADR-0383 P4, #16590).
 */
class SharedStoreUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * One Valkey command with a bounded wait. The reply is PARSED inside the same guard, so a timeout, a
 * refused connection, a null where a value was required and a malformed reply all become
 * [SharedStoreUnavailableException] (503) — never a parse exception escaping as a 500 (review of #16791).
 */
internal class ValkeyCommands(private val redis: ReactiveRedisDataSource, private val timeout: Duration) {
    init {
        require(!timeout.isNegative && !timeout.isZero) { "shared-store timeout must be positive" }
    }

    @Suppress("TooGenericExceptionCaught", "SpreadOperator") // any client or parse failure must fail closed
    suspend fun <T> execute(command: String, vararg args: String, parse: (Response?) -> T): T = try {
        parse(redis.execute(command, *args).ifNoItem().after(timeout).fail().awaitSuspending())
    } catch (e: CancellationException) {
        throw e
    } catch (e: SharedStoreUnavailableException) {
        throw e
    } catch (e: Exception) {
        throw SharedStoreUnavailableException("customer-channel shared store unavailable ($command)", e)
    }

    companion object {
        /** An integer reply; anything else (null, a non-integer) is a store failure. */
        fun integer(command: String): (Response?) -> Long = { reply ->
            reply?.toLong()
                ?: throw SharedStoreUnavailableException("customer-channel shared store gave no $command reply")
        }
    }
}

/**
 * Key-name pseudonymisation (review of #16791, GDPR): the customer `sub` and the proof-key thumbprint
 * never appear in a Valkey key in clear. Each is HMAC-SHA-256'd with a deployment secret, so a key
 * dump names no customer and cannot be joined back without the secret; fixed-length hex also removes
 * any separator ambiguity between fields.
 */
class ValkeyKeyHasher(secret: String) {
    private val key: SecretKeySpec

    init {
        require(secret.toByteArray(StandardCharsets.UTF_8).size >= MIN_SECRET_BYTES) {
            "the customer-channel key HMAC secret must be at least $MIN_SECRET_BYTES bytes"
        }
        key = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), ALGORITHM)
    }

    /** Hex HMAC over the fields, each length-prefixed so no two field lists share an input. */
    fun hash(vararg fields: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(key)
        fields.forEach { f ->
            val bytes = f.toByteArray(StandardCharsets.UTF_8)
            mac.update("${bytes.size}:".toByteArray(StandardCharsets.US_ASCII))
            mac.update(bytes)
        }
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MIN_SECRET_BYTES = 32
        private const val ALGORITHM = "HmacSHA256"
    }
}

/**
 * Shared [DpopReplayStore] (ADR-0320 P2 / ADR-0383 P4): every mcp-service replica claims a
 * (`jkt`, `jti`) in the same Valkey, so a proof admitted by one pod is REPLAYED on every other one,
 * and a pod roll does not reopen the window.
 *
 * - [firstUse] is `SET key 1 NX PX ttl` — atomic: of any number of concurrent claims, across any
 *   number of pods, exactly one gets `OK`. `PX` is RELATIVE: the entry lives `ttl` from the moment
 *   Valkey writes it, so only clock RATE drift matters, not wall-clock offset.
 * - [isUsed] is `EXISTS key`, a peek that writes nothing.
 * - Fail-closed: a claim that cannot be answered (or answers anything but `OK` / null) is
 *   [ReplayClaim.UNAVAILABLE] (503); a peek that cannot be answered THROWS
 *   [SharedStoreUnavailableException] rather than returning `false`.
 * - Requires `maxmemory-policy noeviction`: an evicted entry silently reopens its replay window.
 * - No per-key quota: Valkey memory is bounded by the proof-key bucket that [CustomerChannelAdmission]
 *   spends BEFORE the claim.
 */
class ValkeyDpopReplayStore internal constructor(
    private val commands: ValkeyCommands,
    private val hasher: ValkeyKeyHasher,
) : DpopReplayStore {
    constructor(redis: ReactiveRedisDataSource, timeout: Duration, hasher: ValkeyKeyHasher) :
        this(ValkeyCommands(redis, timeout), hasher)

    override suspend fun firstUse(jkt: String, jti: String, ttl: Duration, now: Instant): ReplayClaim = try {
        commands.execute("SET", key(jkt, jti), "1", "NX", "PX", ttl.toMillis().coerceAtLeast(1).toString()) {
            // SET NX answers OK when it wrote, a null reply when the key already existed; else odd.
            when {
                it == null -> ReplayClaim.REPLAYED
                it.toString() == "OK" -> ReplayClaim.FIRST_USE
                else -> throw SharedStoreUnavailableException("customer-channel shared store gave an odd SET reply")
            }
        }
    } catch (_: SharedStoreUnavailableException) {
        ReplayClaim.UNAVAILABLE
    }

    override suspend fun isUsed(jkt: String, jti: String, now: Instant): Boolean =
        commands.execute("EXISTS", key(jkt, jti), parse = ValkeyCommands.integer("EXISTS")) > 0

    /** `jti` is a random proof id and stays readable; `jkt` (a stable per-device key id) is hashed. */
    fun key(jkt: String, jti: String): String = "$KEY_PREFIX${hasher.hash(jkt)}:$jti"

    companion object {
        const val KEY_PREFIX = "mcp:customer:dpop:"
    }
}

/**
 * The ADR-0383 D5.3 budgets on a SHARED fixed window per key (Valkey), so N replicas give a caller
 * N × nothing extra. Same budgets and window shapes as the in-process [McpRateLimiter][
 * com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter]: proof key and (subject, client) get a
 * minute AND a day window, the per-client cap is minute-only.
 *
 * One window is one Lua call — `INCR` then, on the first hit, `PEXPIRE` — so a counter can never be
 * left without a TTL. Every failure is [SharedStoreUnavailableException] (503), never "allowed".
 * Subject and proof key are HMAC'd ([ValkeyKeyHasher]); the client id is a public allowlist entry.
 */
class ValkeyCustomerThrottles internal constructor(
    private val commands: ValkeyCommands,
    private val limits: Limits,
    private val clock: Clock,
    private val hasher: ValkeyKeyHasher,
) : CustomerChannelAdmission.Throttles {
    constructor(
        redis: ReactiveRedisDataSource,
        timeout: Duration,
        limits: Limits,
        clock: Clock,
        hasher: ValkeyKeyHasher,
    ) :
        this(ValkeyCommands(redis, timeout), limits, clock, hasher)

    data class Limits(val perMinute: Int, val perDay: Int, val sharedPerMinute: Int, val enabled: Boolean = true)

    override suspend fun proofKey(thumbprint: String): String? =
        windows("jkt:${hasher.hash(thumbprint)}", limits.perMinute, limits.perDay)

    override suspend fun client(clientId: String): String? =
        windows("client:${hasher.hash(clientId)}", limits.sharedPerMinute, null)

    override suspend fun subject(subject: String, clientId: String): String? =
        windows("subject:${hasher.hash(subject, clientId)}", limits.perMinute, limits.perDay)

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
        return commands.execute(
            "EVAL",
            INCR_WITH_TTL,
            "1",
            key,
            ttlMs.toString(),
            parse = ValkeyCommands.integer("EVAL"),
        )
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
