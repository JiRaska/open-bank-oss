// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.application.port.out

import com.openbank.referral.domain.ReferralEvent
import com.openbank.referral.domain.ReferralInvite
import com.openbank.referral.domain.ReferralProgram
import com.openbank.referral.domain.ReferralReward
import java.util.UUID

interface ReferralProgramRepository {
    suspend fun create(program: ReferralProgram): ReferralProgram
    suspend fun find(id: UUID): ReferralProgram?
    suspend fun listPublished(at: java.time.Instant): List<ReferralProgram>
    suspend fun publish(id: UUID, maker: String, checker: String, at: java.time.Instant): ReferralProgram
}

interface ReferralInviteRepository {
    suspend fun create(invite: ReferralInvite): ReferralInvite
    suspend fun findByToken(tokenHash: String): ReferralInvite?
    suspend fun findByIdempotencyKey(key: String): ReferralInvite?
    suspend fun attribute(id: UUID, refereePartyId: UUID, at: java.time.Instant): ReferralInvite
}

interface ReferralRewardRepository {
    suspend fun findByInviteAndEvent(inviteId: UUID, eventId: String): ReferralReward?
    suspend fun findByReference(reference: String): ReferralReward?

    /** Persists the reward and its events atomically; broker delivery is handled by the outbox. */
    suspend fun create(reward: ReferralReward, events: List<ReferralEvent>): ReferralReward

    /** Persists the outcome transition and its event atomically. */
    suspend fun outcome(reference: String, status: String, at: java.time.Instant, event: ReferralEvent): ReferralReward
}

interface ReferralAuditRepository {
    suspend fun append(type: String, aggregateId: UUID, actor: String, details: String, at: java.time.Instant)
}
