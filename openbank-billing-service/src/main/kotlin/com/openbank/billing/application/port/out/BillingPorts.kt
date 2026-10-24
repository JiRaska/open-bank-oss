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
data class AccountBilling(
    val productId: String,
    val context: FeeContext,
    /**
     * When the account was opened. The pin is resolved as the revision effective AT THIS INSTANT, not
     * "now" (ADR-0341 D1/D2): an account opened under older terms must not be repriced by a later
     * publication just because billing happened to see it for the first time afterwards.
     */
    val openedAt: Instant? = null,
)

/**
 * Reads the account-side facts a waiver rule is evaluated against (balance, turnover, segment,
 * currency) plus the account's product. Backed by account-service + balance-service reactive
 * REST clients in phase 2c; a no-op stub satisfies CDI in the 2b skeleton.
 */
interface AccountContextPort {
    suspend fun resolve(accountId: String, currency: String): AccountBilling?
}

/**
 * One published catalog revision's billable terms (ADR-0341 D2/D3). [accountingClass] is the class
 * the revision carries, or null when it carries none and cannot be derived; [accountingClassSource]
 * says which. [fees] are the revision's AMOUNT price components in a currency, never the product's
 * "currently published" fees: a revision is immutable, so these terms never change under a contract
 * that pinned it.
 */
data class RevisionTerms(
    val revisionId: String,
    val accountingClass: String?,
    val accountingClassSource: AccountingClassSource,
    val fees: List<BillableFee>,
    /**
     * True when the revision declares fees at all: any AMOUNT price component in ANY currency, any
     * component coded as a fee, or a fee in its embedded v1 document. A fee-bearing revision whose
     * [fees] come out empty for the account's currency is a defect to surface (skip), never "zero fees".
     */
    val feeBearing: Boolean = false,
    /**
     * Fee price components of this revision that billing cannot price (a fee of the wrong kind or with no
     * currency, or fee-coded with no identity in the revision's v1 document). Each makes the assessment
     * charge LESS than the product declares, so the assessment counts it
     * (`openbank.billing.assessment.dropped_components`) every time, not only when the revision is first read.
     */
    val droppedFeeComponents: Int = 0,
    /**
     * AMOUNT price components that carry no fee identity: not one of the revision's legacy v1 fees and
     * not fee-coded. By decision (consistent with #13230) they are NOT fees -- a minimum balance, a limit
     * -- so nothing is charged for them, but every assessment under such a revision is counted
     * (`openbank.billing.assessment.no_fee_identity`) so a v2 product whose fees are all unidentified is visible.
     */
    val noFeeIdentityComponents: Int = 0,
)

/**
 * The revision's embedded v1 document could not be read, so its fee identities are unknown. Distinct from
 * a catalog outage: the catalog answered, the content is what is wrong, and retrying will not fix it.
 */
class RevisionTermsUnreadableException(message: String, cause: Throwable) : RuntimeException(message, cause)

/** Where [RevisionTerms.accountingClass] came from. */
enum class AccountingClassSource {
    /** The revision's own `accountingClass` attribute (catalog banking pack v3 and later). */
    REVISION,

    /**
     * Derived from the `productType` of a lossless v1 projection (`legacy-product`), which predates
     * the attribute. Transitional and COUNTED (`openbank.billing.accounting_class.derived`): it disappears
     * when those revisions are re-published under a schema that carries the class.
     */
    DERIVED_FROM_LEGACY_PRODUCT_TYPE,

    /** No class on the revision and none derivable. */
    NONE,
}

/**
 * Reads billing terms from the catalog by published revision (ADR-0341 D2). Fail-closed like every
 * read port here: a failure throws and the caller turns it into an explicit skip.
 */
interface ProductRevisionPort {
    /**
     * The id of the revision effective at [effectiveAt] (null = now) for a v1 product id, or null when
     * none is published. When the product has no revision effective that far back (an account that
     * predates the catalog's first revision), implementations fall back to the one effective now and
     * COUNT it, because the catalog holds no older terms for that account.
     */
    suspend fun publishedRevisionIdFor(productId: String, effectiveAt: Instant? = null): String?

    /** The terms of [revisionId] in [currency]; cached by revision id, since a revision is immutable. */
    suspend fun terms(revisionId: String, currency: String): RevisionTerms
}

/**
 * The account's pinned catalog revision (ADR-0341 D1). Billing pins at the account's first
 * assessment -- the earliest moment billing sees the account -- until account-service records the
 * pin at opening (the last adoption step) and billing can read it from there. Once pinned the
 * revision is never silently replaced; a change of terms is an explicit re-pin.
 */
interface AccountRevisionPinPort {
    suspend fun find(accountId: String): String?

    /** Pins [revisionId] unless a pin already exists; returns the pin that stands afterwards. */
    suspend fun pinIfAbsent(accountId: String, revisionId: String): String
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
     * Idempotently reserves `(summary.accountId, summary.year)` in durable issuance state and
     * appends the `billing.annual-fee-summary.ready` outbox row in the SAME transaction (ADR-0248).
     * A no-op (returns `false`, nothing written) if this account/year was already issued, including
     * after the SENT outbox row has been purged. Returns `true` only when this call owns the new key.
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
