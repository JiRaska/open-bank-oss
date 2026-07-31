// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval.impl

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.keys.KeyScanArgs
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.coroutines.awaitSuspending
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
 * TTL-bounded (ADR-0155): a [PendingApproval] is not a permanent audit record.
 * A durable-audit requirement would need an additional store; not needed for
 * the pilot.
 */
class RedisApprovalStore(private val redis: ReactiveRedisDataSource, private val clock: Clock) : ApprovalStore {

    private val valueCommands by lazy { redis.value(String::class.java) }
    private val keyCommands by lazy { redis.key(String::class.java) }

    override suspend fun create(
        action: String,
        resourceId: String?,
        makerId: String,
        ttlSeconds: Long,
    ): PendingApproval {
        val approval = PendingApproval(
            id = Ids.newId().toString(),
            action = action,
            resourceId = resourceId,
            makerId = makerId,
            status = ApprovalStatus.PENDING,
            createdAt = OffsetDateTime.now(clock),
        )
        save(approval, ttlSeconds)
        return approval
    }

    override suspend fun find(id: String): PendingApproval? {
        val raw = valueCommands.get(key(id)).awaitSuspending() ?: return null
        return decode(id, raw)
    }

    override suspend fun findPending(limit: Int): List<PendingApproval> {
        val args = KeyScanArgs().match("$KEY_PREFIX*").count(SCAN_COUNT)
        val keys = keyCommands.scan(args).toMulti().collect().asList().awaitSuspending()
        return keys
            .mapNotNull { k ->
                valueCommands.get(k).awaitSuspending()?.let { decode(k.removePrefix(KEY_PREFIX), it) }
            }
            .filter { it.status == ApprovalStatus.PENDING }
            .sortedBy { it.createdAt }
            .take(limit)
    }

    override suspend fun decide(id: String, decidedBy: String, approve: Boolean): PendingApproval? {
        val approval = find(id) ?: return null
        if (decidedBy == approval.makerId) throw SelfApprovalNotAllowedException(approval.makerId)
        // Code review finding: without this, an already APPROVED/REJECTED/EXECUTED approval
        // could be re-decided — flipping an EXECUTED record back to APPROVED and letting the
        // maker replay the original request through AuthorizeInterceptor a second time.
        if (approval.status != ApprovalStatus.PENDING) {
            throw InvalidApprovalStateException(id, ApprovalStatus.PENDING, approval.status)
        }
        val decided = approval.copy(
            status = if (approve) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED,
            decidedBy = decidedBy,
            decidedAt = OffsetDateTime.now(clock),
        )
        save(decided, DECIDED_TTL_SECONDS)
        return decided
    }

    override suspend fun markExecuted(id: String): PendingApproval? {
        val approval = find(id) ?: return null
        // Defense-in-depth: AuthorizeInterceptor only calls this after its own status==APPROVED
        // check, but a second concurrent consumption attempt on the same approval must not
        // silently succeed twice — reject instead of overwriting an already-EXECUTED record.
        if (approval.status != ApprovalStatus.APPROVED) {
            throw InvalidApprovalStateException(id, ApprovalStatus.APPROVED, approval.status)
        }
        val executed = approval.copy(status = ApprovalStatus.EXECUTED)
        save(executed, DECIDED_TTL_SECONDS)
        return executed
    }

    private suspend fun save(approval: PendingApproval, ttlSeconds: Long) {
        valueCommands.set(key(approval.id), encode(approval), SetArgs().ex(ttlSeconds)).awaitSuspending()
    }

    private fun key(id: String) = "$KEY_PREFIX$id"

    // Pipe-delimited, mirroring RedisIdempotencyStore's encoding — the fields
    // (action, ids, enum name, ISO timestamps) never contain the separator.
    private fun encode(a: PendingApproval): String = listOf(
        a.action,
        a.resourceId.orEmpty(),
        a.makerId,
        a.status.name,
        a.createdAt.toString(),
        a.decidedBy.orEmpty(),
        a.decidedAt?.toString().orEmpty(),
    ).joinToString(SEPARATOR)

    private fun decode(id: String, raw: String): PendingApproval? {
        val parts = raw.split(SEPARATOR, limit = FIELD_COUNT)
        if (parts.size < FIELD_COUNT) return null
        return PendingApproval(
            id = id,
            action = parts[ACTION_IDX],
            resourceId = parts[RESOURCE_ID_IDX].ifEmpty { null },
            makerId = parts[MAKER_ID_IDX],
            status = ApprovalStatus.valueOf(parts[STATUS_IDX]),
            createdAt = OffsetDateTime.parse(parts[CREATED_AT_IDX]),
            decidedBy = parts[DECIDED_BY_IDX].ifEmpty { null },
            decidedAt = parts[DECIDED_AT_IDX].ifEmpty { null }?.let(OffsetDateTime::parse),
        )
    }

    private companion object {
        const val KEY_PREFIX = "approval:"
        const val SEPARATOR = "|"
        const val DECIDED_TTL_SECONDS = 86400L
        const val SCAN_COUNT = 500L

        // Field order in the encoded value — must match encode()'s joinToString order.
        const val ACTION_IDX = 0
        const val RESOURCE_ID_IDX = 1
        const val MAKER_ID_IDX = 2
        const val STATUS_IDX = 3
        const val CREATED_AT_IDX = 4
        const val DECIDED_BY_IDX = 5
        const val DECIDED_AT_IDX = 6
        const val FIELD_COUNT = 7
    }
}
