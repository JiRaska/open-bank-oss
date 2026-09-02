// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.domain

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ProductStatus { ACTIVE, INACTIVE, DRAFT, DEPRECATED, ARCHIVED }
enum class ProductType { SAVINGS, CURRENT, LOAN, MORTGAGE, CREDIT_CARD, TERM_DEPOSIT, OVERDRAFT, INVESTMENT }
enum class CardNetwork { VISA, MASTERCARD, AMEX, UNIONPAY }
enum class CardTier { STANDARD, GOLD, PLATINUM, INFINITE }
enum class InterestPayoutFrequency { MONTHLY, QUARTERLY, SEMI_ANNUAL, ANNUAL, AT_MATURITY }
enum class WithdrawalNotice { NONE, DAYS_7, DAYS_14, DAYS_30, DAYS_60, DAYS_90 }
enum class OverdraftType { ARRANGED, UNARRANGED, BOTH }
enum class EligibilitySegment { RETAIL, STUDENT, SENIOR, BUSINESS, PREMIUM, ALL }

data class Fee(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String,
    val amount: Double,
    val currency: String,
    val frequency: String,
    val description: String? = null,
    val waivable: Boolean = false,
    val waiveCondition: String? = null,
)

data class InterestTier(val fromAmount: Double, val toAmount: Double?, val rateAnnual: Double)

data class CardConfig(
    val enabled: Boolean = false,
    val minCards: Int = 0,
    val maxCards: Int = 1,
    val networks: List<CardNetwork> = listOf(CardNetwork.VISA),
    val tiers: List<CardTier> = listOf(CardTier.STANDARD),
    val virtualCardAllowed: Boolean = true,
    val contactlessEnabled: Boolean = true,
    val eligibilityMinAge: Int? = null,
    val eligibilitySegments: List<EligibilitySegment> = listOf(EligibilitySegment.ALL),
    val monthlyFeePerCard: Double = 0.0,
    val cardCurrency: String? = null,
)

data class MultiCurrencyConfig(
    val enabled: Boolean = false,
    /**
     * Declared with a NULLABLE element type on purpose, because that is the truth on the wire.
     * Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check the ELEMENTS
     * of a collection, so `{"supportedCurrencies": [null]}` deserialises happily into a
     * `List<String>` holding a null. Writing the type honestly is what makes
     * [requireSupportedCurrencies] reachable instead of dead code. Read it through that guard,
     * never directly.
     */
    val supportedCurrencies: List<String?> = emptyList(),
    val defaultCurrency: String = "EUR",
    val fxMarginPct: Double = 1.5,
    val fxMarginBuyPct: Double? = null,
    val fxMarginSellPct: Double? = null,
    val crossCurrencyTransferAllowed: Boolean = true,
) {
    /** `IllegalArgumentException` is rendered as a client error by the v1 resource; no
     *  service-local mapper is added (#526). */
    fun requireSupportedCurrencies(): List<String> = supportedCurrencies.mapIndexed { index, code ->
        requireNotNull(code) { "multiCurrencyConfig.supportedCurrencies[$index] must not be null" }
    }
}

data class OverdraftConfig(
    val type: OverdraftType = OverdraftType.ARRANGED,
    val maxLimitAmount: Double,
    val interestRateAnnual: Double,
    val gracePeriodDays: Int = 0,
    val unarrangedDailyFee: Double? = null,
    val unarrangedRateAnnual: Double? = null,
    val autoApprovalEnabled: Boolean = false,
)

data class TermDepositConfig(
    val termMonths: Int,
    val minTermMonths: Int? = null,
    val maxTermMonths: Int? = null,
    val interestRateAnnual: Double,
    val payoutFrequency: InterestPayoutFrequency = InterestPayoutFrequency.AT_MATURITY,
    val autoRenewEnabled: Boolean = true,
    val earlyWithdrawalPenaltyPct: Double = 50.0,
    val earlyWithdrawalNoticeDays: Int = 0,
)

data class SavingsConfig(
    /**
     * Declared with a NULLABLE element type on purpose -- see [MultiCurrencyConfig.supportedCurrencies].
     * Read it through [requireInterestTiers], never directly.
     */
    val interestTiers: List<InterestTier?> = emptyList(),
    val withdrawalNotice: WithdrawalNotice = WithdrawalNotice.NONE,
    val freeWithdrawalsPerMonth: Int = 0,
    val excessWithdrawalFee: Double = 0.0,
    val bonusRateCondition: String? = null,
    val bonusRateAnnual: Double? = null,
) {
    fun requireInterestTiers(): List<InterestTier> = interestTiers.mapIndexed { index, tier ->
        requireNotNull(tier) { "savingsConfig.interestTiers[$index] must not be null" }
    }
}

data class TermsAndConditions(
    val id: String = UUID.randomUUID().toString(),
    val version: String,
    val url: String,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate? = null,
    val language: String = "cs",
    val summary: String? = null,
    // Reference by CODE, deliberately not (code, version) (ADR-0162 D1/version-resolution policy):
    // openbank-document-service enforces at most one PUBLISHED version per code at a time, so a
    // product always resolves to whatever is currently PUBLISHED for this code -- it never needs
    // updating just because document-service published a new template version. An already-signed
    // customer document is unaffected either way: it snapshots the exact template version it was
    // actually rendered from at render time, not this reference.
    val documentTemplateCode: String? = null,
)

data class ProductVersion(
    val version: String,
    val validFrom: LocalDate,
    val validTo: LocalDate? = null,
    val isPublic: Boolean = true,
    val changeNote: String? = null,
    val createdAt: Instant = Instant.EPOCH,
)

data class Product(
    val id: String = UUID.randomUUID().toString(),
    val code: String,
    val name: String,
    val type: String,
    val currency: String,
    val status: ProductStatus = ProductStatus.DRAFT,
    val isPublic: Boolean = true,
    val version: String = "1.0.0",
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
    val baseRate: Double = 0.0,
    val fee: Double = 0.0,
    val fees: List<Fee> = emptyList(),
    val description: String? = null,
    val shortDescription: String? = null,
    val minBalance: Double? = null,
    val maxBalance: Double? = null,
    val cardConfig: CardConfig? = null,
    val multiCurrencyConfig: MultiCurrencyConfig? = null,
    val overdraftConfig: OverdraftConfig? = null,
    val termDepositConfig: TermDepositConfig? = null,
    val savingsConfig: SavingsConfig? = null,
    val termsAndConditions: List<TermsAndConditions> = emptyList(),
    val versionHistory: List<ProductVersion> = emptyList(),
    val tags: List<String> = emptyList(),
    val eligibilitySegments: List<EligibilitySegment> = listOf(EligibilitySegment.ALL),
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
    /** Optimistic concurrency token; mapped from `products.row_version`, never client-generated. */
    val revision: Long = 0,
)
