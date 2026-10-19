// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.reference

import java.math.BigDecimal
import java.time.LocalDate

/**
 * The reference-data kinds of ADR-0344 D3. [wire] is the path segment and the stored value.
 * A kind's payload type is [payloadType]; [ReferencePayload.validate] holds its rules.
 */
enum class ReferenceKind(val wire: String) {
    CALENDAR("calendar"),
    INDEX("index"),
    COUNTERPARTY("counterparty"),
    SSI("ssi"),
    NETTING("netting"),
    SECURITY("security"),
    BOOK("book"),
    BUCKET_SET("bucket-set"),
    REGULATORY_CODE("regulatory-code"),

    // ADR-0344 functional baseline catalogue. product-type has a typed payload (it is the product
    // mandate); the others are generic records — a code or id, a name, attributes as given — until
    // a slice needs to read one, at which point it gets a typed payload like the kinds above.
    PRODUCT_TYPE("product-type"),
    PARTY_GROUP("party-group"),
    PARTY_TYPE("party-type"),
    COUNTRY("country"),
    DOCUMENT_TYPE("document-type"),
    ACCOUNT("account"),
    SETTLEMENT_PLACE("settlement-place"),
    LOAN_TYPE("loan-type"),
    COMMUNICATION_CHANNEL("communication-channel"),
    MARKET_FACTOR("market-factor"),
    OWN_CAPITAL_ITEM("own-capital-item"),
    PERIOD_LENGTH("period-length"),
    SCENARIO("scenario"),
    BEHAVIOURAL_SCENARIO("behavioural-scenario"),
    CALCULATION_SETTINGS("calculation-settings"),
    LIMIT_SETTINGS("limit-settings"),
    DERIVATIVE_CONVERSION_FACTOR("derivative-conversion-factor"),
    COLLATERAL_HAIRCUT("collateral-haircut"),
    PORTFOLIO_GROUP("portfolio-group"),
    ACCOUNTING_SETTINGS("accounting-settings"),
    BALANCE_SHEET_SIDE("balance-sheet-side"),
    DEALING_FILTER("dealing-filter"),

    // ADR-0344 contract 8.5: a bank-published rate sheet (tenor -> rates), effective-dated, four-eyes.
    INTERNAL_RATE_SHEET("internal-rate-sheet"),
    ;

    /** The kinds stored as [GenericPayload]. */
    val generic: Boolean get() = ordinal > PRODUCT_TYPE.ordinal

    companion object {
        fun fromWire(value: String): ReferenceKind = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("unknown reference kind '$value' (one of ${entries.map { it.wire }})")
    }
}

/** A pointer from one record (or a deal) to another reference record. */
data class ReferenceRef(val kind: ReferenceKind, val key: String) {
    override fun toString(): String = "${kind.wire}:$key"
}

/**
 * A kind's typed payload. Every payload knows its own key (so a proposal whose path key disagrees
 * with its body is refused) and the records it names ([references], the dependency graph).
 */
sealed interface ReferencePayload {
    val recordKind: ReferenceKind

    fun key(): String

    /** Throws IllegalArgumentException (400) on the first rule broken. */
    fun validate()

    fun references(): List<ReferenceRef> = emptyList()
}

private val CURRENCY = Regex("^[A-Z]{3}$")
private val COUNTRY = Regex("^[A-Z]{2}$")
private val LEI = Regex("^[A-Z0-9]{20}$")
private val BIC = Regex("^[A-Z0-9]{8}([A-Z0-9]{3})?$")
private val ISIN = Regex("^[A-Z]{2}[A-Z0-9]{9}[0-9]$")
private val CODE = Regex("^[A-Z0-9][A-Z0-9_.-]{0,63}$")
private val TENOR = Regex("^(ON|[0-9]+[DWMY])$")
private const val MAX_TEXT = 200
private const val MAX_ACCOUNT = 34

private fun requireCode(value: String, field: String) =
    require(CODE.matches(value)) { "$field '$value' must be 1..64 of A-Z, 0-9, '_', '.', '-'" }

private fun requireText(value: String, field: String) =
    require(value.isNotBlank() && value.length <= MAX_TEXT) { "$field must be 1..$MAX_TEXT characters" }

private fun requireCurrency(value: String, field: String = "currency") =
    require(CURRENCY.matches(value)) { "$field must be an ISO 4217 code" }

enum class BaseCalendar { CERTIS, TARGET2, WEEKEND_ONLY }

