// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.domain

import java.time.Instant
import java.util.UUID

enum class OfferStatus { DRAFT, PENDING_APPROVAL, PUBLISHED, RETIRED }

enum class StackingPolicy { EXCLUSIVE, STACKABLE }

data class OfferRef(val id: UUID, val name: String, val version: Int)

data class IncentiveOffer(
    val ref: OfferRef,
    val productScope: Set<String>,
    val effectiveFrom: Instant,
    val expiresAt: Instant,
    val totalLimit: Int,
    val perPartyLimit: Int,
    val stackingPolicy: StackingPolicy,
    val status: OfferStatus,
    val maker: String,
    val checker: String? = null,
) {
    init {
        require(ref.name.isNotBlank()) { "offer name is required" }
        require(ref.version > 0) { "offer version must be positive" }
        require(productScope.isNotEmpty()) { "product scope is required" }
        require(effectiveFrom < expiresAt) { "offer expiry must follow its effective time" }
        require(totalLimit > 0) { "total limit must be positive" }
        require(perPartyLimit in 1..totalLimit) { "per-party limit must be within total limit" }
        require(maker.isNotBlank()) { "maker is required" }
    }

    fun submit(requester: String): IncentiveOffer {
        require(status == OfferStatus.DRAFT) { "only a draft can be submitted" }
        require(requester == maker) { "only the maker can submit this offer" }
        return copy(status = OfferStatus.PENDING_APPROVAL)
    }

    fun publish(checker: String): IncentiveOffer {
        require(status == OfferStatus.PENDING_APPROVAL) { "offer is not pending approval" }
        require(checker.isNotBlank() && checker != maker) { "checker must be independent" }
        return copy(status = OfferStatus.PUBLISHED, checker = checker)
    }

    fun retire(): IncentiveOffer {
        require(status == OfferStatus.PUBLISHED) { "only a published offer can be retired" }
        return copy(status = OfferStatus.RETIRED)
    }

    fun accepts(productRef: String, at: Instant): Boolean = status == OfferStatus.PUBLISHED &&
        productRef in productScope &&
        !at.isBefore(effectiveFrom) &&
        at.isBefore(expiresAt)
}
