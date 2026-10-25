// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.application.usecase

import com.openbank.billing.application.port.out.AccountBilling
import com.openbank.billing.application.port.out.AccountContextPort
import com.openbank.billing.application.port.out.AccountRevisionPinPort
import com.openbank.billing.application.port.out.AccountingClassSource
import com.openbank.billing.application.port.out.ProductRevisionPort
import com.openbank.billing.application.port.out.RevisionTerms
import com.openbank.billing.application.port.out.RevisionTermsUnreadableException
import com.openbank.billing.domain.AssessedFee
import com.openbank.billing.domain.BillableFee
import com.openbank.billing.domain.BillingAssessment
import com.openbank.billing.domain.FeeIncomeCurrencies
import com.openbank.libs.product.FeeContext
import com.openbank.libs.product.WaiveReason
import com.openbank.libs.product.WaiverEvaluator
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal

/**
 * Assesses an account's product fees for a billing cycle (ADR-0143 phase 2). Pure orchestration
 * over the shared [WaiverEvaluator] (ADR-0138): resolve the account context, read the fees of the
 * account's PINNED catalog revision (ADR-0341), and decide per fee whether it is charged or waived
 * -- **fail-closed**: a non-waivable fee is always charged, a satisfied waiver waives, and anything
 * the engine cannot evaluate is charged with the reason recorded. If the account context, its
 * revision or its accounting class cannot be resolved, the whole assessment is **skipped** rather
 * than charged on absent inputs.
 *
 * The terms come from the revision the account is pinned to, never from "the product as currently
 * published": a re-published product changes nothing for an account until it is explicitly re-pinned.
 *
 * This phase posts nothing: it produces [AssessedFee]s and their balanced
 * [com.openbank.billing.domain.FeeJournalCommand]s (idempotently keyed).
 */