data class CalendarPayload(
    val code: String,
    val baseCalendar: BaseCalendar,
    val extraHolidays: List<LocalDate> = emptyList(),
    val removedHolidays: List<LocalDate> = emptyList(),
) : ReferencePayload {
    override val recordKind get() = ReferenceKind.CALENDAR

    override fun key() = code

    override fun validate() {
        requireCode(code, "code")
        require(extraHolidays.intersect(removedHolidays.toSet()).isEmpty()) {
            "a date cannot be both an extra and a removed holiday"
        }
    }
}

enum class IndexSource { CNB, ECB, MANUAL, FEED }

data class IndexPayload(
    val code: String,
    val currency: String,
    val tenor: String,
    val dayCount: String,
    val fixingLagBusinessDays: Int,
    val fixingCalendar: String,
    val source: IndexSource,
    val staleAfterBusinessDays: Int = DEFAULT_STALE_AFTER,
    val suspiciousJumpBp: Int = DEFAULT_JUMP_BP,
) : ReferencePayload {
    override val recordKind get() = ReferenceKind.INDEX

    override fun key() = code

    override fun validate() {
        requireCode(code, "code")
        requireCurrency(currency)
        require(TENOR.matches(tenor)) { "tenor must be ON or <n>D/W/M/Y" }
        require(dayCount in DAY_COUNTS) { "dayCount must be one of $DAY_COUNTS" }
        require(fixingLagBusinessDays in 0..MAX_LAG) { "fixingLagBusinessDays must be 0..$MAX_LAG" }
        requireCode(fixingCalendar, "fixingCalendar")
        require(staleAfterBusinessDays >= 0) { "staleAfterBusinessDays must not be negative" }
        require(suspiciousJumpBp >= 0) { "suspiciousJumpBp must not be negative" }
    }

    override fun references() = listOf(ReferenceRef(ReferenceKind.CALENDAR, fixingCalendar))

    companion object {
        const val DEFAULT_STALE_AFTER = 1
        const val DEFAULT_JUMP_BP = 50
        const val MAX_LAG = 10
        val DAY_COUNTS = setOf("ACT/360", "ACT/365F", "ACT/ACT_ISDA", "30/360", "30E/360")
    }
}

enum class ReferenceCounterpartyKind { BANK, CENTRAL_BANK, CORPORATE, ISSUER }

data class CounterpartyPayload(
    val id: String,
    val name: String,
    val kind: ReferenceCounterpartyKind,
    val lei: String? = null,
    val countryCode: String,
    val limits: Map<String, BigDecimal> = emptyMap(),
    val nettingAgreementId: String? = null,
) : ReferencePayload {
    override val recordKind get() = ReferenceKind.COUNTERPARTY

    override fun key() = id

    override fun validate() {
        requireCode(id, "id")
        requireText(name, "name")
        require(lei == null || LEI.matches(lei)) { "lei must be 20 characters A-Z/0-9" }
        require(COUNTRY.matches(countryCode)) { "countryCode must be ISO 3166 alpha-2" }
        limits.forEach { (ccy, amount) ->
            requireCurrency(ccy, "limit currency")
            require(amount.signum() >= 0) { "limit in $ccy must not be negative" }
            require(amount.scale() <= 2) { "limit in $ccy has at most 2 decimal places" }
        }
        nettingAgreementId?.let { requireCode(it, "nettingAgreementId") }
    }

    override fun references() = listOfNotNull(nettingAgreementId?.let { ReferenceRef(ReferenceKind.NETTING, it) })
}

enum class SettlementRail { CERTIS, SEPA, TARGET2, SWIFT }

data class SsiPayload(
    val counterpartyId: String,
    val currency: String,
    val rail: SettlementRail,
    val beneficiaryAccount: String,
    val bic: String? = null,
    val intermediaryBic: String? = null,
) : ReferencePayload {
    override val recordKind get() = ReferenceKind.SSI

    override fun key() = "$counterpartyId:$currency"

    override fun validate() {
        requireCode(counterpartyId, "counterpartyId")
        requireCurrency(currency)
        require(beneficiaryAccount.isNotBlank() && beneficiaryAccount.length <= MAX_ACCOUNT) {
            "beneficiaryAccount must be 1..$MAX_ACCOUNT characters"
        }
        require(bic == null || BIC.matches(bic)) { "bic must be 8 or 11 characters" }
        require(intermediaryBic == null || BIC.matches(intermediaryBic)) {
            "intermediaryBic must be 8 or 11 characters"
        }
        if (rail ==
            SettlementRail.SWIFT
        ) {
            require(bic != null) { "a SWIFT instruction needs the beneficiary bank's bic" }
        }
    }

    override fun references() = listOf(ReferenceRef(ReferenceKind.COUNTERPARTY, counterpartyId))
}

