// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.port.out

import com.openbank.interest.domain.model.InterestAccrual
import com.openbank.interest.domain.model.InterestCapitalization
import com.openbank.interest.domain.model.InterestRateConfig
import com.openbank.interest.domain.tax.TaxProfile
import com.openbank.interest.domain.tax.WithholdingTax
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.smallrye.mutiny.Uni
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Outbound persistence port for interest rate configurations (reactive, Mutiny). */
interface InterestRateConfigRepository {
    fun save(config: InterestRateConfig): Uni<InterestRateConfig>
    fun findById(id: UUID): Uni<InterestRateConfig?>
    fun findByProductId(productId: String): Uni<List<InterestRateConfig>>
    fun findAll(): Uni<List<InterestRateConfig>>
    fun findActiveForProduct(productId: String, date: LocalDate): Uni<InterestRateConfig?>

    /**
     * The rate that actually applies to [accountId] on [date]: an active account-specific override
     * (accountId set) wins over the product-level default (accountId null, productId matches). Null
     * when neither exists — the account earns no interest (e.g. a plain CURRENT account, whose
     * product default is deactivated). This is what the accrual run and the app's rate view use.
     */
    fun findEffectiveRate(
        accountId: UUID,
        productId: String,
        date: LocalDate,
        currency: String? = null,
    ): Uni<InterestRateConfig?>
    fun update(config: InterestRateConfig): Uni<InterestRateConfig>
}

/** Outbound persistence port for daily interest accruals (reactive, Mutiny). */
interface InterestAccrualRepository {
    fun save(accrual: InterestAccrual): Uni<InterestAccrual>
    fun findAll(): Uni<List<InterestAccrual>>
    fun findByAccountId(accountId: UUID, from: LocalDate?, to: LocalDate?): Uni<List<InterestAccrual>>

    /**
     * Pending (`ACCRUING`) accruals for one `(account, product)` up to [toDate]. Filtering by
     * product is essential: an account can accrue under several products, and folding another
     * product's accruals into this capitalization would credit them against the wrong product.
     */
    fun findPendingCapitalization(accountId: UUID, productId: String, toDate: LocalDate): Uni<List<InterestAccrual>>

    /**
     * The distinct `(accountId, productId)` pairs that have at least one pending (`ACCRUING`) accrual
     * up to [toDate] — the work-list for a fleet-wide monthly capitalization run (issue #999). Unlike
     * [findPendingCapitalization] (one already-known pair), this discovers *which* pairs have anything
     * to capitalize, so the scheduler needs no separate account enumeration. Capitalizing is a pure
     * function of already-persisted accruals, so this is a plain DB read — it never touches
     * account-service.
     */
    fun findAccountsWithPendingCapitalization(toDate: LocalDate): Uni<List<Pair<UUID, String>>>

    /**
     * The accruals of one `(account, product)` already **claimed** by an in-flight capitalization
     * (`CAPITALIZING`), regardless of date.
     *
     * Deliberately NOT bounded by a period: the caller must be able to see a claim made for a
     * *different* `periodTo` and refuse rather than silently re-capitalize the same accruals under a
     * second ledger idempotency key — see `InterestService.capitalize`. Each row carries the period
     * it was claimed for in [InterestAccrual.claimedPeriodTo].
     */
    fun findClaimedForCapitalization(accountId: UUID, productId: String): Uni<List<InterestAccrual>>

    /**
     * Claims [accrualIds] for the capitalization of [periodTo]: flips them `ACCRUING → CAPITALIZING`
     * and stamps `claimed_period_to`, in ONE status-guarded transaction of its own.
     *
     * This is what pins the amount the ledger is about to be told to the amount that will be
     * recorded. Without it, the ledger post and the capitalization row each re-derive the accrual
     * set independently, and a backfilled accrual landing between them makes interest-service claim
     * a credit the GL never booked — silently, because the ledger's idempotent replay returns the
     * FIRST journal without comparing amounts.
     *
     * Fails (and rolls back) unless every id flipped: a partial match means a concurrent run claimed
     * or capitalized some of them.
     *
     * [profile] is the tax profile resolved for this claim; it is frozen here alongside the period so a
     * retry recomputes withholding from the same inputs the interrupted attempt used, not a fresh
     * resolve that may have changed (issue #1355).
     */
    fun claimForCapitalization(accrualIds: List<UUID>, periodTo: LocalDate, profile: TaxProfile): Uni<Unit>

    fun sumAccrued(accountId: UUID, from: LocalDate, to: LocalDate): Uni<BigDecimal>
}

/** Outbound persistence port for interest capitalization events (reactive, Mutiny). */
interface InterestCapitalizationRepository {
    fun save(cap: InterestCapitalization): Uni<InterestCapitalization>
    fun findByAccountId(accountId: UUID): Uni<List<InterestCapitalization>>

    /**
     * Persists the capitalization, its paired withholding-tax liability and the outbox event, and
     * flips the source accruals `CAPITALIZING → CAPITALIZED`, all in ONE database transaction (same
     * atomic shape as statement-service's `saveWithOutbox`). A crash or failure anywhere rolls the
     * whole write set back, so a retry re-runs from a clean slate instead of re-crediting
     * already-capitalized accruals (duplicate interest + duplicate withholding).
     *
     * The accrual flip is status-guarded (`AND status = 'CAPITALIZING'`): the rows must still be the
     * ones this attempt claimed via [InterestAccrualRepository.claimForCapitalization]. If a
     * concurrent run capitalized or reversed any of them, the row count mismatches and the
     * transaction fails — no partial rows survive.
     */
    fun saveWithOutbox(
        cap: InterestCapitalization,
        withholding: WithholdingTax,
        event: OutboxMessage,
        accrualIds: List<UUID>,
        capitalizedAt: OffsetDateTime,
    ): Uni<InterestCapitalization>
}
