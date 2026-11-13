// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class DealChannel { PLATFORM, VOICE, ELECTRONIC, INTERNAL }

enum class DocumentType { CONFIRMATION, TERM_SHEET, SIGNED_SCAN, OTHER }

enum class LinkRelation { HEDGE, ROLLOVER, NETTING_SET, AMENDS, ORIGINAL }

enum class Collateralisation { UNCOLLATERALISED, PARTIALLY, ONE_WAY, FULLY }

data class SettlementAccounts(val cashAccountId: String, val securitiesAccountId: String? = null)

data class DealDocument(val documentId: String, val type: DocumentType)

data class DealFee(val type: String, val amount: BigDecimal, val currency: String, val paymentDate: LocalDate)

data class LinkedDeal(val dealId: UUID, val relation: LinkRelation)

data class TradeReporting(
    val uti: String? = null,
    val counterpartyLei: String? = null,
    val clearingObligation: Boolean? = null,
    val collateralisation: Collateralisation? = null,
)

data class RegulatoryTerms(
    val tradeReporting: TradeReporting? = null,
    val centralBankCodes: List<String> = emptyList(),
    val lcrCategory: String? = null,
    val nsfrCategory: String? = null,
)

data class CollateralTerms(
    val securityIsin: String,
    val nominal: BigDecimal,
    val haircutPercent: BigDecimal,
    val dirtyPrice: BigDecimal? = null,
    val substitutionAllowed: Boolean = false,
    val marginCallThreshold: BigDecimal? = null,
)

data class SecurityTerms(
    val securityIsin: String,
    val nominal: BigDecimal,
    val cleanPrice: BigDecimal? = null,
    val yield: BigDecimal? = null,
    val accruedInterest: BigDecimal? = null,
    val settlementPlace: String? = null,
    val custodyAccountId: String? = null,
)

data class FxDealTerms(
    val buyCurrency: String,
    val buyAmount: BigDecimal,
    val sellCurrency: String,
    val sellAmount: BigDecimal,
    val rate: BigDecimal,
    val forwardPoints: BigDecimal? = null,
    val nearDate: LocalDate? = null,
    val farDate: LocalDate? = null,
    val nearRate: BigDecimal? = null,
    val farRate: BigDecimal? = null,
)

enum class FraSettlement { ADVANCE_DISCOUNTED, ARREARS }

data class FraTerms(val period: String, val settlementConvention: FraSettlement = FraSettlement.ADVANCE_DISCOUNTED)

/**
 * Early withdrawal of a deposit or early repayment of a loan (ADR-0344 catalogue): whether it is
 * allowed, the notice it needs, and the penalty in basis points of the amount withdrawn.
 */
data class EarlyWithdrawalTerms(
    val allowed: Boolean,
    val noticeDays: Int? = null,
    val penaltyBp: Int? = null,
    val note: String? = null,
)

/**
 * Typed terms of a deposit or loan (MM placement / borrowing, central-bank facilities, LOAN, CASH):
 * the account the principal moves through, the account interest is paid to or from, and the
 * early-withdrawal terms. They replaced the free-form agent `inputs` the ticket used to carry them in.
 */
data class DepositLoanTerms(
    val principalAccountId: String? = null,
    val interestAccountId: String? = null,
    val earlyWithdrawal: EarlyWithdrawalTerms? = null,
)

/** One outbound confirmation the bank recorded as sent (ADR-0344 catalogue). */
data class SentConfirmation(val by: Actor, val at: Instant, val documentId: String?, val channel: String?)

/**
 * The deal header and product-specific terms of the ADR-0344 functional baseline catalogue, beside
 * the flat economic fields the legacy products always had. Everything is optional: a legacy deal
 * carries an empty header and behaves exactly as before. Stored as one versioned JSON document.
 */
