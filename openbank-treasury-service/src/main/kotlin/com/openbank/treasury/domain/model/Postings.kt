// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import com.openbank.libs.domain.money.RoundingPolicy
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class Side { DEBIT, CREDIT }

/** The ledger events that move value. `BOOKED` posts nothing (no off-balance commitment in the MVP). */
enum class PostingEvent(val key: String) {
    SETTLED("settled"),

    // ADR-0344 D7: the posting-rule matrix events of the leg-described products. Their keys are
    // `treasury:<dealId>:<event>:<flowId|date>`.
    TRADE("trade"),
    SETTLEMENT("settlement"),
    ACCRUAL("accrual"),
    FIXING("fixing"),
    PAYMENT("payment"),
    AMORTISATION("amortisation"),
    REVALUATION("revaluation"),
    MATURITY("maturity"),
    EARLY_TERMINATION("early-termination"),
    CORRECTION("correction"),

    /** One calendar day of interest recognised on an accrual basis (ADR-0315 D5); keyed per day. */
    ACCRUED("accrued"),
    MATURED("matured"),
    REVERSED("reversed"),
}

/**
 * Which of a product's ledger accounts a leg belongs to. Mirrors the ledger's `AccountingRole`
 * (ADR-0341 D3) by name: the ledger, not the treasury, decides which GL account that is.
 */
enum class LedgerRole {
    PRINCIPAL,
    ACCRUED_INTEREST,
    INTEREST_INCOME,
    INTEREST_EXPENSE,

    // ADR-0344 D7: the instrument roles of the posting-rule matrix (ledger V34 adds them to the map).
    PREMIUM_DISCOUNT,
    FAIR_VALUE,
    REVALUATION_RESERVE,
    REALISED_GAIN,
    REALISED_LOSS,
    UNREALISED_GAIN,
    UNREALISED_LOSS,
    MEMO,
    MEMO_CONTRA,
}

/** The accounting classes treasury posts under (ADR-0341 D3); the ledger maps each to GL accounts. */
object AccountingClasses {
    const val NOSTRO = "NOSTRO_ACCOUNT"
    const val FX_POSITION = "FX_SPOT_POSITION"

    // ADR-0344 D7: the classes of the posting-rule matrix products (ledger V34).
    const val CASH_IN_TRANSIT = "CASH_IN_TRANSIT"
    const val TREASURY_LOAN = "TREASURY_LOAN"
    const val SECURITY_AMORTISED_COST = "DEBT_SECURITY_AMORTISED_COST"
    const val SECURITY_FVOCI = "DEBT_SECURITY_FVOCI"
    const val SECURITY_FVTPL = "DEBT_SECURITY_FVTPL"
    const val REPO_BORROWING = "REPO_BORROWING"
    const val REVERSE_REPO_LENDING = "REVERSE_REPO_LENDING"
    const val DERIVATIVE_ASSET = "DERIVATIVE_ASSET"
    const val DERIVATIVE_LIABILITY = "DERIVATIVE_LIABILITY"
    const val DERIVATIVE_RECEIVE_LEG = "DERIVATIVE_RECEIVE_LEG"
    const val DERIVATIVE_PAY_LEG = "DERIVATIVE_PAY_LEG"
    const val TRADING_RESULT = "TREASURY_TRADING_RESULT"
    const val FORWARD_COMMITMENT = "FORWARD_COMMITMENT"
    const val COLLATERAL_RECEIVED = "COLLATERAL_RECEIVED"
    const val COLLATERAL_PLEDGED = "COLLATERAL_PLEDGED"
}

/**
 * One leg of a journal. It names its account by [accountingClass] + [role] (+ [currency]) and carries
 * no GL code: the ledger resolves the account from its own map (ADR-0341 D3).
 */
data class PostingLine(
    val accountingClass: String,
    val role: LedgerRole,
    val side: Side,
    val amount: BigDecimal,
    val currency: String,
    /**
     * Set ONLY on a reversal leg, to the account the reversed postings actually landed on
     * ([ResolvedAccount]); a class split across accounts yields one leg per account ([PinnedReversal]). The ledger map may be re-pointed between a settlement
     * and its reversal by a reviewed migration; a reversal that re-resolved the class would then
     * offset a different account than the one it is reversing. Null on every other leg.
     */
    val glAccountId: UUID? = null,
)

