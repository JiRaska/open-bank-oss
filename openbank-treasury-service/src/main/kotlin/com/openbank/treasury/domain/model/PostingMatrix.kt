// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.CashFlowKind
import com.openbank.treasury.domain.cashflow.FlowDirection
import com.openbank.treasury.domain.cashflow.ProductLedger
import com.openbank.treasury.domain.reference.Ifrs9Category
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** ADR-0344 D7: when a product is recognised on the balance sheet. */
enum class Recognition { TRADE_DATE, SETTLEMENT_DATE }

/**
 * The amount a template line takes, resolved from the event's [PostingContext]. A line whose amount
 * resolves to zero is not posted.
 */
enum class AmountExpression {
    /** The cash flow's own amount. */
    FLOW,

    /** A deal's notional (memo lines). */
    NOTIONAL,

    /** A security's face / nominal. */
    FACE,

    /** Face above the consideration (bought at a discount). */
    DISCOUNT,

    /** Consideration above the face (bought at a premium). */
    PREMIUM,

    /** Interest accrued on the flow up to the payment (the accrued account is cleared by it). */
    ACCRUED,

    /** The part of a paid flow not yet accrued (recognised on payment). */
    UNACCRUED,

    /** One day of interest, or of discount accretion / premium amortisation. */
    DAILY,

    /** The cash consideration plus purchased accrued interest of a bond purchase. */
    CONSIDERATION_AND_ACCRUED,

    /** Interest bought with a bond (accrued to the purchase value date). */
    PURCHASED_ACCRUED,

    /** Collateral nominal (repo memo lines). */
    COLLATERAL,

    /** An increase in fair value since the last revaluation. */
    GAIN,

    /** A decrease in fair value since the last revaluation. */
    LOSS,
}

/** Which flows a rule applies to; null = any. */
data class FlowSelector(val kind: CashFlowKind? = null, val direction: FlowDirection? = null)

data class LineTemplate(val side: Side, val glPurpose: String, val amount: AmountExpression)

/**
 * One row of the posting-rule matrix: `(product, event, IFRS 9 category) → journal template`.
 * [ifrs9Category] null = every category (the product is not measured by category). [selector]
 * narrows the row to one flow kind/direction; [glPurpose] `securities` resolves by the category.
 */
data class PostingRule(
    val product: ProductType,
    val event: PostingEvent,
    val ifrs9Category: Ifrs9Category?,
    val recognition: Recognition,
    val selector: FlowSelector,
    val lines: List<LineTemplate>,
    val version: Int = PostingMatrix.VERSION,
)

/** The resolved amounts of one event. */
class PostingContext(private val values: Map<AmountExpression, BigDecimal>) {
    operator fun get(e: AmountExpression): BigDecimal = values[e] ?: BigDecimal.ZERO
}

/** A matrix event has no row for this product/flow: refused, never posted to a guessed account. */
class NoPostingRuleException(message: String) : IllegalStateException(message)

/**
 * ADR-0344 D7: the posting-rule matrix for the eleven leg-described products, versioned data. The
 * five legacy products keep [PostingRules] (ADR-0315 D5) and are listed here for completeness.
 *
 * Recognition is declared per product: derivatives (FX forward/swap, FRA, IRS) on TRADE date as an
 * off-balance commitment memo until their flows settle; everything else on SETTLEMENT date. Every
 * event set: TRADE, SETTLEMENT, ACCRUAL, FIXING (records the fixed rate, posts nothing), PAYMENT,
 * AMORTISATION, REVALUATION, MATURITY, EARLY_TERMINATION, CORRECTION. A CORRECTION re-derives the
 * journals of the corrected events and posts their DELTA with the same templates; an
 * EARLY_TERMINATION is the MATURITY template on the termination date plus the unaccrued rest.
 */
@Suppress("TooManyFunctions") // one builder helper per kind of matrix row; data, not behaviour
object PostingMatrix {
    const val VERSION = 1

    private val D = Side.DEBIT
    private val C = Side.CREDIT
    private fun l(side: Side, purpose: String, amount: AmountExpression) = LineTemplate(side, purpose, amount)
    private val IN = FlowDirection.IN
    private val OUT = FlowDirection.OUT

    fun recognition(product: ProductType): Recognition = when (product) {
        ProductType.FX_FORWARD, ProductType.FX_SWAP, ProductType.FRA, ProductType.IRS -> Recognition.TRADE_DATE
        else -> Recognition.SETTLEMENT_DATE
    }

    private class Builder(val product: ProductType) {
        val rules = mutableListOf<PostingRule>()
        fun rule(
            event: PostingEvent,
            selector: FlowSelector = FlowSelector(),
            category: Ifrs9Category? = null,
            vararg lines: LineTemplate,
        ) {
            rules += PostingRule(product, event, category, recognition(product), selector, lines.toList())
        }
    }

    private fun product(p: ProductType, block: Builder.() -> Unit) = Builder(p).apply(block).rules

