// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.port.`in`

import com.openbank.interest.domain.model.*
import io.smallrye.mutiny.Uni
import java.time.LocalDate
import java.util.UUID

/**
 * What one accrual run did (ADR-0342): accounts accrued, accounts whose accrual FAILED, and accounts
 * whose balance was UNAVAILABLE (account-service down). A run with any failed/unavailable account
 * must not be reported as RAN - the day would tie out with no interest for those accounts.
 */
data class AccrualRunOutcome(
    val accrued: Int,
    val failed: Int,
    val unavailable: Int,
    val firstFailure: String?,
    /** The account LIST could not be read (account-service down on some page): no reliable account set. */
    val listingFailed: Boolean = false,
) {
    /** True when no account was left unaccrued for a reason other than a legitimate no-op. */
    val complete: Boolean get() = failed == 0 && unavailable == 0 && !listingFailed
}

interface AccrueInterestUseCase {
    fun accrue(request: AccrualRequest): Uni<InterestAccrual>
    fun accrueAll(date: LocalDate): Uni<Int>

    /** [accrueAll] with the failures counted. Defaulted so a stub implementing only [accrueAll] compiles. */
    fun accrueAllWithOutcome(date: LocalDate): Uni<AccrualRunOutcome> =
        accrueAll(date).map { AccrualRunOutcome(it, 0, 0, null) }
}

/**
 * What one capitalization run did (ADR-0342 D4): how many pairs were capitalized (one ledger
 * journal each), how many the ledger refused because the accounting day no longer accepts
 * postings, and how many FAILED for any other reason (ledger unreachable, 5xx, a bug). All three are
 * close outcomes reported to the ledger, not just a WARN line: only a run with neither refusals nor
 * failures may be reported as RAN, because otherwise the day ties out over interest never posted.
 */
data class CapitalizationRunOutcome(
    val capitalized: Int,
    val refusedClosedDay: Int,
    val refusalReason: String?,
    val failed: Int = 0,
    val failureReason: String? = null,
) {
    /** True when every pair that was due is capitalized. */
    val complete: Boolean get() = refusedClosedDay == 0 && failed == 0
}

interface CapitalizeInterestUseCase {
    fun capitalize(accountId: UUID, productId: String, toDate: LocalDate): Uni<InterestCapitalization>
    fun capitalizeAll(toDate: LocalDate): Uni<Int>

    /**
     * [capitalizeAll] with the closed-day refusals counted. Defaulted so a stub implementing only
     * [capitalizeAll] keeps compiling; [com.openbank.interest.application.usecase.InterestService]
     * overrides it with the real count.
     */
    fun capitalizeAllWithOutcome(toDate: LocalDate): Uni<CapitalizationRunOutcome> =
        capitalizeAll(toDate).map { CapitalizationRunOutcome(it, 0, null) }

    /**
     * Completes every outstanding capitalization claim at its own frozen period, starting no new
     * ones. Returns how many were completed.
     *
     * Exposed separately from [capitalizeAll] because the two run on very different clocks. A claim
     * stranded by a failed ledger post freezes its `(account, product)` pair until it is completed,
     * and [capitalizeAll] runs MONTHLY — so recovery riding along with it would leave a wedge in
     * place for up to a month, which is how this went unnoticed for seven weeks in sandbox (#10404).
     * This is safe to run often: it touches only sets a previous attempt already claimed, and
     * replaying one is idempotent by the ledger key.
     */
    fun recoverOutstandingClaims(): Uni<Int>
}

interface GetAccrualsUseCase {
    fun listAllAccruals(): Uni<List<InterestAccrual>>
    fun getAccruals(accountId: UUID, from: LocalDate?, to: LocalDate?): Uni<List<InterestAccrual>>
    fun getSummary(accountId: UUID, from: LocalDate, to: LocalDate): Uni<AccrualSummary>
    fun getCapitalizations(accountId: UUID): Uni<List<InterestCapitalization>>
}

interface ManageRateConfigUseCase {
    fun createConfig(config: InterestRateConfig): Uni<InterestRateConfig>
    fun getConfig(id: UUID): Uni<InterestRateConfig?>
    fun listConfigs(productId: String?): Uni<List<InterestRateConfig>>

    /** The rate that actually applies to [accountId] (account override, else product default), or
     *  null if the account earns no interest. Drives the app's per-account rate view. */
    fun effectiveRate(accountId: UUID, productId: String, date: LocalDate): Uni<InterestRateConfig?>
    fun deactivateConfig(id: UUID): Uni<InterestRateConfig>
}
