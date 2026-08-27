// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.application

import com.openbank.incentive.domain.CodeDigest
import com.openbank.incentive.domain.IncentiveOffer
import com.openbank.incentive.domain.PromoReservation
import java.time.Instant
import java.util.UUID

@Suppress("TooManyFunctions")
interface IncentiveStore {
    suspend fun createOffer(offer: IncentiveOffer): IncentiveOffer
    suspend fun findOffer(id: UUID): IncentiveOffer?
    suspend fun listPublishedOffers(): List<IncentiveOffer>
    suspend fun submitOffer(id: UUID, actor: String): IncentiveOffer
    suspend fun publishOffer(id: UUID, actor: String, at: Instant): IncentiveOffer
    suspend fun addCodes(offerId: UUID, digests: Set<CodeDigest>, actor: String, at: Instant): Int
    suspend fun reserve(command: ReserveIncentive): PromoReservation
    suspend fun commit(id: UUID, actor: String, at: Instant): PromoReservation
    suspend fun release(id: UUID, actor: String, at: Instant): PromoReservation
    suspend fun commitAttributed(
        id: UUID,
        partyRef: String,
        productRef: String,
        actor: String,
        qualifiedAt: Instant,
    ): PromoReservation
    suspend fun releaseAttributed(
        id: UUID,
        partyRef: String,
        productRef: String,
        actor: String,
        at: Instant,
    ): PromoReservation
    suspend fun expireDue(at: Instant): Int
}

data class ReserveIncentive(
    val offerId: UUID,
    val digest: CodeDigest,
    val partyRef: String,
    val productRef: String,
    val attributionRef: UUID?,
    val idempotencyKey: String,
    val actor: String,
    val now: Instant,
    val expiresAt: Instant,
)