/**
 * What the ledger resolved one leg of a journal to when it was posted. Recorded with the deal's
 * journal so a later reversal can offset exactly those accounts, whatever the map says by then.
 */
data class ResolvedAccount(
    val accountingClass: String,
    val role: LedgerRole,
    val currency: String,
    val glAccountId: UUID,
)

/**
 * A balanced journal for one deal event. [idempotencyKey] is `treasury:<dealId>:<event>` (ADR-0315
 * D5), or `treasury:<dealId>:accrued:<date>` for a daily accrual ([suffix] = the ISO date): a retried
 * post returns the ledger's original entry and never double-posts.
 */
data class JournalSpec(
    val dealId: UUID,
    val event: PostingEvent,
    val lines: List<PostingLine>,
    val suffix: String? = null,
) {
    val idempotencyKey: String get() = "treasury:$dealId:${event.key}" + (suffix?.let { ":$it" } ?: "")

    init {
        require(lines.size >= 2) { "a journal needs at least two lines" }
        lines.groupBy { it.currency }.forEach { (ccy, ls) ->
            val dr = ls.filter { it.side == Side.DEBIT }.sumOf { it.amount }
            val cr = ls.filter { it.side == Side.CREDIT }.sumOf { it.amount }
            check(dr.compareTo(cr) == 0) { "journal for $dealId/${event.key} unbalanced in $ccy: Dr $dr != Cr $cr" }
        }
        require(lines.all { it.amount.signum() > 0 }) { "every journal line amount must be positive" }
    }
}

/**
 * The posting table (ADR-0315 D5). Pure: a deal and an event in, a balanced journal out. I is the
 * deal's interest, A the part of it already accrued (Σ of the daily ACCRUED journals).
 *
 * | product              | SETTLED                       | ACCRUED (per day, amount d)         | MATURED                                                        |
 * |----------------------|-------------------------------|-------------------------------------|----------------------------------------------------------------|
 * | MM_PLACEMENT         | Dr placement / Cr nostro (P)  | Dr accrued receivable / Cr income   | Dr nostro (P+I) / Cr placement (P) / Cr accrued (A) / Cr income (I−A) |
 * | CNB_DEPOSIT_FACILITY | Dr ČNB 1510 / Cr nostro (P)   | Dr accrued receivable / Cr income   | Dr nostro (P+I) / Cr 1510 (P) / Cr accrued (A) / Cr income (I−A)      |
 * | MM_BORROWING         | Dr nostro / Cr borrowing (P)  | Dr expense / Cr accrued payable     | Dr borrowing (P) / Dr accrued (A) / Dr expense (I−A) / Cr nostro (P+I) |
 * | CNB_LOMBARD          | Dr nostro / Cr ČNB 2320 (P)   | Dr expense / Cr accrued payable     | Dr 2320 (P) / Dr accrued (A) / Dr expense (I−A) / Cr nostro (P+I)      |
 *
 * The daily amount is `cumulative(d) − cumulative(d−1)` with `cumulative(n) = I · n / days`, rounded
 * as I itself is, so the dailies sum to exactly I over the life of the deal and a maturity after a
 * complete accrual run books nothing to income (A = I). REVERSED (from SETTLED) is the settlement
 * journal with every side flipped, plus the accrued A unwound out of income or expense, posted as
 * one new offsetting journal under its own key; from BOOKED nothing had posted. A zero amount
 * posts no line.
 *
 * FX_SPOT (F = foreign amount, C = its CZK leg) SETTLED, routed through the ledger's per-currency
 * FX position accounts (V5, credited when the bank acquires a currency) so each currency balances:
 * - BUY:  Dr nostro(F) / Cr FX position(F) (F); Dr FX position CZK / Cr nostro CZK (C)
 * - SELL: the same four lines with every side flipped.
 * It never accrues or matures; its reversal is the flipped settlement. Revaluation of the open
 * position is the ledger's daily FX revaluation, not the treasury's.
 */