@ApplicationScoped
class FeeAssessmentService(
    private val accounts: AccountContextPort,
    private val revisions: ProductRevisionPort,
    private val pins: AccountRevisionPinPort,
    private val meters: MeterRegistry,
) {

    suspend fun assess(cycleId: String, accountId: String, currency: String): BillingAssessment {
        // Fail-closed (#13226): a currency the ledger has no fee-income account for would only ever 422 at
        // the ledger and wedge its outbox row. Refuse it here, counted by reason -- never a zero charge.
        if (!FeeIncomeCurrencies.isMapped(currency)) {
            return skipped(cycleId, accountId, currency, "FEE_INCOME_CURRENCY_UNMAPPED")
        }
        val billing = accounts.resolve(accountId, currency)
            ?: return skipped(cycleId, accountId, currency, "ACCOUNT_CONTEXT_UNRESOLVED")

        // Fail-closed, same as the account-context branch above: a catalog or pin-store fault must be a
        // visible, explicit skip -- not a silent "zero fees" that looks identical to a product that
        // genuinely has none. The REASON says which part failed, because the remedies differ: the catalog
        // being down, the pin store (our own database) failing, and a revision whose embedded document is
        // unreadable (retrying never fixes it) must not all read PRODUCT_CATALOG_UNREACHABLE. A cancelled
        // coroutine is never a skip: it propagates.
        val pinned = when (val pin = resolvePin(accountId, billing)) {
            is Pin.Skip -> return skipped(cycleId, accountId, currency, pin.reason)
            is Pin.Found -> pin.revisionId
        }
        val terms: RevisionTerms = attempt { revisions.terms(pinned, currency) }.getOrElse {
            val reason = if (it is RevisionTermsUnreadableException) {
                "LEGACY_DOCUMENT_UNREADABLE"
            } else {
                "PRODUCT_CATALOG_UNREACHABLE"
            }
            log.warn("Revision terms unavailable; assessment skipped")
            return skipped(cycleId, accountId, currency, reason)
        }
        // A fee with no accounting class has no ledger account to land in; charging it anyway would
        // mean guessing one (ADR-0341 D3).
        val accountingClass = terms.accountingClass
        if (accountingClass == null || terms.accountingClassSource == AccountingClassSource.NONE) {
            return skipped(cycleId, accountId, currency, "ACCOUNTING_CLASS_UNRESOLVED")
        }
        if (terms.noFeeIdentityComponents > 0) {
            // AMOUNT components with no fee identity are not fees (a minimum balance, a limit). Counted per
            // assessment so a v2 product whose fees are ALL unidentified shows up, not as a zero bill alone.
            meters.counter("openbank.billing.assessment.no_fee_identity").increment()
        }
        // A product that declares fees but yields none we can charge in this currency (only other
        // currencies, or only components billing cannot price) must NOT come out as a clean zero-fee
        // assessment: that reads exactly like a product with no fees and silently under-charges.
        if (terms.feeBearing && terms.fees.isEmpty()) {
            return skipped(cycleId, accountId, currency, "FEE_COMPONENT_UNRESOLVED")
        }
        if (terms.droppedFeeComponents > 0) {
            // This assessment charges less than the product declares; a persistent gap must be visible to
            // an alert, which a once-per-revision counter (the revision is cached) cannot give.
            meters.counter("openbank.billing.assessment.dropped_components").increment()
            log.warn("Assessment has dropped fee components")
        }
        val assessed = terms.fees.map { fee ->
            assessOne(cycleId, accountId, currency, fee, billing.context, terms.revisionId, accountingClass)
        }
        return BillingAssessment(
            cycleId = cycleId,
            accountId = accountId,
            currency = currency,
            skipped = false,
            skipReason = null,
            assessedFees = assessed,
        )
    }

    private sealed interface Pin {
        data class Found(val revisionId: String) : Pin
        data class Skip(val reason: String) : Pin
    }

    /**
     * Runs [block]; any failure other than cancellation comes back as a value so the caller can map it to
     * a reason. A cancelled coroutine is rethrown: it is never a skip.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** The account's pinned revision, pinning it as of the account's opening on first sight. */
    private suspend fun resolvePin(accountId: String, billing: AccountBilling): Pin {
        val existing = attempt { pins.find(accountId) }.getOrElse {
            log.warn("Revision pin store read failed; assessment skipped")
            return Pin.Skip("REVISION_PIN_STORE_UNAVAILABLE")
        }
        if (existing != null) return Pin.Found(existing)
        // The terms an account is billed under are the ones in force when it was OPENED, not the ones in
        // force the first time billing looks at it (ADR-0341 D1).
        val openedAt = billing.openedAt ?: return Pin.Skip("ACCOUNT_OPENING_UNKNOWN")
        val published = attempt { revisions.publishedRevisionIdFor(billing.productId, openedAt) }.getOrElse {
            log.warn("Catalog revision lookup failed; assessment skipped")
            return Pin.Skip("PRODUCT_CATALOG_UNREACHABLE")
        } ?: return Pin.Skip("PRODUCT_REVISION_UNRESOLVED")
        return attempt { pins.pinIfAbsent(accountId, published) }.fold(
            onSuccess = { Pin.Found(it) },
            onFailure = {
                log.warn("Revision pin store write failed; assessment skipped")
                Pin.Skip("REVISION_PIN_STORE_UNAVAILABLE")
            },
        )
    }

    private val log = org.jboss.logging.Logger.getLogger(FeeAssessmentService::class.java)

    /** Every skip is COUNTED by reason: a skip is a fee not charged, and must never be invisible. */
    private fun skipped(cycleId: String, accountId: String, currency: String, reason: String): BillingAssessment {
        meters.counter("openbank.billing.assessment.skipped", "reason", reason).increment()
        return BillingAssessment(
            cycleId = cycleId,
            accountId = accountId,
            currency = currency,
            skipped = true,
            skipReason = reason,
            assessedFees = emptyList(),
        )
    }

    @Suppress("LongParameterList")
    private fun assessOne(
        cycleId: String,
        accountId: String,
        currency: String,
        fee: BillableFee,
        context: FeeContext,
        revisionId: String,
        accountingClass: String,
    ): AssessedFee {
        val reason = if (!fee.waivable) {
            WaiveReason.NOT_WAIVABLE
        } else {
            WaiverEvaluator.evaluate(fee.waiveCondition, context)
        }
        val waived = reason == WaiveReason.WAIVED_BY_CONDITION
        return AssessedFee(
            cycleId = cycleId,
            accountId = accountId,
            feeId = fee.feeId,
            name = fee.name,
            currency = currency,
            chargedAmount = if (waived) BigDecimal.ZERO else fee.amount,
            waived = waived,
            reason = reason,
            productRevisionId = revisionId,
            accountingClass = accountingClass,
        )
    }
}