    /** An interest flow accrued daily into [accrued] against [pl] and cleared on payment. */
    private fun Builder.interest(direction: FlowDirection, accrued: String, pl: String) {
        val sel = FlowSelector(CashFlowKind.INTEREST, direction)
        if (direction == IN) {
            rule(
                PostingEvent.ACCRUAL,
                sel,
                null,
                l(D, accrued, AmountExpression.DAILY),
                l(C, pl, AmountExpression.DAILY),
            )
            rule(
                PostingEvent.PAYMENT,
                sel,
                null,
                l(D, "nostro", AmountExpression.FLOW),
                l(C, accrued, AmountExpression.ACCRUED),
                l(C, pl, AmountExpression.UNACCRUED),
            )
        } else {
            rule(
                PostingEvent.ACCRUAL,
                sel,
                null,
                l(D, pl, AmountExpression.DAILY),
                l(C, accrued, AmountExpression.DAILY),
            )
            rule(
                PostingEvent.PAYMENT,
                sel,
                null,
                l(D, accrued, AmountExpression.ACCRUED),
                l(D, pl, AmountExpression.UNACCRUED),
                l(C, "nostro", AmountExpression.FLOW),
            )
        }
    }

    /** An exchange moving [account] against the nostro: received = Dr nostro, paid = Cr nostro. */
    private fun Builder.exchange(event: PostingEvent, kind: CashFlowKind, account: String) {
        rule(
            event,
            FlowSelector(kind, IN),
            null,
            l(D, "nostro", AmountExpression.FLOW),
            l(C, account, AmountExpression.FLOW),
        )
        rule(
            event,
            FlowSelector(kind, OUT),
            null,
            l(D, account, AmountExpression.FLOW),
            l(C, "nostro", AmountExpression.FLOW),
        )
    }

    private fun Builder.commitmentMemo() {
        rule(
            PostingEvent.TRADE,
            FlowSelector(),
            null,
            l(D, "commitment-memo", AmountExpression.NOTIONAL),
            l(C, "commitment-contra", AmountExpression.NOTIONAL),
        )
        rule(
            PostingEvent.MATURITY,
            FlowSelector(),
            null,
            l(D, "commitment-contra", AmountExpression.NOTIONAL),
            l(C, "commitment-memo", AmountExpression.NOTIONAL),
        )
    }

    private fun Builder.derivativeRevaluation() {
        rule(
            PostingEvent.REVALUATION,
            FlowSelector(),
            null,
            l(D, "derivative-asset", AmountExpression.GAIN),
            l(C, "unrealised-gain", AmountExpression.GAIN),
        )
        rule(
            PostingEvent.REVALUATION,
            FlowSelector(),
            null,
            l(D, "unrealised-loss", AmountExpression.LOSS),
            l(C, "derivative-liability", AmountExpression.LOSS),
        )
    }

    private fun Builder.securityRevaluation() {
        rule(
            PostingEvent.REVALUATION,
            FlowSelector(),
            Ifrs9Category.FVTPL,
            l(D, "securities", AmountExpression.GAIN),
            l(C, "unrealised-gain", AmountExpression.GAIN),
        )
        rule(
            PostingEvent.REVALUATION,
            FlowSelector(),
            Ifrs9Category.FVTPL,
            l(D, "unrealised-loss", AmountExpression.LOSS),
            l(C, "securities", AmountExpression.LOSS),
        )
        rule(
            PostingEvent.REVALUATION,
            FlowSelector(),
            Ifrs9Category.FVOCI,
            l(D, "securities", AmountExpression.GAIN),
            l(C, "fvoci-reserve", AmountExpression.GAIN),
        )
        rule(
            PostingEvent.REVALUATION,
            FlowSelector(),
            Ifrs9Category.FVOCI,
            l(D, "fvoci-reserve", AmountExpression.LOSS),
            l(C, "securities", AmountExpression.LOSS),
        )
    }

    /** Discount accretion (Dr premium-discount) or premium amortisation (Cr premium-discount), daily and residual at maturity. */
    private fun Builder.discountAmortisation() {
        rule(
            PostingEvent.AMORTISATION,
            FlowSelector(),
            null,
            l(D, "premium-discount", AmountExpression.DISCOUNT),
            l(C, "income-securities", AmountExpression.DISCOUNT),
            l(D, "income-securities", AmountExpression.PREMIUM),
            l(C, "premium-discount", AmountExpression.PREMIUM),
        )
    }