@Suppress("TooManyFunctions") // one function per legacy posting event plus leg builders
object PostingRules {
    /** ADR-0344 D7: the leg-described products get their journals from the posting-rule matrix. */
    private fun requireLegacy(deal: Deal) =
        check(deal.product.legacy) { "${deal.product} has no posting rules yet (ADR-0344 D7)" }

    fun settlement(deal: Deal): JournalSpec {
        requireLegacy(deal)
        val p = deal.principal
        val ccy = deal.currency
        val lines = when (deal.product) {
            ProductType.MM_PLACEMENT, ProductType.CNB_DEPOSIT_FACILITY -> listOf(
                leg(deal, LedgerRole.PRINCIPAL, Side.DEBIT, p),
                nostro(ccy, Side.CREDIT, p),
            )
            ProductType.MM_BORROWING, ProductType.CNB_LOMBARD -> listOf(
                nostro(ccy, Side.DEBIT, p),
                leg(deal, LedgerRole.PRINCIPAL, Side.CREDIT, p),
            )
            ProductType.FX_SPOT -> fxSpotSettlement(deal)
            else -> error("${deal.product} has no posting rules yet (ADR-0344 D7)")
        }
        return JournalSpec(deal.id, PostingEvent.SETTLED, lines)
    }

    private fun fxSpotSettlement(deal: Deal): List<PostingLine> {
        val fx = checkNotNull(deal.fx) { "FX_SPOT deal ${deal.id} without FX terms" }
        val ccy = deal.currency
        val czk = Deal.CZK
        val position = AccountingClasses.FX_POSITION
        val buy = listOf(
            nostro(ccy, Side.DEBIT, deal.principal),
            PostingLine(position, LedgerRole.PRINCIPAL, Side.CREDIT, deal.principal, ccy),
            PostingLine(position, LedgerRole.PRINCIPAL, Side.DEBIT, fx.counterAmount, czk),
            nostro(czk, Side.CREDIT, fx.counterAmount),
        )
        return if (fx.side == FxSide.BUY) buy else buy.map { it.copy(side = it.side.flipped()) }
    }

    private fun Side.flipped() = if (this == Side.DEBIT) Side.CREDIT else Side.DEBIT

    /** Interest accrued from the value date up to and including [date], capped at maturity. */
    fun accruedThrough(deal: Deal, date: LocalDate): BigDecimal {
        val total = deal.days
        if (total <= 0L) return BigDecimal.ZERO
        val elapsed = ChronoUnit.DAYS.between(deal.valueDate, date).coerceIn(0L, total)
        return RoundingPolicy.TREASURY_AMOUNT.divide(
            deal.interest.multiply(BigDecimal.valueOf(elapsed)),
            BigDecimal.valueOf(total),
        )
    }

    /** The journal accruing [date]'s interest, or null when that day adds nothing. */
    fun accrual(deal: Deal, date: LocalDate): JournalSpec? {
        if (deal.product == ProductType.FX_SPOT || !deal.product.legacy) return null
        val amount = accruedThrough(deal, date) - accruedThrough(deal, date.minusDays(1))
        if (amount.signum() <= 0) return null
        val lines = if (deal.product.isAsset) {
            listOf(
                leg(deal, LedgerRole.ACCRUED_INTEREST, Side.DEBIT, amount),
                leg(deal, LedgerRole.INTEREST_INCOME, Side.CREDIT, amount),
            )
        } else {
            listOf(
                leg(deal, LedgerRole.INTEREST_EXPENSE, Side.DEBIT, amount),
                leg(deal, LedgerRole.ACCRUED_INTEREST, Side.CREDIT, amount),
            )
        }
        return JournalSpec(deal.id, PostingEvent.ACCRUED, lines, suffix = date.toString())
    }

