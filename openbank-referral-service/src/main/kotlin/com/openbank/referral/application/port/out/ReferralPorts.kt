// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.application.port.out

import com.openbank.referral.domain.ReferralEvent
import com.openbank.referral.domain.ReferralInvite
import com.openbank.referral.domain.ReferralProgram
import com.openbank.referral.domain.ReferralPublishOutcome
import com.openbank.referral.domain.ReferralReward
import java.util.UUID

interface ReferralProgramRepository {
    suspend fun create(program: ReferralProgram): ReferralProgram
    suspend fun find(id: UUID): ReferralProgram?
    suspend fun listPublished(): List<ReferralProgram>
    suspend fun publish(id: UUID, maker: String, checker: String, at: java.time.Instant): ReferralProgram
}

interface ReferralInviteRepository {
    suspend fun create(invite: ReferralInvite): ReferralInvite
    suspend fun findByToken(tokenHash: String): ReferralInvite?
    suspend fun findByIdempotencyKey(key: String): ReferralInvite?
    suspend fun attribute(id: UUID, refereePartyId: UUID, at: java.time.Instant): ReferralInvite

    /** Every invite [referrerPartyId] issued, any status. */
    suspend fun listByReferrer(referrerPartyId: UUID): List<ReferralInvite>
}

interface ReferralRewardRepository {
    suspend fun findByInviteAndEvent(inviteId: UUID, eventId: String): ReferralReward?
    suspend fun findByReference(reference: String): ReferralReward?
    suspend fun create(reward: ReferralReward): ReferralReward
    suspend fun outcome(reference: String, status: String, at: java.time.Instant): ReferralReward

    /** Every reward on any of [inviteIds]; empty input returns empty without a query. */
    suspend fun listByInviteIds(inviteIds: List<UUID>): List<ReferralReward>
}

interface ReferralEventPublisher {
    /**
     * Hands [event] to the transport and reports what actually happened. Callers must branch on
     * the returned [ReferralPublishOutcome] — there is deliberately no boolean, so an unwired
     * adapter cannot be mistaken for a delivering one.
     */
    suspend fun publish(event: ReferralEvent): ReferralPublishOutcome
}

interface ReferralAuditRepository {
    suspend fun append(type: String, aggregateId: UUID, actor: String, details: String, at: java.time.Instant)

    /** When each of [inviteIds] was issued, from its `INVITE_ISSUED` row; absent ids are omitted. */
    suspend fun issuedAt(inviteIds: List<UUID>): Map<UUID, java.time.Instant>
}
