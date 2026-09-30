// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval.impl

import com.openbank.libs.approval.ApprovalLimitExceededException
import com.openbank.libs.approval.ApprovalRequestBinding
import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.redis.client.Response
import org.eclipse.microprofile.config.ConfigProvider
import java.security.MessageDigest
import java.time.Clock
import java.time.OffsetDateTime

/**
 * NOT a CDI bean by itself — same per-service `@Produces` wiring pattern as
 * [com.openbank.libs.idempotency.impl.RedisIdempotencyStore] (see its KDoc for
 * why: not every service configures Redis, so a libs-side bean would break
 * ArC augmentation fleet-wide).
 *
 *     @ApplicationScoped
 *     class ApprovalConfig {
 *         @Produces @ApplicationScoped
 *         fun approvalStore(redis: ReactiveRedisDataSource, clock: Clock): ApprovalStore =
 *             RedisApprovalStore(redis, clock)
 *     }
 *
 * Layout — every key lives under `approval-v2:<namespace>:`, the namespace defaulting to
 * `quarkus.application.name`, so services sharing one Redis never see each other's records:
 * - `<id>`: a HASH per approval, carrying the approval's TTL;
 * - `pending`: a ZSET of PENDING ids scored by expiry, read by [findPending] instead of a keyspace
 *   SCAN; expired members are pruned on every read;
 * - `maker:<sha256(action, maker)>`: a ZSET of one maker's PENDING ids for one action, which is
 *   what [maxPendingPerMakerAction] bounds (config key [MAX_PENDING_CONFIG_KEY]).
 *
 * Every check-and-write ([create], [decide], [markExecuted]) is one Lua script, so it is atomic on
 * the Redis server: of concurrent callers racing the same transition, exactly one wins and the
 * rest get [InvalidApprovalStateException]. `RedisApprovalStoreIT` runs the scripts against a real
 * server.
 *
 * Records written by the previous layout (`approval:<id>`, a pipe-delimited string, no service
 * namespace, no request binding) are still found, decided and consumed by id until their TTL (at
 * most 24 h) runs out, so an approval id already held by application code keeps working across the
 * deploy. They are moved into this service's namespace on first write, carry no
 * [PendingApproval.requestFingerprint], and so can never satisfy an intercepted request; nor are
 * they listed by [findPending].
 *
 * TTL-bounded (ADR-0155): a [PendingApproval] is not a permanent audit record.
 */