data class DealHeader(
    val legalEntityId: String? = null,
    val counterpartyReference: String? = null,
    val channel: DealChannel? = null,
    val tradeDateTime: Instant? = null,
    val effectiveDate: LocalDate? = null,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
    val jurisdiction: String? = null,
    val calendars: List<String> = emptyList(),
    val businessDayConvention: String? = null,
    val rollDirection: RollDirection = RollDirection.FROM_END,
    val fixedConvention: Boolean = false,
    val notionalExchange: Boolean? = null,
    val singleCurrency: Boolean? = null,
    val note: String? = null,
    val settlementAccounts: SettlementAccounts? = null,
    val documents: List<DealDocument> = emptyList(),
    val fees: List<DealFee> = emptyList(),
    val linkedDeals: List<LinkedDeal> = emptyList(),
    val originalDealId: UUID? = null,
    val regulatory: RegulatoryTerms? = null,
    val collateral: CollateralTerms? = null,
    val securityTerms: SecurityTerms? = null,
    val fxTerms: FxDealTerms? = null,
    val fraTerms: FraTerms? = null,
    val depositLoanTerms: DepositLoanTerms? = null,
    val bookId: String? = null,
    val confirmationsSent: List<SentConfirmation> = emptyList(),
) {
    @Suppress("CyclomaticComplexMethod", "LongMethod") // one guard per optional field
    fun validate() {
        listOf(
            "documents" to documents,
            "fees" to fees,
            "linkedDeals" to linkedDeals,
            "calendars" to calendars,
        ).forEach { (n, l) ->
            require(l.size <= MAX_ITEMS) { "$n has at most $MAX_ITEMS entries" }
        }
        require(counterpartyReference == null || counterpartyReference.length <= MAX_REF) {
            "counterpartyReference has at most $MAX_REF characters"
        }
        require(jurisdiction == null || COUNTRY.matches(jurisdiction)) { "jurisdiction must be ISO 3166 alpha-2" }
        require(note == null || note.length <= MAX_NOTE) { "note has at most $MAX_NOTE characters" }
        if (validFrom != null &&
            validTo != null
        ) {
            require(validTo.isAfter(validFrom)) { "validTo must be after validFrom" }
        }
        require(businessDayConvention == null || businessDayConvention in CONVENTIONS) {
            "businessDayConvention must be one of $CONVENTIONS"
        }
        fees.forEach {
            require(it.amount.signum() >= 0 && it.amount.scale() <= 2) { "a fee amount is non-negative, at most 2 dp" }
            require(CCY.matches(it.currency)) { "a fee currency is ISO 4217" }
        }
        require(linkedDeals.map { it.dealId }.toSet().size == linkedDeals.size) { "a deal is linked once" }
        regulatory?.tradeReporting?.counterpartyLei?.let {
            require(LEI.matches(it)) { "counterpartyLei must be 20 A-Z/0-9" }
        }
        regulatory?.tradeReporting?.uti?.let { require(it.length <= MAX_UTI) { "uti has at most $MAX_UTI characters" } }
        collateral?.let {
            require(ISIN.matches(it.securityIsin)) { "collateral.securityIsin is not an ISIN" }
            require(it.nominal.signum() > 0) { "collateral nominal must be positive" }
            require(it.haircutPercent.signum() >= 0 && it.haircutPercent <= HUNDRED) { "haircutPercent is 0..100" }
        }
        securityTerms?.let {
            require(ISIN.matches(it.securityIsin)) { "securityIsin is not an ISIN" }
            require(it.nominal.signum() > 0) { "nominal must be positive" }
            require(it.cleanPrice == null || it.yield == null) { "give a clean price or a yield, not both" }
        }
        listOfNotNull(
            regulatory?.lcrCategory,
            regulatory?.nsfrCategory,
            legalEntityId,
            jurisdiction,
            bookId,
            businessDayConvention,
        ).plus(regulatory?.centralBankCodes.orEmpty()).plus(calendars).forEach {
            require(CODE.matches(it)) { "'$it' is not a code (1..32 of A-Z, 0-9, '_', '.', '-')" }
        }
        fees.forEach { require(CODE.matches(it.type)) { "fee type '${it.type}' is not a code" } }
        documents.forEach {
            require(it.documentId.length <= MAX_REF && it.documentId.none(Char::isISOControl)) {
                "documentId is invalid"
            }
        }
        require(counterpartyReference == null || counterpartyReference.none(Char::isISOControl)) {
            "counterpartyReference has control characters"
        }
        fraTerms?.let { require(FRA_PERIOD.matches(it.period)) { "FRA period is <start>x<end> in months, e.g. 3x6" } }
        depositLoanTerms?.let(::validateDepositLoan)
    }

    private fun validateDepositLoan(d: DepositLoanTerms) {
        listOfNotNull(d.principalAccountId, d.interestAccountId).forEach {
            require(it.isNotBlank() && it.length <= MAX_REF && it.none(Char::isISOControl)) {
                "a deposit/loan account id is 1..$MAX_REF printable characters"
            }
        }
        d.earlyWithdrawal?.let { e ->
            require(e.noticeDays == null || e.noticeDays in 0..MAX_NOTICE_DAYS) { "noticeDays is 0..$MAX_NOTICE_DAYS" }
            require(e.penaltyBp == null || e.penaltyBp in 0..MAX_PENALTY_BP) { "penaltyBp is 0..$MAX_PENALTY_BP" }
            require(e.allowed || (e.noticeDays == null && e.penaltyBp == null)) {
                "notice and penalty apply only when early withdrawal is allowed"
            }
            require(e.note == null || (e.note.length <= MAX_EW_NOTE && e.note.none(Char::isISOControl))) {
                "an early-withdrawal note has at most ${MAX_EW_NOTE} printable characters"
            }
        }
    }

    companion object {
        val EMPTY = DealHeader()
        const val SCHEMA_VERSION = 1
        const val MAX_ITEMS = 100
        private const val MAX_REF = 64
        private const val MAX_NOTE = 2000
        private const val MAX_UTI = 52
        private const val MAX_NOTICE_DAYS = 3660
        private const val MAX_EW_NOTE = 256
        private const val MAX_PENALTY_BP = 10_000
        private val HUNDRED = BigDecimal("100")
        private val COUNTRY = Regex("^[A-Z]{2}$")
        private val CODE = Regex("^[A-Z0-9_.-]{1,32}$")
        private val CCY = Regex("^[A-Z]{3}$")
        private val LEI = Regex("^[A-Z0-9]{20}$")
        private val ISIN = Regex("^[A-Z]{2}[A-Z0-9]{9}[0-9]$")
        private val FRA_PERIOD = Regex("^[0-9]{1,2}x[0-9]{1,2}$")
        val CONVENTIONS = setOf("UNADJUSTED", "FOLLOWING", "MODIFIED_FOLLOWING", "PRECEDING", "MODIFIED_PRECEDING")
    }
}