    private val NEW_PRODUCT_RULES: List<PostingRule> = listOf(
        product(ProductType.CASH) {
            exchange(PostingEvent.SETTLEMENT, CashFlowKind.NOTIONAL_EXCHANGE, "cash-transit")
        },
        product(ProductType.LOAN) {
            rule(
                PostingEvent.SETTLEMENT,
                FlowSelector(CashFlowKind.NOTIONAL_EXCHANGE, OUT),
                null,
                l(D, "loan", AmountExpression.FLOW),
                l(C, "nostro", AmountExpression.FLOW),
            )
            interest(IN, "accrued-loan", "income-loan")
            rule(
                PostingEvent.AMORTISATION,
                FlowSelector(CashFlowKind.PRINCIPAL, IN),
                null,
                l(D, "nostro", AmountExpression.FLOW),
                l(C, "loan", AmountExpression.FLOW),
            )
            rule(
                PostingEvent.MATURITY,
                FlowSelector(CashFlowKind.PRINCIPAL, IN),
                null,
                l(D, "nostro", AmountExpression.FLOW),
                l(C, "loan", AmountExpression.FLOW),
            )
        },
        listOf(ProductType.TREASURY_BILL, ProductType.BILL_OF_EXCHANGE).flatMap { p ->
            product(p) {
                Ifrs9Category.entries.forEach { cat ->
                    rule(
                        PostingEvent.SETTLEMENT,
                        FlowSelector(CashFlowKind.NOTIONAL_EXCHANGE, OUT),
                        cat,
                        l(D, "securities", AmountExpression.FACE),
                        l(C, "premium-discount", AmountExpression.DISCOUNT),
                        l(D, "premium-discount", AmountExpression.PREMIUM),
                        l(C, "nostro", AmountExpression.FLOW),
                    )
                    rule(
                        PostingEvent.MATURITY,
                        FlowSelector(CashFlowKind.NOTIONAL_EXCHANGE, IN),
                        cat,
                        l(D, "nostro", AmountExpression.FLOW),
                        l(C, "securities", AmountExpression.FLOW),
                    )
                }
                discountAmortisation()
                securityRevaluation()
            }
        },
        product(ProductType.BOND) {
            Ifrs9Category.entries.forEach { cat ->
                rule(
                    PostingEvent.SETTLEMENT,
                    FlowSelector(CashFlowKind.PRINCIPAL, OUT),
                    cat,
                    l(D, "securities", AmountExpression.FACE),
                    l(C, "premium-discount", AmountExpression.DISCOUNT),
                    l(D, "premium-discount", AmountExpression.PREMIUM),
                    l(D, "accrued-securities", AmountExpression.PURCHASED_ACCRUED),
                    l(C, "nostro", AmountExpression.CONSIDERATION_AND_ACCRUED),
                )
                rule(
                    PostingEvent.MATURITY,
                    FlowSelector(CashFlowKind.PRINCIPAL, IN),
                    cat,
                    l(D, "nostro", AmountExpression.FLOW),
                    l(C, "securities", AmountExpression.FLOW),
                )
            }
            interest(IN, "accrued-securities", "income-securities")
            discountAmortisation()
            securityRevaluation()
        },
        product(ProductType.REPO) {
            rule(
                PostingEvent.SETTLEMENT,
                FlowSelector(CashFlowKind.NOTIONAL_EXCHANGE, IN),
                null,
                l(D, "nostro", AmountExpression.FLOW),
                l(C, "repo", AmountExpression.FLOW),
                l(D, "collateral-pledged-memo", AmountExpression.COLLATERAL),
                l(C, "collateral-pledged-contra", AmountExpression.COLLATERAL),
            )
            interest(OUT, "accrued-payable-repo", "expense-repo")
            rule(
                PostingEvent.MATURITY,
                FlowSelector(CashFlowKind.PRINCIPAL, OUT),
                null,
                l(D, "repo", AmountExpression.FLOW),
                l(C, "nostro", AmountExpression.FLOW),
                l(D, "collateral-pledged-contra", AmountExpression.COLLATERAL),
                l(C, "collateral-pledged-memo", AmountExpression.COLLATERAL),
            )
        },
        product(ProductType.REVERSE_REPO) {
            rule(
                PostingEvent.SETTLEMENT,
                FlowSelector(CashFlowKind.NOTIONAL_EXCHANGE, OUT),
                null,
                l(D, "reverse-repo", AmountExpression.FLOW),
                l(C, "nostro", AmountExpression.FLOW),
                l(D, "collateral-received-memo", AmountExpression.COLLATERAL),
                l(C, "collateral-received-contra", AmountExpression.COLLATERAL),
            )
            interest(IN, "accrued-reverse-repo", "income-reverse-repo")
            rule(
                PostingEvent.MATURITY,
                FlowSelector(CashFlowKind.PRINCIPAL, IN),
                null,
                l(D, "nostro", AmountExpression.FLOW),
                l(C, "reverse-repo", AmountExpression.FLOW),
                l(D, "collateral-received-contra", AmountExpression.COLLATERAL),
                l(C, "collateral-received-memo", AmountExpression.COLLATERAL),
            )
        },
        product(ProductType.FX_FORWARD) {
            commitmentMemo()
            exchange(PostingEvent.SETTLEMENT, CashFlowKind.NOTIONAL_EXCHANGE, "fx-position")
            derivativeRevaluation()
        },
        product(ProductType.FX_SWAP) {
            commitmentMemo()
            exchange(PostingEvent.SETTLEMENT, CashFlowKind.NOTIONAL_EXCHANGE, "fx-position")
            exchange(PostingEvent.MATURITY, CashFlowKind.NOTIONAL_EXCHANGE, "fx-position")
            derivativeRevaluation()
        },
        product(ProductType.FRA) {
            commitmentMemo()
            rule(PostingEvent.FIXING, FlowSelector(CashFlowKind.INTEREST), null)
            rule(
                PostingEvent.PAYMENT,
                FlowSelector(CashFlowKind.INTEREST, IN),
                null,
                l(D, "nostro", AmountExpression.FLOW),
                l(C, "realised-gain", AmountExpression.FLOW),
            )
            rule(
                PostingEvent.PAYMENT,
                FlowSelector(CashFlowKind.INTEREST, OUT),
                null,
                l(D, "realised-loss", AmountExpression.FLOW),
                l(C, "nostro", AmountExpression.FLOW),
            )
            derivativeRevaluation()
        },
        product(ProductType.IRS) {
            commitmentMemo()
            rule(PostingEvent.FIXING, FlowSelector(CashFlowKind.INTEREST), null)
            interest(IN, "accrued-derivative", "income-derivative")
            interest(OUT, "accrued-payable-derivative", "expense-derivative")
            derivativeRevaluation()
        },
    ).flatten()

