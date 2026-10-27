// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.domain.institution

import com.openbank.libs.domain.money.InvalidMoneyException
import com.openbank.libs.domain.money.InvalidMoneyReason
import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * `treasury.payment.released.v1` as it arrived, every field still untrusted: strings exactly as
 * on the wire (`null` = absent or not a string). [ReleasedPaymentValidator] is the only way from
 * here to a [ValidatedRelease].
 */
data class ReleasedPayment(
    val paymentId: String?,
    val idempotencyKey: String?,
    val rail: String?,
    val currency: String?,
    val amount: String?,
    val valueDate: String?,
    val debtorBic: String?,
    val debtorAccountNumber: String?,
    val debtorGlAccountingClass: String?,
    val creditorBic: String?,
    val creditorAccountNumber: String?,
    val creditorName: String?,
    val intermediaryBic: String?,
    val remittanceInformation: String?,
    val dealIds: List<String>?,
    val flowIds: List<String>?,
    val releasedBy: String?,
    val releasedAt: String?,
    val synthetic: Boolean?,
    /** Optional (additive, 9.1 addendum): the counterparty SSI version the creditor block came from. */
    val ssiVersion: Long? = null,
    /** True when `ssiVersion` was present but not an integer — refused, never coerced. */
    val ssiVersionMalformed: Boolean = false,
)

/** A release that passed every rule, in typed form. */
data class ValidatedRelease(
    val paymentId: UUID,
    val idempotencyKey: String,
    val rail: InstitutionTransferRail,
    val amount: Money,
    val valueDate: LocalDate,
    val debtor: InstitutionParty,
    val debtorGlAccountingClass: String,
    val creditor: InstitutionParty,
    val creditorName: String,
    val intermediaryBic: String?,
    val remittanceInformation: String?,
    val dealIds: List<UUID>,
    val flowIds: List<UUID>,
    val releasedBy: String,
    val releasedAt: Instant,
    val synthetic: Boolean,
    val ssiVersion: Long? = null,
)

/**
 * Why a release was refused: an ISO 20022 `ExternalStatusReason1Code` plus a short reason that
 * names the field and never echoes its value (the payload is untrusted).
 */
data class ReleaseRejection(val reasonCode: String, val reason: String)

sealed interface ReleaseValidation {
    data class Valid(val release: ValidatedRelease) : ReleaseValidation

    data class Invalid(val rejection: ReleaseRejection) : ReleaseValidation
}

/**
 * Strict validation of a [ReleasedPayment] — the rail's trust boundary for bank-own money. The
 * first failing rule wins; each maps to one ISO 20022 reason code:
 *
 * | rule | code |
 * |---|---|
 * | missing / malformed field, unknown rail, length bound exceeded, bad id list | `FF01` |
 * | currency not ISO 4217, or not the rail's currency (CERTIS = CZK, TARGET2 = EUR) | `AM03` |
 * | amount not a positive decimal with exactly two decimals, or finer than the currency's minor unit | `AM12` |
 * | BIC (debtor, creditor, intermediary) not a BIC | `RC01` |
 * | account neither an IBAN with a valid check digit nor a plain account number | `AC01` |
 * | value date not `YYYY-MM-DD`, or earlier than today minus the tolerance | `DT01` |
 *
 * Pure: [today] and [pastToleranceDays] come from the caller (clock + configuration).
 */
object ReleasedPaymentValidator {
    const val MAX_IDEMPOTENCY_KEY = 255
    const val MAX_NAME = 140
    const val MAX_REMITTANCE = 140
    const val MAX_GL_CLASS = 64
    const val MAX_RELEASED_BY = 255
    const val MAX_IDS = 500
    private const val CURRENCY_LENGTH = 3
    private const val MAX_ACCOUNT = 34

    private val BIC = Regex("[A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?")
    private val IBAN = Regex("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")
    private val PLAIN_ACCOUNT = Regex("[0-9]{1,6}-?[0-9]{1,10}(/[0-9]{4})?|[A-Z0-9]{1,34}")
    private val AMOUNT = Regex("[0-9]{1,15}\\.[0-9]{2}")
    private val IDEMPOTENCY_KEY = Regex("treasury:[A-Za-z0-9:._-]+:payment")

