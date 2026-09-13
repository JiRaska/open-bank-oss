// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.referral.domain.ReferralInvite
import com.openbank.referral.domain.ReferralProgram
import com.openbank.referral.domain.ReferralReward
import io.smallrye.mutiny.Uni
import java.util.UUID

interface ReferralProgramRepository {
    suspend fun create(program: ReferralProgram): ReferralProgram
    suspend fun find(id: UUID): ReferralProgram?
    suspend fun publish(id: UUID, maker: String, checker: String, at: java.time.Instant): ReferralProgram
}

interface ReferralInviteRepository {
    suspend fun create(invite: ReferralInvite): ReferralInvite
    suspend fun findByToken(tokenHash: String): ReferralInvite?
    suspend fun findByIdempotencyKey(key: String): ReferralInvite?
    suspend fun attribute(id: UUID, refereePartyId: UUID, at: java.time.Instant): ReferralInvite
}

/**
 * [create] and [outcome] persist the reward row and its outbox event(s) in ONE transaction
 * (ADR-0049/ADR-0050) — never a separate "publish" call after the fact. A reward whose event was
 * hand-carried to a live transport outside the state-changing transaction is the exact shape
 * #7190 replaced: a process crash or emit failure between the two could lose the event, or record
 * one for a reward that never committed. The outbox row IS the durable hand-off; a scheduled
 * dispatcher drains it asynchronously (see [ReferralOutboxRepository]).
 */
interface ReferralRewardRepository {
    suspend fun findByInviteAndEvent(inviteId: UUID, eventId: String): ReferralReward?
    suspend fun findByReference(reference: String): ReferralReward?
    suspend fun create(reward: ReferralReward, outbox: List<OutboxMessage>): ReferralReward
    suspend fun outcome(reference: String, status: String, at: java.time.Instant, outbox: OutboxMessage): ReferralReward
}

/** Outbound port for draining the transactional referral outbox (read pending, mark sent/failed). */
interface ReferralOutboxRepository : OutboxRepository {
    fun persistInTransaction(message: OutboxMessage): Uni<Void>
}

interface ReferralAuditRepository {
    suspend fun append(type: String, aggregateId: UUID, actor: String, details: String, at: java.time.Instant)
}