    /** The legacy rows ([PostingRules]), so `GET /posting-rules` lists every product. */
    private val LEGACY_RULES: List<PostingRule> = listOf(
        product(ProductType.MM_PLACEMENT) {
            rule(
                PostingEvent.SETTLED,
                FlowSelector(),
                null,
                l(D, "placement", AmountExpression.FLOW),
                l(C, "nostro", AmountExpression.FLOW),
            )
            rule(
                PostingEvent.ACCRUED,
                FlowSelector(),
                null,
                l(D, "accrued-receivable", AmountExpression.DAILY),
                l(C, "income", AmountExpression.DAILY),
            )
            rule(
                PostingEvent.MATURED,
                FlowSelector(),
                null,
                l(D, "nostro", AmountExpression.FLOW),
                l(C, "placement", AmountExpression.FACE),
                l(C, "accrued-receivable", AmountExpression.ACCRUED),
                l(C, "income", AmountExpression.UNACCRUED),
            )
        },
        product(ProductType.CNB_DEPOSIT_FACILITY) {
            rule(
                PostingEvent.SETTLED,
                FlowSelector(),
                null,
                l(D, "cnb", AmountExpression.FLOW),
                l(C, "nostro", AmountExpression.FLOW),
            )
            rule(
                PostingEvent.ACCRUED,
                FlowSelector(),
                null,
                l(D, "accrued-receivable", AmountExpression.DAILY),
                l(C, "income", AmountExpression.DAILY),
            )
            rule(
                PostingEvent.MATURED,
                FlowSelector(),
                null,
                l(D, "nostro", AmountExpression.FLOW),
                l(C, "cnb", AmountExpression.FACE),
                l(C, "accrued-receivable", AmountExpression.ACCRUED),
                l(C, "income", AmountExpression.UNACCRUED),
            )
        },
        product(ProductType.MM_BORROWING) {
            rule(
                PostingEvent.SETTLED,
                FlowSelector(),
                null,
                l(D, "nostro", AmountExpression.FLOW),
                l(C, "borrowing", AmountExpression.FLOW),
            )
            rule(
                PostingEvent.ACCRUED,
                FlowSelector(),
                null,
                l(D, "expense", AmountExpression.DAILY),
                l(C, "accrued-payable", AmountExpression.DAILY),
            )
            rule(
                PostingEvent.MATURED,
                FlowSelector(),
                null,
                l(D, "borrowing", AmountExpression.FACE),
                l(D, "accrued-payable", AmountExpression.ACCRUED),
                l(D, "expense", AmountExpression.UNACCRUED),
                l(C, "nostro", AmountExpression.FLOW),
            )
        },
        product(ProductType.CNB_LOMBARD) {
            rule(
                PostingEvent.SETTLED,
                FlowSelector(),
                null,
                l(D, "nostro", AmountExpression.FLOW),
                l(C, "cnb-borrowing", AmountExpression.FLOW),
            )
            rule(
                PostingEvent.ACCRUED,
                FlowSelector(),
                null,
                l(D, "expense", AmountExpression.DAILY),
                l(C, "accrued-payable", AmountExpression.DAILY),
            )
            rule(
                PostingEvent.MATURED,
                FlowSelector(),
                null,
                l(D, "cnb-borrowing", AmountExpression.FACE),
                l(D, "accrued-payable", AmountExpression.ACCRUED),
                l(D, "expense", AmountExpression.UNACCRUED),
                l(C, "nostro", AmountExpression.FLOW),
            )
        },
        product(ProductType.FX_SPOT) {
            exchange(PostingEvent.SETTLED, CashFlowKind.NOTIONAL_EXCHANGE, "fx-position")
        },
    ).flatten()

    val rules: List<PostingRule> = LEGACY_RULES + NEW_PRODUCT_RULES