data class NettingPayload(
    val id: String,
    val counterpartyId: String,
    val currencies: List<String>,
    val paymentNetting: Boolean,
) : ReferencePayload {
    override val recordKind get() = ReferenceKind.NETTING

    override fun key() = id

    override fun validate() {
        requireCode(id, "id")
        requireCode(counterpartyId, "counterpartyId")
        require(currencies.isNotEmpty()) { "a netting agreement covers at least one currency" }
        currencies.forEach { requireCurrency(it, "netting currency") }
        require(currencies.toSet().size == currencies.size) { "netting currencies must be distinct" }
    }

    override fun references() = listOf(ReferenceRef(ReferenceKind.COUNTERPARTY, counterpartyId))
}

enum class SecurityType { BOND, TREASURY_BILL, BILL_OF_EXCHANGE }

data class SecurityPayload(
    val isin: String,
    val name: String,
    val issuerId: String,
    val type: SecurityType,
    val currency: String,
    val issueDate: LocalDate,
    val maturityDate: LocalDate,
    val denomination: BigDecimal,
    val couponRate: BigDecimal? = null,
    val couponFrequency: String? = null,
    val dayCount: String,
    val businessDayConvention: String,
    val calendar: String,
) : ReferencePayload {
    override val recordKind get() = ReferenceKind.SECURITY

    override fun key() = isin

    override fun validate() {
        require(ISIN.matches(isin) && isinCheckDigitValid(isin)) { "isin '$isin' is not a valid ISIN" }
        requireText(name, "name")
        requireCode(issuerId, "issuerId")
        requireCurrency(currency)
        require(maturityDate.isAfter(issueDate)) { "maturityDate must be after issueDate" }
        require(denomination.signum() > 0) { "denomination must be positive" }
        require(dayCount in IndexPayload.DAY_COUNTS) { "dayCount must be one of ${IndexPayload.DAY_COUNTS}" }
        require(businessDayConvention in CONVENTIONS) { "businessDayConvention must be one of $CONVENTIONS" }
        requireCode(calendar, "calendar")
        if (type == SecurityType.BOND) {
            require(couponRate != null && couponRate.signum() >= 0) { "a bond needs a non-negative couponRate" }
            require(couponFrequency in FREQUENCIES) { "a bond needs a couponFrequency in $FREQUENCIES" }
        } else {
            require(couponRate == null && couponFrequency == null) { "a discount security carries no coupon" }
        }
    }

    override fun references() = listOf(
        ReferenceRef(ReferenceKind.COUNTERPARTY, issuerId),
        ReferenceRef(ReferenceKind.CALENDAR, calendar),
    )

    companion object {
        val CONVENTIONS = setOf("UNADJUSTED", "FOLLOWING", "MODIFIED_FOLLOWING", "PRECEDING", "MODIFIED_PRECEDING")
        val FREQUENCIES = setOf("MONTHLY", "QUARTERLY", "SEMI_ANNUAL", "ANNUAL")
        private const val ISIN_LETTER_OFFSET = 55
        private const val LUHN_DOUBLE_ABOVE = 9
        private const val DECIMAL = 10

        /** ISO 6166 check digit: letters to numbers (A=10 ... Z=35), then Luhn over the digits. */
        fun isinCheckDigitValid(isin: String): Boolean {
            val digits = isin.dropLast(1).map { c ->
                if (c.isDigit()) c.toString() else (c.code - ISIN_LETTER_OFFSET).toString()
            }
                .joinToString("")
            val sum = digits.reversed().mapIndexed { i, ch ->
                val d = ch - '0'
                if (i % 2 == 0) (d * 2).let { if (it > LUHN_DOUBLE_ABOVE) it - LUHN_DOUBLE_ABOVE else it } else d
            }.sum()
            return (DECIMAL - sum % DECIMAL) % DECIMAL == isin.last() - '0'
        }
    }
}

enum class BookPurpose { BANKING_BOOK, TRADING_BOOK }

enum class Ifrs9Category { AMORTISED_COST, FVOCI, FVTPL }

