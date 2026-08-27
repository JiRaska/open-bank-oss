// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.application

import com.openbank.incentive.domain.CodeDigest
import com.openbank.incentive.domain.IncentiveOffer
import com.openbank.incentive.domain.PromoReservation
import java.time.Instant
import java.util.UUID

interface IncentiveStore {
    suspend fun createOffer(offer: IncentiveOffer): IncentiveOffer
    suspend fun findOffer(id: UUID): IncentiveOffer?
    suspend fun submitOffer(id: UUID, actor: String): IncentiveOffer
    suspend fun publishOffer(id: UUID, actor: String, at: Instant): IncentiveOffer
    suspend fun addCodes(offerId: UUID, digests: Set<CodeDigest>, actor: String, at: Instant): Int
    suspend fun reserve(
        offerId: UUID,
        digest: CodeDigest,
        partyRef: String,
        productRef: String,
        idempotencyKey: String,
        actor: String,
        now: Instant,
        expiresAt: Instant,
    ): PromoReservation
    suspend fun commit(id: UUID, actor: String, at: Instant): PromoReservation
    suspend fun release(id: UUID, actor: String, at: Instant): PromoReservation
    suspend fun expireDue(at: Instant): Int
}