    /** The rows for one event of one product, narrowed by category and flow. */
    fun rows(
        product: ProductType,
        event: PostingEvent,
        category: Ifrs9Category,
        flow: CashFlow? = null,
    ): List<PostingRule> = rules.filter { r ->
        r.product == product &&
            r.event == event &&
            (r.ifrs9Category == null || r.ifrs9Category == category) &&
            (
                flow == null ||
                    (
                        (r.selector.kind == null || r.selector.kind == flow.kind) &&
                            (r.selector.direction == null || r.selector.direction == flow.direction)
                        )
                )
    }

    fun hasEvent(product: ProductType, event: PostingEvent): Boolean = rules.any {
        it.product == product &&
            it.event == event
    }

    /** Resolve [rows] against [ctx] into posting lines in [currency]; zero lines are dropped. */
    fun lines(
        rows: List<PostingRule>,
        ctx: PostingContext,
        currency: String,
        category: Ifrs9Category,
    ): List<PostingLine> = rows.flatMap { it.lines }.mapNotNull { t ->
        val amount = ctx[t.amount]
        if (amount.signum() <=
            0
        ) {
            null
        } else {
            PostingLine(code(t.glPurpose, currency, category), t.side, amount, currency)
        }
    }

    fun code(purpose: String, currency: String, category: Ifrs9Category): String = TreasuryChart.code(
        currency,
        if (purpose == "securities") {
            when (category) {
                Ifrs9Category.AMORTISED_COST -> "securities-ac"
                Ifrs9Category.FVOCI -> "securities-fvoci"
                Ifrs9Category.FVTPL -> "securities-fvtpl"
            }
        } else {
            purpose
        },
    )
}

/**
 * Journals of the matrix products, derived from the deal's stored cash-flow ledger. Pure.
 *
 * - settlement: the flows that settle on the value date (an initial exchange, the exchange-only
 *   flows paid that day, a bond purchase) plus, for a TRADE_DATE product, its commitment memo;
 * - accrual: one day of every interest flow running over that day, and of a security's discount
 *   accretion or premium amortisation, straight-line from value date to maturity;
 * - payment: an interest flow (PAYMENT) or a non-final principal repayment (AMORTISATION);
 * - maturity: the final exchange or principal, the memo release and any residual amortisation.
 *
 * Idempotency keys: `treasury:<dealId>:<event>:<flowId|date>` (ADR-0344 D5).
 */
@Suppress("TooManyFunctions") // one function per matrix event plus its arithmetic, kept together
object MatrixPostings {
    fun settlementFlows(deal: Deal, flows: List<CashFlow>): List<CashFlow> {
        val date = firstSettlementDate(deal, flows)
        return flows.filter { f -> f.amount != null && f.paymentDate == date && settles(deal, f) }
    }

    /** An initial exchange, an exchange-only flow, or a bond purchase (never an FRA's settlement). */
    private fun settles(deal: Deal, f: CashFlow) =
        (f.periodIndex == -1 && f.legIndex != ProductLedger.DEAL_LEVEL_LEG) ||
            (f.legIndex == ProductLedger.DEAL_LEVEL_LEG && deal.product == ProductType.BOND) ||
            exchangeOnly(deal, f)

    /** The final exchanges/principal on the maturity date that the MATURITY rows cover. */
    fun maturityFlows(deal: Deal, flows: List<CashFlow>): List<CashFlow> {
        val settling = settlementFlows(deal, flows).map { it.flowId }.toSet()
        val last = flows.filter { it.kind != CashFlowKind.INTEREST && it.flowId !in settling && it.amount != null }
            .maxOfOrNull { it.paymentDate } ?: return emptyList()
        return flows.filter { f ->
            f.flowId !in settling &&
                f.amount != null &&
                f.paymentDate == last &&
                f.kind != CashFlowKind.INTEREST &&
                PostingMatrix.rows(deal.product, PostingEvent.MATURITY, Ifrs9Category.AMORTISED_COST, f).any {
                    it.lines.isNotEmpty() &&
                        it.selector.kind != null
                }
        }
    }

    /** Interest flows and intermediate principal repayments, each posted once when paid. */
    fun paymentFlows(deal: Deal, flows: List<CashFlow>): List<CashFlow> {
        val excluded = (settlementFlows(deal, flows) + maturityFlows(deal, flows)).map { it.flowId }.toSet()
        return flows.filter { f ->
            f.flowId !in excluded &&
                f.amount != null &&
                (f.kind == CashFlowKind.INTEREST || f.kind == CashFlowKind.PRINCIPAL)
        }
    }

    private fun firstSettlementDate(deal: Deal, flows: List<CashFlow>): LocalDate =
        flows.filter { settles(deal, it) }.minOfOrNull { it.paymentDate } ?: deal.valueDate

    private fun exchangeOnly(deal: Deal, f: CashFlow) = f.kind == CashFlowKind.NOTIONAL_EXCHANGE &&
        deal.legs.getOrNull(f.legIndex)?.rate?.type == RateType.NONE

