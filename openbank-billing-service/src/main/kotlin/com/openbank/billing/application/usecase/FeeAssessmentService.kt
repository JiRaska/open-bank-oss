// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.application.usecase

import com.openbank.billing.application.port.out.AccountContextPort
import com.openbank.billing.application.port.out.AccountRevisionPinPort
import com.openbank.billing.application.port.out.AccountingClassSource
import com.openbank.billing.application.port.out.ProductRevisionPort
import com.openbank.billing.application.port.out.RevisionTerms
import com.openbank.billing.domain.AssessedFee
import com.openbank.billing.domain.BillableFee
import com.openbank.billing.domain.BillingAssessment
import com.openbank.libs.product.FeeContext
import com.openbank.libs.product.WaiveReason
import com.openbank.libs.product.WaiverEvaluator
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
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
        val billing = accounts.resolve(accountId, currency)
            ?: return skipped(cycleId, accountId, currency, "ACCOUNT_CONTEXT_UNRESOLVED")

        // Fail-closed, same as the account-context branch above: a catalog or pin-store fault
        // (unreachable, timeout, unexpected 5xx) must be a visible, explicit skip -- not a silent "zero
        // fees" that looks identical to a product that genuinely has none. Confirmed live during
        // real-environment verification (ADR-0143) that swallowing the exception made the two cases
        // indistinguishable.
        // TooGenericExceptionCaught/SwallowedException: deliberately catch ANY fault -- narrowing this
        // would leave a class of faults unhandled and silently billing on absent data.
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        val terms: RevisionTerms = try {
            val pinned = pins.find(accountId) ?: run {
                // The terms an account is billed under are the ones in force when it was OPENED, not
                // the ones in force the first time billing looks at it (ADR-0341 D1): resolving "now"
                // would silently reprice every account older than the latest republication.
                val openedAt = billing.openedAt
                    ?: return skipped(cycleId, accountId, currency, "ACCOUNT_OPENING_UNKNOWN")
                val published = revisions.publishedRevisionIdFor(billing.productId, openedAt)
                    ?: return skipped(cycleId, accountId, currency, "PRODUCT_REVISION_UNRESOLVED")
                pins.pinIfAbsent(accountId, published)
            }
            revisions.terms(pinned, currency)
        } catch (e: Exception) {
            return skipped(cycleId, accountId, currency, "PRODUCT_CATALOG_UNREACHABLE")
        }
        // A fee with no accounting class has no ledger account to land in; charging it anyway would
        // mean guessing one (ADR-0341 D3).
        val accountingClass = terms.accountingClass
        if (accountingClass == null || terms.accountingClassSource == AccountingClassSource.NONE) {
            return skipped(cycleId, accountId, currency, "ACCOUNTING_CLASS_UNRESOLVED")
        }
        // A product that declares fees but yields none we can charge in this currency (only other
        // currencies, or only components billing cannot price) must NOT come out as a clean zero-fee
        // assessment: that reads exactly like a product with no fees and silently under-charges.
        if (terms.feeBearing && terms.fees.isEmpty()) {
            return skipped(cycleId, accountId, currency, "FEE_COMPONENT_UNRESOLVED")
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

    /** Every skip is COUNTED by reason: a skip is a fee not charged, and must never be invisible. */
    private fun skipped(cycleId: String, accountId: String, currency: String, reason: String): BillingAssessment {
        meters.counter("billing.assessment.skipped", "reason", reason).increment()
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
