// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.application.usecase

import com.openbank.billing.application.port.out.AccountContextPort
import com.openbank.billing.application.port.out.ProductCatalogPort
import com.openbank.billing.domain.AssessedFee
import com.openbank.billing.domain.BillableFee
import com.openbank.billing.domain.BillingAssessment
import com.openbank.libs.product.FeeContext
import com.openbank.libs.product.WaiveReason
import com.openbank.libs.product.WaiverEvaluator
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal

/**
 * Assesses an account's product fees for a billing cycle (ADR-0143 phase 2). Pure orchestration
 * over the shared [WaiverEvaluator] (ADR-0138): resolve the account context, read the product's
 * billable fees, and decide per fee whether it is charged or waived — **fail-closed**: a
 * non-waivable fee is always charged, a satisfied waiver waives, and anything the engine cannot
 * evaluate is charged with the reason recorded. If the account context cannot be resolved at all,
 * the whole assessment is **skipped** rather than charged on absent inputs.
 *
 * This phase posts nothing: it produces [AssessedFee]s and their balanced
 * [com.openbank.billing.domain.FeeJournalCommand]s (idempotently keyed). The ledger posting leg
 * (REST client + outbox) and the scheduled trigger are phase 2c.
 */
@ApplicationScoped
class FeeAssessmentService(private val accounts: AccountContextPort, private val catalog: ProductCatalogPort) {

    suspend fun assess(cycleId: String, accountId: String, currency: String): BillingAssessment {
        val billing = accounts.resolve(accountId, currency)
            ?: return BillingAssessment(
                cycleId = cycleId,
                accountId = accountId,
                currency = currency,
                skipped = true,
                skipReason = "ACCOUNT_CONTEXT_UNRESOLVED",
                assessedFees = emptyList(),
            )

        // Fail-closed, same as the account-context branch above: a product-catalog read failure
        // (e.g. transiently unreachable) must be a visible, explicit skip — not a silent "zero
        // fees" that looks identical to a product that genuinely has none. Confirmed live during
        // real-environment verification (ADR-0143) that swallowing this exception at the port
        // level made the two cases indistinguishable.
        // TooGenericExceptionCaught/SwallowedException: deliberately catch ANY fault (network
        // error, timeout, unexpected 5xx) — narrowing this would leave a class of catalog faults
        // unhandled and silently billing on absent data, exactly what this branch exists to prevent.
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        val fees = try {
            catalog.billableFees(billing.productId, currency)
        } catch (e: Exception) {
            return BillingAssessment(
                cycleId = cycleId,
                accountId = accountId,
                currency = currency,
                skipped = true,
                skipReason = "PRODUCT_CATALOG_UNREACHABLE",
                assessedFees = emptyList(),
            )
        }
        val assessed = fees.map { fee -> assessOne(cycleId, accountId, currency, fee, billing.context) }
        return BillingAssessment(
            cycleId = cycleId,
            accountId = accountId,
            currency = currency,
            skipped = false,
            skipReason = null,
            assessedFees = assessed,
        )
    }

    private fun assessOne(
        cycleId: String,
        accountId: String,
        currency: String,
        fee: BillableFee,
        context: FeeContext,
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
        )
    }
}