    fun trade(deal: Deal): JournalSpec? {
        if (PostingMatrix.recognition(deal.product) != Recognition.TRADE_DATE) return null
        val rows = PostingMatrix.rows(deal.product, PostingEvent.TRADE, Ifrs9Category.AMORTISED_COST)
        val lines = PostingMatrix.lines(
            rows,
            PostingContext(mapOf(AmountExpression.NOTIONAL to deal.principal)),
            deal.currency,
            Ifrs9Category.AMORTISED_COST,
        )
        return lines.takeIf {
            it.size >= 2
        }?.let { JournalSpec(deal.id, PostingEvent.TRADE, it, suffix = deal.tradeDate.toString()) }
    }

    fun settlement(deal: Deal, flows: List<CashFlow>, category: Ifrs9Category): JournalSpec? {
        val settling = settlementFlows(deal, flows)
        val face = securityFace(deal, flows)
        val lines = if (deal.product == ProductType.BOND) {
            val clean = settling.filter { it.kind == CashFlowKind.PRINCIPAL }.sumOf { checkNotNull(it.amount) }
            val purchased = settling.filter { it.kind == CashFlowKind.INTEREST }.sumOf { checkNotNull(it.amount) }
            val principal = settling.first { it.kind == CashFlowKind.PRINCIPAL }
            val ctx = securityContext(face, clean) + mapOf(
                AmountExpression.PURCHASED_ACCRUED to purchased,
                AmountExpression.CONSIDERATION_AND_ACCRUED to clean + purchased,
            )
            PostingMatrix.lines(
                PostingMatrix.rows(deal.product, PostingEvent.SETTLEMENT, category, principal),
                PostingContext(ctx),
                deal.currency,
                category,
            )
        } else {
            settling.flatMap { f ->
                val rows = PostingMatrix.rows(deal.product, PostingEvent.SETTLEMENT, category, f).filter {
                    it.selector.kind !=
                        null
                }
                if (rows.isEmpty()) {
                    throw NoPostingRuleException(
                        "${deal.product} has no SETTLEMENT rule for a ${f.direction} ${f.kind}",
                    )
                }
                val amount = checkNotNull(f.amount)
                val ctx = securityContext(face, amount) + mapOf(
                    AmountExpression.FLOW to amount,
                    AmountExpression.COLLATERAL to (deal.header.collateral?.nominal ?: BigDecimal.ZERO),
                )
                PostingMatrix.lines(rows, PostingContext(ctx), f.currency, category)
            }
        }
        // An FX outright is final once settled: its commitment memo is released with the exchange.
        val release = if (deal.product.settlesOnOneDay) memoRelease(deal, category) else emptyList()
        return (lines + release).takeIf { it.size >= 2 }?.let {
            JournalSpec(deal.id, PostingEvent.SETTLEMENT, it, suffix = deal.valueDate.toString())
        }
    }

    /**
     * One day's accrual of every interest flow and the security amortisation, or null. [paid]
     * flows no longer accrue.
     */
    fun accrual(
        deal: Deal,
        flows: List<CashFlow>,
        day: LocalDate,
        category: Ifrs9Category,
        paid: Set<UUID>,
    ): JournalSpec? {
        val interest = flows.filter { f ->
            f.kind == CashFlowKind.INTEREST &&
                f.amount != null &&
                f.flowId !in paid &&
                f.legIndex != ProductLedger.DEAL_LEVEL_LEG &&
                f.accrualStart != null &&
                f.accrualEnd != null
        }.flatMap { f ->
            val daily = dailyInterest(deal, f, day)
            if (daily.signum() <= 0) {
                emptyList()
            } else {
                val rows = PostingMatrix.rows(deal.product, PostingEvent.ACCRUAL, category, f).filter {
                    it.selector.kind !=
                        null
                }
                PostingMatrix.lines(rows, PostingContext(mapOf(AmountExpression.DAILY to daily)), f.currency, category)
            }
        }
        val amortisation = amortisationLines(deal, flows, category, day)
        val lines = interest + amortisation
        return lines.takeIf {
            it.size >= 2
        }?.let { JournalSpec(deal.id, PostingEvent.ACCRUAL, it, suffix = day.toString()) }
    }

    /** A paid interest flow or intermediate repayment, clearing what [accruedSoFar] recognised. */
    fun payment(deal: Deal, flow: CashFlow, accruedSoFar: BigDecimal, category: Ifrs9Category): JournalSpec {
        val amount = checkNotNull(flow.amount) { "flow ${flow.flowId} has no amount (awaiting its fixing)" }
        val event = if (flow.kind == CashFlowKind.PRINCIPAL) PostingEvent.AMORTISATION else PostingEvent.PAYMENT
        val rows = PostingMatrix.rows(deal.product, event, category, flow).filter { it.selector.kind != null }
        if (rows.isEmpty()) {
            throw NoPostingRuleException(
                "${deal.product} has no $event rule for a ${flow.direction} ${flow.kind}",
            )
        }
        val accrued = accruedSoFar.min(amount)
        val ctx = PostingContext(
            mapOf(
                AmountExpression.FLOW to amount,
                AmountExpression.ACCRUED to accrued,
                AmountExpression.UNACCRUED to amount - accrued,
            ),
        )
        return JournalSpec(
            deal.id,
            event,
            PostingMatrix.lines(rows, ctx, flow.currency, category),
            suffix = flow.flowId.toString(),
        )
    }