data class BookPayload(
    val id: String,
    val parentId: String? = null,
    val name: String,
    val purpose: BookPurpose,
    val ifrs9Category: Ifrs9Category,
) : ReferencePayload {
    override val recordKind get() = ReferenceKind.BOOK

    override fun key() = id

    override fun validate() {
        requireCode(id, "id")
        parentId?.let {
            requireCode(it, "parentId")
            require(it != id) { "a book cannot be its own parent" }
        }
        requireText(name, "name")
        // IFRS 9 4.1: a trading book is measured at fair value through profit or loss.
        if (purpose == BookPurpose.TRADING_BOOK) {
            require(ifrs9Category == Ifrs9Category.FVTPL) { "a trading book is FVTPL" }
        }
    }

    override fun references() = listOfNotNull(parentId?.let { ReferenceRef(ReferenceKind.BOOK, it) })
}

data class Bucket(val label: String, val fromDays: Int, val toDays: Int? = null)

data class BucketSetPayload(val id: String, val name: String, val buckets: List<Bucket>) : ReferencePayload {
    override val recordKind get() = ReferenceKind.BUCKET_SET

    override fun key() = id

    /** Buckets are contiguous from day 0, ascending, with only the last open-ended. */
    override fun validate() {
        requireCode(id, "id")
        requireText(name, "name")
        require(buckets.isNotEmpty()) { "a bucket set has at least one bucket" }
        require(buckets.first().fromDays == 0) { "the first bucket starts at day 0" }
        buckets.forEachIndexed { i, b ->
            requireText(b.label, "bucket label")
            val last = i == buckets.lastIndex
            if (!last) {
                val to = requireNotNull(b.toDays) { "only the last bucket may be open-ended" }
                require(to > b.fromDays) { "bucket ${b.label} must end after it starts" }
                require(buckets[i + 1].fromDays == to) { "buckets must be contiguous: ${b.label} ends at $to" }
            } else {
                b.toDays?.let { require(it > b.fromDays) { "bucket ${b.label} must end after it starts" } }
            }
        }
        require(buckets.map { it.label }.toSet().size == buckets.size) { "bucket labels must be distinct" }
    }
}

enum class RegulatoryList { CNB, DISIFE23, EMIR_PRODUCT, LCR, NSFR, TRADE_REPORTING, CLASSIFICATION }

data class RegulatoryCodePayload(val list: RegulatoryList, val code: String, val label: String) : ReferencePayload {
    override val recordKind get() = ReferenceKind.REGULATORY_CODE

    override fun key() = "${list.name}:$code"

    override fun validate() {
        requireCode(code, "code")
        requireText(label, "label")
    }
}

/**
 * product-type (ADR-0344 catalogue): the product mandate that used to live only in configuration —
 * whether the product may be dealt at all, the largest principal per deal by currency (the
 * currency set is the allowed list) and the longest tenor. Once a version is ACTIVE it is the
 * authority; configuration seeds only the products that have no record.
 */
data class ProductTypePayload(
    val code: String,
    val permitted: Boolean,
    val maxPrincipal: Map<String, BigDecimal> = emptyMap(),
    val maxTenorDays: Long? = null,
    val allowedCurrencies: List<String>? = null,
) : ReferencePayload {
    override val recordKind get() = ReferenceKind.PRODUCT_TYPE

    override fun key() = code

    override fun validate() {
        requireCode(code, "code")
        maxPrincipal.forEach { (ccy, amount) ->
            requireCurrency(ccy, "maxPrincipal currency")
            require(amount.signum() > 0) { "maxPrincipal in $ccy must be positive" }
        }
        require(maxTenorDays == null || maxTenorDays > 0) { "maxTenorDays must be positive" }
        allowedCurrencies?.let {
            require(it.toSet() == maxPrincipal.keys) { "allowedCurrencies must equal the maxPrincipal currencies" }
        }
        if (permitted) require(maxPrincipal.isNotEmpty()) { "a permitted product names at least one currency" }
    }
}

/** A catalogue record without a dedicated schema: `code` (or `id`) is the key, `name` required. */
data class GenericPayload(override val recordKind: ReferenceKind, val fields: Map<String, Any?>) : ReferencePayload {
    override fun key(): String = (fields["code"] ?: fields["id"])?.toString() ?: ""

    override fun validate() {
        require(recordKind.generic) { "${recordKind.wire} has a typed payload" }
        requireCode(key(), "code")
        requireText(fields["name"]?.toString() ?: "", "name")
        require(fields.size <= MAX_FIELDS) { "a record has at most $MAX_FIELDS fields" }
    }

    private companion object {
        const val MAX_FIELDS = 64
    }
}