    private val RAIL_CURRENCY = mapOf(InstitutionTransferRail.CERTIS to "CZK", InstitutionTransferRail.TARGET2 to "EUR")

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun validate(p: ReleasedPayment, today: LocalDate, pastToleranceDays: Long): ReleaseValidation {
        val paymentId = uuid(p.paymentId) ?: return invalid("FF01", "paymentId is missing or not a UUID")
        val key = p.idempotencyKey
        if (key == null || key.length > MAX_IDEMPOTENCY_KEY || !IDEMPOTENCY_KEY.matches(key)) {
            return invalid("FF01", "idempotencyKey is missing or not a treasury payment key")
        }
        val rail = InstitutionTransferRail.entries.firstOrNull { it.name == p.rail }
            ?: return invalid("FF01", "rail is not one of CERTIS, TARGET2, SWIFT")
        val currency = p.currency ?: return invalid("AM03", "currency is missing")
        if (currency.length != CURRENCY_LENGTH ||
            currency != currency.uppercase()
        ) {
            return invalid("AM03", "currency is not an ISO 4217 code")
        }
        RAIL_CURRENCY[rail]?.let { if (it != currency) return invalid("AM03", "currency is not cleared on rail $rail") }
        val amountText = p.amount
        if (amountText == null || !AMOUNT.matches(amountText)) {
            return invalid("AM12", "amount is not a decimal string with two decimals")
        }
        val amount = try {
            Money.of(BigDecimal(amountText), currency)
        } catch (e: InvalidMoneyException) {
            return if (e.reason == InvalidMoneyReason.CURRENCY_UNSUPPORTED) {
                invalid("AM03", "currency is not an ISO 4217 code with a minor unit")
            } else {
                invalid("AM12", "amount is finer than the currency's minor unit")
            }
        }
        if (!amount.isPositive()) return invalid("AM12", "amount must be greater than zero")

        val valueDate = try {
            LocalDate.parse(p.valueDate ?: return invalid("DT01", "valueDate is missing"))
        } catch (_: DateTimeParseException) {
            return invalid("DT01", "valueDate is not YYYY-MM-DD")
        }
        if (valueDate.isBefore(today.minusDays(pastToleranceDays))) return invalid("DT01", "valueDate is in the past")

        val debtorBic = p.debtorBic?.takeIf { BIC.matches(it) } ?: return invalid("RC01", "debtor.bic is not a BIC")
        val creditorBic =
            p.creditorBic?.takeIf { BIC.matches(it) } ?: return invalid("RC01", "creditor.bic is not a BIC")
        val intermediary = p.intermediaryBic
        if (intermediary != null && !BIC.matches(intermediary)) return invalid("RC01", "intermediaryBic is not a BIC")
        if (debtorBic == creditorBic) return invalid("RC01", "creditor.bic equals debtor.bic")

        val debtorAccount =
            p.debtorAccountNumber?.takeIf(::isAccount)
                ?: return invalid("AC01", "debtor.accountNumber is not an account")
        val creditorAccount = p.creditorAccountNumber?.takeIf(::isAccount)
            ?: return invalid("AC01", "creditor.accountNumber is not an account")

        val glClass = p.debtorGlAccountingClass?.takeIf { it.isNotBlank() && it.length <= MAX_GL_CLASS }
            ?: return invalid("FF01", "debtor.glAccountingClass is missing or too long")
        val creditorName = p.creditorName?.takeIf { it.isNotBlank() && it.length <= MAX_NAME }
            ?: return invalid("FF01", "creditor.name is missing or longer than $MAX_NAME")
        val remittance = p.remittanceInformation
        if (remittance != null && (remittance.isBlank() || remittance.length > MAX_REMITTANCE)) {
            return invalid("FF01", "remittanceInformation is blank or longer than $MAX_REMITTANCE")
        }
        val dealIds = uuids(p.dealIds) ?: return invalid("FF01", "dealIds is missing or holds a non-UUID")
        val flowIds =
            uuids(p.flowIds)?.takeIf { it.isNotEmpty() }
                ?: return invalid("FF01", "flowIds is empty or holds a non-UUID")
        val releasedBy = p.releasedBy?.takeIf { it.isNotBlank() && it.length <= MAX_RELEASED_BY }
            ?: return invalid("FF01", "releasedBy is missing or too long")
        val releasedAt = try {
            OffsetDateTime.parse(p.releasedAt ?: return invalid("FF01", "releasedAt is missing")).toInstant()
        } catch (_: DateTimeParseException) {
            return invalid("FF01", "releasedAt is not RFC 3339")
        }
        val synthetic = p.synthetic ?: return invalid("FF01", "synthetic is missing")
        if (p.ssiVersionMalformed || (p.ssiVersion != null && p.ssiVersion < 1)) {
            return invalid("FF01", "ssiVersion is not a positive integer")
        }

        return ReleaseValidation.Valid(
            ValidatedRelease(
                paymentId = paymentId,
                idempotencyKey = key,
                rail = rail,
                amount = amount,
                valueDate = valueDate,
                debtor = InstitutionParty(debtorBic, debtorAccount),
                debtorGlAccountingClass = glClass,
                creditor = InstitutionParty(creditorBic, creditorAccount),
                creditorName = creditorName,
                intermediaryBic = intermediary,
                remittanceInformation = remittance,
                dealIds = dealIds,
                flowIds = flowIds,
                releasedBy = releasedBy,
                releasedAt = releasedAt,
                synthetic = synthetic,
                ssiVersion = p.ssiVersion,
            ),
        )
    }

    /** IBAN with a valid ISO 7064 mod-97 check, or a plain (domestic) account number. */
    fun isAccount(account: String): Boolean = if (IBAN.matches(account)) {
        ibanChecksumValid(account)
    } else {
        account.length <= MAX_ACCOUNT &&
            PLAIN_ACCOUNT.matches(account)
    }

    @Suppress("MagicNumber")
    private fun ibanChecksumValid(iban: String): Boolean {
        val rearranged = iban.substring(4) + iban.substring(0, 4)
        var remainder = 0
        for (c in rearranged) {
            val digits = if (c.isDigit()) c.toString() else (c - 'A' + 10).toString()
            for (d in digits) remainder = (remainder * 10 + (d - '0')) % 97
        }
        return remainder == 1
    }

    private fun uuid(raw: String?): UUID? = raw?.let {
        runCatching { UUID.fromString(it) }.getOrNull()?.takeIf { u ->
            u.toString() ==
                it
        }
    }

    private fun uuids(raw: List<String>?): List<UUID>? {
        if (raw == null || raw.size > MAX_IDS) return null
        return raw.map { uuid(it) ?: return null }
    }

    private fun invalid(code: String, reason: String) = ReleaseValidation.Invalid(ReleaseRejection(code, reason))
}