    fun maturity(deal: Deal, flows: List<CashFlow>, category: Ifrs9Category, amortisedSoFar: BigDecimal): JournalSpec? {
        val final = maturityFlows(deal, flows).flatMap { f ->
            val rows = PostingMatrix.rows(deal.product, PostingEvent.MATURITY, category, f).filter {
                it.selector.kind !=
                    null
            }
            val ctx = PostingContext(
                mapOf(
                    AmountExpression.FLOW to checkNotNull(f.amount),
                    AmountExpression.COLLATERAL to (deal.header.collateral?.nominal ?: BigDecimal.ZERO),
                ),
            )
            PostingMatrix.lines(rows, ctx, f.currency, category)
        }
        val memo = memoRelease(deal, category)
        val residual = amortisationTotal(deal, flows) - amortisedSoFar
        val rest = if (residual.signum() == 0) {
            emptyList()
        } else {
            amortisationLinesFor(deal, category, residual)
        }
        val lines = final + memo + rest
        return lines.takeIf {
            it.size >= 2
        }?.let { JournalSpec(deal.id, PostingEvent.MATURITY, it, suffix = deal.maturityDate.toString()) }
    }

    private fun memoRelease(deal: Deal, category: Ifrs9Category): List<PostingLine> =
        PostingMatrix.rows(deal.product, PostingEvent.MATURITY, category).filter { it.selector.kind == null }
            .let {
                PostingMatrix.lines(
                    it,
                    PostingContext(mapOf(AmountExpression.NOTIONAL to deal.principal)),
                    deal.currency,
                    category,
                )
            }

    /** A journal and the accounting date it posts on. */
    data class Dated(val spec: JournalSpec, val entryDate: LocalDate)

    /**
     * The daily run for one SETTLED deal, from the day after [lastAccrual] (the value date when
     * none) through [through]: each day's accrual, then the flows paid that day. A flow paid before
     * the run starts and not yet posted is posted first. [paid] flows are never posted again, and a
     * paid flow stops accruing. Pure: the caller posts the journals in order.
     */
    fun run(
        deal: Deal,
        flows: List<CashFlow>,
        category: Ifrs9Category,
        lastAccrual: LocalDate?,
        paid: Set<UUID>,
        through: LocalDate,
    ): List<Dated> {
        val out = mutableListOf<Dated>()
        val done = paid.toMutableSet()
        val due = paymentFlows(deal, flows).sortedWith(
            compareBy({
                it.paymentDate
            }, { it.legIndex }, { it.periodIndex }),
        )
        var accruedTo = lastAccrual ?: deal.valueDate
        fun payUpTo(day: LocalDate) {
            due.filter { it.flowId !in done && !it.paymentDate.isAfter(day) }.forEach { f ->
                val accrued = if (f.accrualStart != null && f.accrualEnd != null) {
                    accruedThrough(deal, f, minOf(accruedTo, f.paymentDate))
                } else {
                    BigDecimal.ZERO
                }
                out += Dated(payment(deal, f, accrued, category), f.paymentDate)
                done += f.flowId
            }
        }
        payUpTo(accruedTo)
        var day = accruedTo.plusDays(1)
        while (!day.isAfter(through)) {
            accrual(deal, flows, day, category, done)?.let { out += Dated(it, day) }
            accruedTo = day
            payUpTo(day)
            day = day.plusDays(1)
        }
        return out
    }

    /** The latest payment date of any flow: the day the deal's last cash moves. */
    fun lastPaymentDate(deal: Deal, flows: List<CashFlow>): LocalDate =
        flows.maxOfOrNull { it.paymentDate } ?: deal.maturityDate

    // ── interest and amortisation arithmetic (straight-line by calendar day, exact in total) ──

    /** Cumulative interest of [f] recognised through [day]; the flow's own amount at its accrual end. */
    fun accruedThrough(deal: Deal, f: CashFlow, day: LocalDate): BigDecimal {
        val start = checkNotNull(f.accrualStart)
        val end = checkNotNull(f.accrualEnd)
        val from = if (deal.product == ProductType.BOND && start.isBefore(deal.valueDate)) deal.valueDate else start
        val purchased = if (from != start) purchasedAccrued(deal, f) else BigDecimal.ZERO
        val total = ChronoUnit.DAYS.between(from, end)
        if (total <= 0L) return checkNotNull(f.amount)
        val elapsed = ChronoUnit.DAYS.between(from, day).coerceIn(0L, total)
        val rest = checkNotNull(f.amount) - purchased
        return purchased +
            RoundingPolicy.TREASURY_AMOUNT.divide(rest.multiply(BigDecimal.valueOf(elapsed)), BigDecimal.valueOf(total))
    }

