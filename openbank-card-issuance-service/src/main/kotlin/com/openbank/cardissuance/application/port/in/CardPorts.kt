// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.port.`in`

import com.openbank.cardissuance.domain.model.*
import java.time.LocalDate
import java.util.UUID

data class IssueCardCommand(
    val idempotencyKey: String,
    val partyId: UUID,
    val accountId: UUID,
    val productCode: String,
    val cardType: CardType,
    val network: CardNetwork,
    val cardholderName: String,
    val embossedName: String,
    val currency: String,
    val dailyLimitMinorUnits: Long,
    val monthlyLimitMinorUnits: Long,
    val deliveryAddress: String?,
)

data class CardStatusCommand(val cardId: UUID, val reason: String?, val changedBy: String)

data class UpdateLimitsCommand(
    val cardId: UUID,
    val dailyLimitMinorUnits: Long,
    val monthlyLimitMinorUnits: Long,
    val changedBy: String,
)

data class UpdateControlsCommand(
    val cardId: UUID,
    val contactlessEnabled: Boolean,
    val onlineEnabled: Boolean,
    val atmEnabled: Boolean,
    val abroadEnabled: Boolean,
    val changedBy: String,
)

/** Who is asking for a card's PAN/CVV, for the access audit trail. */
data class ReadSecureDetailsQuery(val cardId: UUID, val requestedBy: String)

/**
 * The decrypted synthetic credential. Held in memory for exactly one response — never logged,
 * never cached, never written to an event. See `SyntheticPanGenerator` for why it is synthetic.
 */
data class CardSecureDetails(
    val pan: String,
    val cvv: String,
    val expiryDate: LocalDate,
    val cardholderName: String,
    val network: CardNetwork,
)

/** What a party may still do on a product, per product-catalog's `cardConfig`. */
data class CardEntitlements(
    val productCode: String,
    val maxCards: Int,
    val issued: Int,
    val remaining: Int,
    val virtualCardAllowed: Boolean,
    val singleUseAllowed: Boolean,
    val networks: List<CardNetwork>,
    val tiers: List<String>,
    val monthlyFeePerCard: Double,
    val enabled: Boolean,
    val source: EntitlementSource,
) {
    companion object {
        /** Sentinel for `maxCards`/`remaining` when no cap is known (see [EntitlementSource.FALLBACK]). */
        const val UNLIMITED = -1
    }
}

/**
 * Where an entitlement came from. [FALLBACK] means product-catalog did not answer (or does not
 * know the code) and the permissive default applied — `maxCards`/`remaining` are then
 * [CardEntitlements.UNLIMITED] (`-1`), i.e. "no known cap", not "zero left". A caller that renders
 * a quota must branch on this rather than trust the numbers.
 */
enum class EntitlementSource { CATALOG, FALLBACK }

@Suppress("TooManyFunctions") // one use-case method per card operation (hexagonal)
interface CardUseCase {
    suspend fun issueCard(cmd: IssueCardCommand): Card
    suspend fun activateCard(cmd: CardStatusCommand): Card
    suspend fun blockCard(cmd: CardStatusCommand): Card
    suspend fun suspendCard(cmd: CardStatusCommand): Card
    suspend fun resumeCard(cmd: CardStatusCommand): Card
    suspend fun cancelCard(cmd: CardStatusCommand): Card
    suspend fun readSecureDetails(query: ReadSecureDetailsQuery): CardSecureDetails
    suspend fun getEntitlements(partyId: UUID, productCode: String): CardEntitlements
    suspend fun updateLimits(cmd: UpdateLimitsCommand): Card
    suspend fun updateControls(cmd: UpdateControlsCommand): Card
    suspend fun getCard(id: UUID): Card?
    suspend fun listAll(): List<Card>
    suspend fun listByAccount(accountId: UUID): List<Card>
    suspend fun listByParty(partyId: UUID): List<Card>
}
