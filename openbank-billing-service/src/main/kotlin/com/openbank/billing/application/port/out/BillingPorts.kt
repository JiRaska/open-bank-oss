// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.application.port.out

import com.openbank.billing.domain.AnnualFeeSummary
import com.openbank.billing.domain.AssessedFee
import com.openbank.billing.domain.BillableFee
import com.openbank.billing.domain.BillingAssessment
import com.openbank.billing.domain.FeeJournalCommand
import com.openbank.billing.domain.FeeReversalCommand
import com.openbank.libs.product.FeeContext
import java.time.Instant
import java.util.UUID

/**
 * The account's product identity plus the resolved fee-evaluation context for a currency.
 * `null` from [AccountContextPort.resolve] means "could not resolve" — billing fails closed
 * and does not charge (ADR-0143).
 */
data class AccountBilling(val productId: String, val context: FeeContext)

/**
 * Reads the account-side facts a waiver rule is evaluated against (balance, turnover, segment,
 * currency) plus the account's product. Backed by account-service + balance-service reactive
 * REST clients in phase 2c; a no-op stub satisfies CDI in the 2b skeleton.
 */
interface AccountContextPort {
    suspend fun resolve(accountId: String, currency: String): AccountBilling?
}

/**
 * Reads the billable fee definitions for a product/currency from the product catalogue
 * (`GET /api/v1/fees`). Backed by a reactive REST client in phase 2c.
 */
interface ProductCatalogPort {
    suspend fun billableFees(productId: String, currency: String): List<BillableFee>
}

/**
 * Persists a [BillingAssessment] (ADR-0143 phase 2c): the cycle/account/currency assessment row
 * plus one row per [AssessedFee]. **Idempotent** — re-running a cycle for the same
 * `(cycleId, accountId, currency)` returns the previously persisted assessment rather than
 * inserting new ones (the unique constraint on `(cycle_id, account_id, currency)` /
 * `(cycle_id, account_id, fee_id, currency)` is the enforcement backstop).
 */
interface BillingAssessmentRepository {
    /** The persisted assessment for this cycle/account/currency, if one already exists. */
    suspend fun findExisting(cycleId: String, accountId: String, currency: String): BillingAssessment?

    /**
     * Persist the assessment and, in the SAME transaction, append one outbox row per chargeable
     * (non-waived, non-zero) fee — the atomic "assessment commits with the intent-to-post"
     * required by ADR-0143 step 2. Returns the persisted assessment with each chargeable fee's
     * [AssessedFee.postingStatus] set to `PENDING`.
     */
    suspend fun persistWithPostingIntent(assessment: BillingAssessment): BillingAssessment

    /** The single [AssessedFee] with this (charge) idempotency key, if one has been persisted. */
    suspend fun findFeeByIdempotencyKey(idempotencyKey: String): AssessedFee?

    /** Mark one fee POSTED with the ledger's returned journal id (called by the outbox publisher). */
    suspend fun markPosted(idempotencyKey: String, journalId: UUID)

    /** Mark one fee FAILED — its outbox row reached a terminal DEAD state without posting. */
    suspend fun markFailed(idempotencyKey: String)

    /**
     * Append the reversal outbox row for an already-POSTED fee, in the SAME transaction as
     * flipping it to [com.openbank.billing.domain.PostingStatus.REVERSAL_PENDING] (ADR-0143 phase
     * 2e) — mirrors [persistWithPostingIntent]'s atomicity for the charge leg. Returns the updated
     * fee, or `null` if no fee with [idempotencyKey] exists (a genuinely idempotent no-op is
     * decided one layer up, in the use-case, since it needs to distinguish "already reversed" from
     * "never posted" to fail cleanly).
     */
    suspend fun persistReversalIntent(idempotencyKey: String, reason: String): AssessedFee?

    /** Mark one fee REVERSED with the ledger's returned reversal-journal id. */
    suspend fun markReversed(idempotencyKey: String, reversalJournalId: UUID)

    /** Mark one fee's reversal FAILED — its reversal outbox row reached a terminal DEAD state. */
    suspend fun markReversalFailed(idempotencyKey: String)

    /**
     * Every [AssessedFee] successfully [com.openbank.billing.domain.PostingStatus.POSTED] for
     * [accountId] whose [AssessedFee.postedAt] falls in `[from, to)` (ADR-0248, PAD Art. 5 annual
     * fee-summary aggregation). Deliberately narrower than "every row that exists for the period":
     * waived/never-charged fees never reach POSTED, and a fee that is under reversal
     * ([com.openbank.billing.domain.PostingStatus.REVERSAL_PENDING]) or already
     * [com.openbank.billing.domain.PostingStatus.REVERSED] is excluded too — a fee under or
     * already compensated is not a fee the customer actually bore for the year.
     */
    suspend fun postedFeesForAccount(accountId: String, from: Instant, to: Instant): List<AssessedFee>

    /**
     * Idempotently appends the `billing.annual-fee-summary.ready` outbox row for
     * `(summary.accountId, summary.year)` (ADR-0248) — a no-op (returns `false`, nothing written)
     * if a row for this account/year was already appended, so the annual scheduler is safe to
     * re-run. Returns `true` if this call actually appended the row.
     */
    suspend fun appendAnnualFeeSummaryEvent(summary: AnnualFeeSummary, occurredAt: Instant): Boolean
}

/**
 * Reads the account's owning party id (ADR-0248) — billing-service has no `partyRef` anywhere in
 * its own domain today; this reuses account-service's existing `GET /api/v1/accounts/{id}`
 * response (`AccountResponse.partyId`, already returned, just not previously mapped by billing's
 * client DTO) rather than inventing a new source. `null` means the account could not be resolved
 * (fail-closed — the annual-summary use case skips that account rather than publishing with a
 * fabricated party reference).
 */
interface AccountPartyLookupPort {
    suspend fun partyIdFor(accountId: String): String?
}

/** Outbound port to the ledger's journal-posting endpoint (ADR-0143 step 2 / ADR-0039). */
interface LedgerPostingPort {
    /**
     * Posts a balanced fee journal; returns the ledger's journal id. Idempotent on
     * [FeeJournalCommand.idempotencyKey].
     */
    suspend fun post(command: FeeJournalCommand): UUID

    /**
     * Posts the compensating (reversing) journal for an already-posted fee (ADR-0143 phase 2e);
     * returns the ledger's journal id for the reversal. Idempotent on
     * [FeeReversalCommand.idempotencyKey] (distinct from the original charge's key).
     */
    suspend fun postReversal(command: FeeReversalCommand): UUID
}

/** One page of the fleet-wide ACTIVE-account sweep (ADR-0143 billing discovery, issue #548). */
data class BillableAccountsPage(val accountIds: List<String>, val nextCursor: String?)

/**
 * Discovers the accounts a billing cycle sweeps — account-service's fleet-wide
 * `GET /api/v1/accounts/active` read (ADR-0143 / issue #548 follow-up: replaces the
 * operator-configured account CSV as the batch source). Cursor-paginated; a `null`
 * [BillableAccountsPage.nextCursor] means the sweep is complete. Backed by a reactive
 * REST client; errors propagate so a failed page read aborts (and logs) the sweep
 * rather than silently billing a partial batch.
 */
interface BillableAccountDiscoveryPort {
    suspend fun activeAccounts(limit: Int, afterCursor: String?): BillableAccountsPage
}