    private fun dailyInterest(deal: Deal, f: CashFlow, day: LocalDate): BigDecimal =
        accruedThrough(deal, f, day) - accruedThrough(deal, f, day.minusDays(1))

    /** A bond's interest bought with the purchase, on the coupon running at the value date. */
    private fun purchasedAccrued(deal: Deal, running: CashFlow): BigDecimal =
        deal.legs.firstOrNull()?.rate?.rate?.let { coupon ->
            val terms = deal.header.securityTerms ?: return@let BigDecimal.ZERO
            SimpleInterest.of(
                terms.nominal,
                coupon,
                deal.legs.first().dayCount,
                checkNotNull(running.accrualStart),
                deal.valueDate,
            )
        } ?: BigDecimal.ZERO

    private fun securityFace(deal: Deal, flows: List<CashFlow>): BigDecimal = when (deal.product) {
        ProductType.BOND -> deal.header.securityTerms?.nominal ?: deal.principal
        ProductType.TREASURY_BILL, ProductType.BILL_OF_EXCHANGE ->
            flows.filter {
                it.kind == CashFlowKind.NOTIONAL_EXCHANGE && it.direction == FlowDirection.IN
            }.maxOfOrNull { checkNotNull(it.amount) }
                ?: deal.principal
        else -> BigDecimal.ZERO
    }

    private fun securityContext(face: BigDecimal, consideration: BigDecimal): Map<AmountExpression, BigDecimal> = mapOf(
        AmountExpression.FACE to face,
        AmountExpression.FLOW to consideration,
        AmountExpression.DISCOUNT to (face - consideration).max(BigDecimal.ZERO),
        AmountExpression.PREMIUM to (consideration - face).max(BigDecimal.ZERO),
    )

    /** Face − consideration: positive = discount to accrete, negative = premium to amortise. */
    fun amortisationTotal(deal: Deal, flows: List<CashFlow>): BigDecimal {
        if (!PostingMatrix.hasEvent(deal.product, PostingEvent.AMORTISATION) ||
            deal.product !in setOf(ProductType.BOND, ProductType.TREASURY_BILL, ProductType.BILL_OF_EXCHANGE)
        ) {
            return BigDecimal.ZERO
        }
        val settling = settlementFlows(deal, flows)
        val consideration = settling.filter { it.kind != CashFlowKind.INTEREST }.sumOf { checkNotNull(it.amount) }
        return securityFace(deal, flows) - consideration
    }

    fun amortisedThrough(deal: Deal, flows: List<CashFlow>, day: LocalDate): BigDecimal {
        val total = amortisationTotal(deal, flows)
        val days = ChronoUnit.DAYS.between(deal.valueDate, deal.maturityDate)
        if (total.signum() == 0 || days <= 0L) return BigDecimal.ZERO
        val elapsed = ChronoUnit.DAYS.between(deal.valueDate, day).coerceIn(0L, days)
        return RoundingPolicy.TREASURY_AMOUNT.divide(
            total.multiply(BigDecimal.valueOf(elapsed)),
            BigDecimal.valueOf(days),
        )
    }

    private fun amortisationLines(
        deal: Deal,
        flows: List<CashFlow>,
        category: Ifrs9Category,
        day: LocalDate,
    ): List<PostingLine> {
        val daily = amortisedThrough(deal, flows, day) - amortisedThrough(deal, flows, day.minusDays(1))
        return if (daily.signum() == 0) emptyList() else amortisationLinesFor(deal, category, daily)
    }

    private fun amortisationLinesFor(deal: Deal, category: Ifrs9Category, signed: BigDecimal): List<PostingLine> {
        val rows = PostingMatrix.rows(deal.product, PostingEvent.AMORTISATION, category)
        val ctx = PostingContext(
            mapOf(
                AmountExpression.DISCOUNT to signed.max(BigDecimal.ZERO),
                AmountExpression.PREMIUM to signed.negate().max(BigDecimal.ZERO),
            ),
        )
        return PostingMatrix.lines(rows, ctx, deal.currency, category)
    }

    /** The REVALUATION journal for a fair-value change from the risk engine's valuation (never priced here). */
    fun revaluation(
        deal: Deal,
        change: BigDecimal,
        currency: String,
        asOf: LocalDate,
        category: Ifrs9Category,
    ): JournalSpec? {
        val rows = PostingMatrix.rows(deal.product, PostingEvent.REVALUATION, category)
        if (rows.isEmpty() || change.signum() == 0) return null
        val ctx = PostingContext(
            mapOf(
                AmountExpression.GAIN to change.max(BigDecimal.ZERO),
                AmountExpression.LOSS to change.negate().max(BigDecimal.ZERO),
            ),
        )
        val lines = PostingMatrix.lines(rows, ctx, currency, category)
        return lines.takeIf {
            it.size >= 2
        }?.let { JournalSpec(deal.id, PostingEvent.REVALUATION, it, suffix = asOf.toString()) }
    }
}