class RedisApprovalStore(
    private val redis: ReactiveRedisDataSource,
    private val clock: Clock,
    namespace: String = configuredNamespace(),
    private val maxPendingPerMakerAction: Int = configuredMaxPending(),
) : ApprovalStore {

    init {
        require(namespace.isNotBlank()) { "approval store namespace must not be blank" }
        require(maxPendingPerMakerAction > 0) { "maxPendingPerMakerAction must be positive" }
    }

    private val prefix = "$KEY_PREFIX$namespace:"
    private val pendingIndex = "${prefix}pending"
    private val hashCommands by lazy { redis.hash(String::class.java) }

    override suspend fun create(
        action: String,
        resourceId: String?,
        makerId: String,
        ttlSeconds: Long,
        binding: ApprovalRequestBinding?,
    ): PendingApproval {
        val approval = PendingApproval(
            id = Ids.newId().toString(),
            action = action,
            resourceId = resourceId,
            makerId = makerId,
            status = ApprovalStatus.PENDING,
            createdAt = OffsetDateTime.now(clock),
            requestFingerprint = binding?.fingerprint,
            summary = binding?.summary,
        )
        val created = eval(
            CREATE_SCRIPT,
            listOf(key(approval.id), pendingIndex, makerIndex(action, makerId)),
            approval.id,
            clock.millis().toString(),
            ttlSeconds.toString(),
            maxPendingPerMakerAction.toString(),
            *fields(approval).toTypedArray(),
        )
        if (created?.toInteger() != 1) throw ApprovalLimitExceededException(action, maxPendingPerMakerAction)
        return approval
    }

    override suspend fun find(id: String): PendingApproval? = findCurrent(id) ?: findLegacy(id)?.second

    override suspend fun findPending(limit: Int): List<PendingApproval> {
        exec("ZREMRANGEBYSCORE", pendingIndex, "-inf", clock.millis().toString())
        val ids = exec("ZRANGE", pendingIndex, "0", (limit - 1).coerceAtLeast(0).toString())
            ?.map { it.toString() }
            .orEmpty()
        return ids
            .mapNotNull { findCurrent(it) }
            .filter { it.status == ApprovalStatus.PENDING }
            .sortedBy { it.createdAt }
            .take(limit)
    }

    override suspend fun decide(id: String, decidedBy: String, approve: Boolean): PendingApproval? {
        if (!migrateLegacy(id)) return null
        val newStatus = if (approve) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED
        val result = eval(
            DECIDE_SCRIPT,
            listOf(key(id), pendingIndex),
            id,
            decidedBy,
            newStatus.name,
            OffsetDateTime.now(clock).toString(),
            DECIDED_TTL_SECONDS.toString(),
        ) ?: return null
        return when (result[0].toString()) {
            MISSING -> null
            // Segregation of duties is reported before the status: a maker re-deciding their own
            // settled approval is told why they may not, not that it is in the wrong state.
            SELF -> throw SelfApprovalNotAllowedException(result[1].toString())
            STATE -> throw InvalidApprovalStateException(id, ApprovalStatus.PENDING, statusOf(result))
            else -> findCurrent(id)
        }
    }

    override suspend fun markExecuted(id: String): PendingApproval? {
        if (!migrateLegacy(id)) return null
        val result = eval(MARK_EXECUTED_SCRIPT, listOf(key(id))) ?: return null
        return when (result[0].toString()) {
            MISSING -> null
            STATE -> throw InvalidApprovalStateException(id, ApprovalStatus.APPROVED, statusOf(result))
            else -> findCurrent(id)
        }
    }

    private fun statusOf(result: Response) = ApprovalStatus.valueOf(result[1].toString())

    private suspend fun findCurrent(id: String): PendingApproval? {
        if (!isPlainId(id)) return null
        val fields = hashCommands.hgetall(key(id)).awaitSuspending()
        if (fields.isNullOrEmpty()) return null
        return decodeHash(id, fields)
    }

    /**
     * Ensures a legacy record for [id], if any, has been moved into this namespace. Returns `false`
     * only when neither layout holds the id.
     */
    private suspend fun migrateLegacy(id: String): Boolean {
        if (!isPlainId(id)) return false
        if (exec("EXISTS", key(id))?.toInteger() == 1) return true
        val (raw, legacy) = findLegacy(id) ?: return false
        eval(
            MIGRATE_SCRIPT,
            listOf("$LEGACY_KEY_PREFIX$id", key(id)),
            raw,
            *fields(legacy).toTypedArray(),
            legacy.decidedBy.orEmpty(),
            legacy.decidedAt?.toString().orEmpty(),
        )
        // Either this call moved it, a concurrent one did, or it expired in between.
        return exec("EXISTS", key(id))?.toInteger() == 1
    }

    private suspend fun findLegacy(id: String): Pair<String, PendingApproval>? {
        if (!isPlainId(id)) return null
        val raw = exec("GET", "$LEGACY_KEY_PREFIX$id")?.toString() ?: return null
        return decodeLegacy(id, raw)?.let { raw to it }
    }

    private suspend fun exec(command: String, vararg args: String): Response? =
        redis.execute(command, *args).awaitSuspending()

    private suspend fun eval(script: String, keys: List<String>, vararg args: String): Response? =
        exec("EVAL", script, keys.size.toString(), *keys.toTypedArray(), *args)

    private fun key(id: String) = "$prefix$id"

    private fun makerIndex(action: String, makerId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$action\n$makerId".toByteArray(Charsets.UTF_8))
        return "${prefix}maker:" + digest.joinToString("") { "%02x".format(it) }
    }

    /** Ids are server-generated; anything else (e.g. `pending`, or a pattern) is never an approval id. */
    private fun isPlainId(id: String) = id.length in 1..MAX_ID_LENGTH && ID_PATTERN.matches(id) && id != "pending"

    private fun fields(a: PendingApproval): List<String> = listOf(
        a.action,
        a.resourceId.orEmpty(),
        a.makerId,
        a.status.name,
        a.createdAt.toString(),
        a.requestFingerprint.orEmpty(),
        a.summary.orEmpty(),
    )

    private fun decodeHash(id: String, f: Map<String, String>): PendingApproval? {
        val status = f["status"]?.let { s -> ApprovalStatus.entries.firstOrNull { it.name == s } }
        val action = f["action"]
        val makerId = f["makerId"]
        val createdAt = f["createdAt"]
        if (status == null || action == null || makerId == null || createdAt == null) return null
        return PendingApproval(
            id = id,
            action = action,
            resourceId = f["resourceId"]?.ifEmpty { null },
            makerId = makerId,
            status = status,
            createdAt = OffsetDateTime.parse(createdAt),
            decidedBy = f["decidedBy"]?.ifEmpty { null },
            decidedAt = f["decidedAt"]?.ifEmpty { null }?.let(OffsetDateTime::parse),
            requestFingerprint = f["fp"]?.ifEmpty { null },
            summary = f["summary"]?.ifEmpty { null },
        )
    }

    private fun decodeLegacy(id: String, raw: String): PendingApproval? {
        val parts = raw.split(LEGACY_SEPARATOR, limit = LEGACY_FIELD_COUNT)
        if (parts.size < LEGACY_FIELD_COUNT) return null
        val status = ApprovalStatus.entries.firstOrNull { it.name == parts[LEGACY_STATUS_IDX] } ?: return null
        return runCatching {
            PendingApproval(
                id = id,
                action = parts[LEGACY_ACTION_IDX],
                resourceId = parts[LEGACY_RESOURCE_IDX].ifEmpty { null },
                makerId = parts[LEGACY_MAKER_IDX],
                status = status,
                createdAt = OffsetDateTime.parse(parts[LEGACY_CREATED_IDX]),
                decidedBy = parts[LEGACY_DECIDED_BY_IDX].ifEmpty { null },
                decidedAt = parts[LEGACY_DECIDED_AT_IDX].ifEmpty { null }?.let(OffsetDateTime::parse),
            )
        }.getOrNull()
    }

    companion object {
        /** Upper bound on one maker's PENDING approvals per action, unless configured otherwise. */
        const val DEFAULT_MAX_PENDING_PER_MAKER_ACTION = 20
        const val MAX_PENDING_CONFIG_KEY = "openbank.approval.max-pending-per-maker-action"

        private const val KEY_PREFIX = "approval-v2:"
        private const val LEGACY_KEY_PREFIX = "approval:"
        private const val LEGACY_SEPARATOR = "|"
        private const val DECIDED_TTL_SECONDS = 86400L
        private const val MAX_ID_LENGTH = 64
        private val ID_PATTERN = Regex("[A-Za-z0-9-]+")
        private const val MISSING = "MISSING"
        private const val SELF = "SELF"
        private const val STATE = "STATE"

        // Field order of the previous pipe-delimited layout.
        private const val LEGACY_ACTION_IDX = 0
        private const val LEGACY_RESOURCE_IDX = 1
        private const val LEGACY_MAKER_IDX = 2
        private const val LEGACY_STATUS_IDX = 3
        private const val LEGACY_CREATED_IDX = 4
        private const val LEGACY_DECIDED_BY_IDX = 5
        private const val LEGACY_DECIDED_AT_IDX = 6
        private const val LEGACY_FIELD_COUNT = 7

        private fun configuredNamespace(): String = ConfigProvider.getConfig()
            .getOptionalValue("quarkus.application.name", String::class.java)
            .orElseThrow { IllegalStateException("quarkus.application.name must be set to namespace approvals") }

        private fun configuredMaxPending(): Int = ConfigProvider.getConfig()
            .getOptionalValue(MAX_PENDING_CONFIG_KEY, Int::class.javaObjectType)
            .orElse(DEFAULT_MAX_PENDING_PER_MAKER_ACTION)

        // KEYS: item, pending index, maker index.
        // ARGV: id, nowMs, ttlSeconds, limit, action, resourceId, makerId, status, createdAt, fp, summary.
        private const val CREATE_SCRIPT = """
            local now = tonumber(ARGV[2])
            local ttl = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)
            if redis.call('ZCARD', KEYS[3]) >= tonumber(ARGV[4]) then return 0 end
            redis.call('HSET', KEYS[1], 'action', ARGV[5], 'resourceId', ARGV[6], 'makerId', ARGV[7],
                'status', ARGV[8], 'createdAt', ARGV[9], 'fp', ARGV[10], 'summary', ARGV[11],
                'makerIndex', KEYS[3])
            redis.call('EXPIRE', KEYS[1], ttl)
            local expiry = now + ttl * 1000
            for i = 2, 3 do
                redis.call('ZADD', KEYS[i], expiry, ARGV[1])
                if redis.call('PTTL', KEYS[i]) < ttl * 1000 then redis.call('PEXPIRE', KEYS[i], ttl * 1000) end
            end
            return 1
        """

        // KEYS: item, pending index. ARGV: id, decidedBy, newStatus, decidedAt, decidedTtl.
        private const val DECIDE_SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) == 0 then return {'MISSING'} end
            local f = redis.call('HMGET', KEYS[1], 'makerId', 'status', 'makerIndex')
            if f[1] == ARGV[2] then return {'SELF', f[1]} end
            if f[2] ~= 'PENDING' then return {'STATE', f[2]} end
            redis.call('HSET', KEYS[1], 'status', ARGV[3], 'decidedBy', ARGV[2], 'decidedAt', ARGV[4])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))
            redis.call('ZREM', KEYS[2], ARGV[1])
            if f[3] then redis.call('ZREM', f[3], ARGV[1]) end
            return {'OK'}
        """

        // KEYS: item.
        private const val MARK_EXECUTED_SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) == 0 then return {'MISSING'} end
            local s = redis.call('HGET', KEYS[1], 'status')
            if s ~= 'APPROVED' then return {'STATE', s} end
            redis.call('HSET', KEYS[1], 'status', 'EXECUTED')
            return {'OK'}
        """

        // KEYS: legacy item, new item. ARGV: expected legacy value, 7 hash fields, decidedBy, decidedAt.
        // Moves the record only if it is still exactly what was read, so no concurrent write is lost.
        private const val MIGRATE_SCRIPT = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            local pttl = redis.call('PTTL', KEYS[1])
            if pttl <= 0 then return 0 end
            redis.call('HSET', KEYS[2], 'action', ARGV[2], 'resourceId', ARGV[3], 'makerId', ARGV[4],
                'status', ARGV[5], 'createdAt', ARGV[6], 'fp', ARGV[7], 'summary', ARGV[8],
                'decidedBy', ARGV[9], 'decidedAt', ARGV[10])
            redis.call('PEXPIRE', KEYS[2], pttl)
            redis.call('DEL', KEYS[1])
            return 1
        """
    }
}