    fun maturity(deal: Deal, accrued: BigDecimal = BigDecimal.ZERO): JournalSpec {
        requireLegacy(deal)
        require(deal.product != ProductType.FX_SPOT) { "an FX spot deal has no maturity journal" }
        val p = deal.principal
        val i = deal.interest
        require(accrued.signum() >= 0 && accrued <= i) { "accrued $accrued outside 0..$i for ${deal.id}" }
        val rest = i - accrued
        val ccy = deal.currency
        val lines = when (deal.product) {
            ProductType.MM_PLACEMENT, ProductType.CNB_DEPOSIT_FACILITY -> listOfNotNull(
                nostro(ccy, Side.DEBIT, p + i),
                leg(deal, LedgerRole.PRINCIPAL, Side.CREDIT, p),
                optional(deal, LedgerRole.ACCRUED_INTEREST, Side.CREDIT, accrued),
                optional(deal, LedgerRole.INTEREST_INCOME, Side.CREDIT, rest),
            )
            ProductType.FX_SPOT -> error("unreachable: FX spot has no maturity")
            ProductType.MM_BORROWING, ProductType.CNB_LOMBARD -> listOfNotNull(
                leg(deal, LedgerRole.PRINCIPAL, Side.DEBIT, p),
                optional(deal, LedgerRole.ACCRUED_INTEREST, Side.DEBIT, accrued),
                optional(deal, LedgerRole.INTEREST_EXPENSE, Side.DEBIT, rest),
                nostro(ccy, Side.CREDIT, p + i),
            )
            else -> error("${deal.product} has no posting rules yet (ADR-0344 D7)")
        }
        return JournalSpec(deal.id, PostingEvent.MATURED, lines)
    }

    /** The offsetting journal for a SETTLED deal's reversal, unwinding [accrued]; null when nothing had posted. */
    fun reversal(deal: Deal, stateBeforeReversal: DealState, accrued: BigDecimal = BigDecimal.ZERO): JournalSpec? {
        if (stateBeforeReversal != DealState.SETTLED) return null
        val flipped = settlement(deal).lines.map { it.copy(side = it.side.flipped()) }
        val unwind = if (deal.product.isAsset) {
            listOfNotNull(
                optional(deal, LedgerRole.INTEREST_INCOME, Side.DEBIT, accrued),
                optional(deal, LedgerRole.ACCRUED_INTEREST, Side.CREDIT, accrued),
            )
        } else {
            listOfNotNull(
                optional(deal, LedgerRole.ACCRUED_INTEREST, Side.DEBIT, accrued),
                optional(deal, LedgerRole.INTEREST_EXPENSE, Side.CREDIT, accrued),
            )
        }
        return JournalSpec(deal.id, PostingEvent.REVERSED, flipped + unwind)
    }

    private fun leg(deal: Deal, role: LedgerRole, side: Side, amount: BigDecimal) = PostingLine(
        checkNotNull(deal.product.accountingClass) { "${deal.product} posts through the matrix (ADR-0344 D7)" },
        role,
        side,
        amount,
        deal.currency,
    )

    private fun nostro(currency: String, side: Side, amount: BigDecimal) =
        PostingLine(AccountingClasses.NOSTRO, LedgerRole.PRINCIPAL, side, amount, currency)

    private fun optional(deal: Deal, role: LedgerRole, side: Side, amount: BigDecimal): PostingLine? =
        if (amount.signum() > 0) leg(deal, role, side, amount) else null
}

/**
 * A journal being reversed cannot be offset account by account: it carries no recorded accounts (posted
 * before `deal_journals.resolved_accounts` existed, or the ledger's answer could not be paired with its
 * legs), or the recorded accounts do not match the legs it posted. Refused rather than re-resolved
 * against today's class map, which could offset a different account than the one that was posted to.
 * Mapped to 409 `REVERSAL_ACCOUNTS_UNKNOWN`; the operator remedy is in the service docs (backfill the
 * row from the ledger's own journal, then retry).
 */
class ReversalAccountsUnknownException(message: String) : IllegalStateException(message)

/** One journal the deal posted, as the posting table regenerates it, with what the ledger recorded for it. */
data class PostedJournalRecord(val journalId: UUID, val spec: JournalSpec, val accounts: List<ResolvedAccount>)

/**
 * Pins a reversal to what was actually posted (ADR-0341 D3, follow-up to #13205). The signed amounts
 * of every reversed journal are netted per `(class, role, currency, glAccountId)` and each account is
 * offset by exactly its own net, so accruals that landed on account A before a map re-point and on B
 * after it are unwound as two lines, A and B, each by what it received. Never re-resolves: a journal
 * without recorded accounts, or whose recorded accounts do not match its legs, fails the reversal.
 *
 * [rule] (the posting-table reversal) stays authoritative for WHAT is reversed: per class leg, the
 * per-account lines must sum to the rule's amount, or the reversal is refused as inconsistent.
 */
object PinnedReversal {
    private data class ClassKey(val accountingClass: String, val role: LedgerRole, val currency: String)

    private fun PostingLine.classKey() = ClassKey(accountingClass, role, currency)
    private fun PostingLine.signed() = if (side == Side.DEBIT) amount else amount.negate()

    fun build(rule: JournalSpec, reversed: List<PostedJournalRecord>): JournalSpec {
        val posted = netPerAccount(reversed)
        val ruleNet = LinkedHashMap<ClassKey, BigDecimal>()
        rule.lines.forEach { ruleNet.merge(it.classKey(), it.signed(), BigDecimal::add) }
        val keys = ruleNet.keys + posted.keys.filter { k -> posted.getValue(k).values.any { it.signum() != 0 } }
        keys.forEach { k -> requireOffsetsRule(rule.dealId, k, posted[k], ruleNet[k] ?: BigDecimal.ZERO) }
        return rule.copy(lines = keys.flatMap { k -> offsetLines(k, posted[k].orEmpty()) })
    }

    /** Signed amounts posted, per class key and, inside it, per account in first-posted order. */
    private fun netPerAccount(reversed: List<PostedJournalRecord>): Map<ClassKey, Map<UUID, BigDecimal>> {
        val posted = LinkedHashMap<ClassKey, LinkedHashMap<UUID, BigDecimal>>()
        reversed.forEach { journal ->
            requireRecorded(journal)
            journal.spec.lines.zip(journal.accounts).forEach { (line, account) ->
                posted.getOrPut(line.classKey()) { LinkedHashMap() }
                    .merge(account.glAccountId, line.signed(), BigDecimal::add)
            }
        }
        return posted
    }

    private fun requireRecorded(journal: PostedJournalRecord) {
        val what = "journal ${journal.journalId} (${journal.spec.idempotencyKey})"
        if (journal.accounts.isEmpty()) {
            throw ReversalAccountsUnknownException(
                "$what has no recorded accounts; refusing to re-resolve it against today's ledger map",
            )
        }
        val matches = journal.accounts.size == journal.spec.lines.size &&
            journal.accounts.zip(journal.spec.lines).all { (a, l) ->
                a.accountingClass == l.accountingClass && a.role == l.role && a.currency == l.currency
            }
        if (!matches) {
            throw ReversalAccountsUnknownException(
                "$what: recorded accounts do not match its ${journal.spec.lines.size} legs",
            )
        }
    }

    private fun requireOffsetsRule(dealId: UUID, k: ClassKey, posted: Map<UUID, BigDecimal>?, ruleNet: BigDecimal) {
        val offset = posted?.values?.fold(BigDecimal.ZERO, BigDecimal::add)?.negate() ?: BigDecimal.ZERO
        if (offset.compareTo(ruleNet) != 0) {
            throw ReversalAccountsUnknownException(
                "reversal of $dealId: ${k.accountingClass}/${k.role}/${k.currency} offsets $offset by the recorded " +
                    "journals but $ruleNet by the posting table; the records do not match",
            )
        }
    }

    private fun offsetLines(k: ClassKey, perAccount: Map<UUID, BigDecimal>): List<PostingLine> =
        perAccount.filterValues { it.signum() != 0 }.map { (account, net) ->
            PostingLine(
                k.accountingClass,
                k.role,
                if (net.signum() > 0) Side.CREDIT else Side.DEBIT,
                net.abs(),
                k.currency,
                account,
            )
        }
}
