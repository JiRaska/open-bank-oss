// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.AccountingClass
import com.openbank.libs.product.ir.Action
import com.openbank.libs.product.ir.Hook
import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.IrLimits
import com.openbank.libs.product.ir.ProductClock
import com.openbank.libs.product.ir.ProductEvent
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductIrCanonical
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.ir.ProductState
import com.openbank.libs.product.replay.ProductReplay
import com.openbank.libs.product.replay.ReplayPortfolio
import com.openbank.libs.product.replay.ReplayRange
import com.openbank.libs.product.replay.ReplayResult
import com.openbank.libs.productrevision.HmacIrSigner
import com.openbank.libs.productrevision.IrSignatureVerifierPort
import com.openbank.libs.productrevision.PinnedRevisionLoader
import com.openbank.libs.productrevision.RevisionIdentity
import com.openbank.libs.productrevision.SignedRevisionCopy
import com.openbank.libs.productrevision.SignedRevisionSource
import com.openbank.products.catalog.ReferenceProducts
import com.openbank.simulation.model.CapitalisationResidueTracker
import com.openbank.simulation.model.ProductShadowBook
import java.math.BigDecimal
import java.util.UUID

/**
 * ADR-0352 phase 4: a published reference product (`openbank-products`) replayed over a portfolio
 * for several simulated years through the one pure interpreter, with every instruction booked into
 * the [ProductShadowBook].
 *
 * The revision is loaded the way a consumer loads it — signed at "publish", then fetched and
 * verified by `PinnedRevisionLoader` against its pin — so the scenario exercises the signed path,
 * not a bare `compile()`. The signing key is a fixed simulation HMAC key: deterministic, and never
 * a deployment key (`HmacIrSigner` is the laptop/test adapter by its own contract).
 *
 * Invariants checked after the run, each a named violation in [ProductReplayReport.violations]:
 *
 * | invariant | meaning |
 * |---|---|
 * | `product-money-conservation` | every `Post` balanced per currency over the run |
 * | `shadow-trial-balance` | the shadow book's trial balance is zero at every simulated day end |
 * | `shadow-deposit-reconciles` | each customer's deposit position equals the replay's final balance |
 * | `no-negative-balance` | no accepted customer posting leaves a balance below the product's overdraft floor |
 * | `monotone-tier` | a larger balance never accrues less interest on the same day |
 * | `bounded-termination` | evaluations within the replay's structural bound |
 * | `accrued-closure-reconciles` | per account, opening + accrued - capitalised - other debits == the book's accrued-interest-payable position |
 * | `capitalisation-residue-bounded` | no capitalisation leaves more than half a cent either way on accrued interest payable |
 *
 * IR v1 capitalises HALF_UP to the cent, so the residue a capitalisation leaves is not zero: it is
 * up to 0.005 either way and negative whenever the cent was rounded up (an over-payment of under
 * half a cent, recovered from the next period's accrual). An `AT_MATURITY` product keeps its last
 * residue on accrued interest payable for good. That is the named limitation
 * [V1_CAPITALISATION_RESIDUE], reported in [ProductReplayReport.limitations] rather than read as a
 * pass; an explicit rounding mode and a final residue sweep are IR v2 work.
 *
 * A balance the product's OWN debits (a scheduled fee, a penalty) take below the floor is not a
 * violation of `prePosting` — no rule of IR v1 can refuse a scheduled fee — and is reported in
 * [ProductReplayReport.findings] with the rules that debited the deposit. On the reference savings
 * product a dormant account below the fee-waiver threshold is drained by the monthly fee and goes
 * negative; the replay surfaces that, it does not hide it.
 *
 * Not part of the seeded per-step `World` loop: a multi-year replay is a different time scale from
 * a 50-step money-path interleaving, so it runs as its own scenario (`ProductReplayScenarioTest`).
 */
object ProductReplayScenario {

    /**
     * A reference product, the floor no accepted customer posting may take its balance below, and
     * the invariants it is KNOWN to violate today. A known violation must still occur — the test
     * fails once it stops — so fixing the product forces the entry out instead of leaving it to rot.
     */
    data class ReferenceCase(
        val name: String,
        val overdraftFloor: BigDecimal,
        val knownViolations: Set<String> = emptySet(),
    )

    val CASES: List<ReferenceCase> = listOf(
        ReferenceCase("savings-fixed", BigDecimal.ZERO),
        // ReferenceProducts.currentAccount's arranged overdraft: postings may take it to -500.00.
        ReferenceCase("current-account", BigDecimal("-500.00")),
        ReferenceCase("term-deposit", BigDecimal.ZERO),
    )

    /** The IR v1 limitation every capitalising product carries: see the class KDoc. */
    const val V1_CAPITALISATION_RESIDUE = "v1-capitalisation-residue"

    private val SIMULATION_SIGNING_SECRET: ByteArray = "openbank-simulation-product-replay-key".toByteArray()
    private const val SIMULATION_KEY_ID = "simulation-hmac-1"

    val interpreter: ProductInterpreter = ProductInterpreter(FinanceProductArithmetic)

    /**
     * Publishes [name] signed and loads it back through the pinned-revision loader. [verifier]
     * defaults to the publishing key; a test passes another to prove the loader refuses.
     */
    fun loadSigned(name: String, verifier: IrSignatureVerifierPort? = null): ProductRevisionIr {
        val definition = requireNotNull(ReferenceProducts.byName(name)) { "no reference product $name" }
        val compiled = definition.compile()
        val signer = HmacIrSigner(SIMULATION_SIGNING_SECRET, SIMULATION_KEY_ID)
        val pin = RevisionIdentity(
            productCode = compiled.productCode,
            revisionId = UUID.nameUUIDFromBytes("simulation:$name".toByteArray()),
            revisionNumber = 1,
            irVersion = compiled.irVersion,
            revisionHash = ProductIrCanonical.revisionHash(compiled),
        )
        val copy = SignedRevisionCopy(definition.canonicalJson(), pin, signer.sign(pin))
        val source = SignedRevisionSource { requested -> copy.takeIf { it.identity == requested } }
        return PinnedRevisionLoader(source, verifier ?: signer).load(pin)
    }

    fun run(
        case: ReferenceCase,
        portfolio: ReplayPortfolio,
        range: ReplayRange,
        book: ProductShadowBook = ProductShadowBook(),
    ): ProductReplayReport {
        val revision = loadSigned(case.name)
        book.open(portfolio)
        val residues = CapitalisationResidueTracker(book).also { it.open(portfolio) }
        val result = ProductReplay(interpreter).run(revision, portfolio, range, residues)
        val violations = ArrayList<String>()
        val findings = ArrayList<ProductDebitBelowFloor>()
        if (!result.conserved) violations += "product-money-conservation: ${result.conservation}"
        book.violations().firstOrNull()?.let { violations += "shadow-trial-balance: $it" }
        for (a in result.accounts) {
            val position = book.deposit(a.id, a.currency)
            if (position.compareTo(a.finalBalance) != 0) {
                violations += "shadow-deposit-reconciles: ${a.id} book $position replay ${a.finalBalance}"
            }
            if (a.minCustomerBalance < case.overdraftFloor) {
                violations +=
                    "no-negative-balance: ${a.id} reached ${a.minCustomerBalance} below ${case.overdraftFloor}"
            }
            if (a.minBalance < case.overdraftFloor) {
                val debiting = a.posted.filter { (k, m) ->
                    k.accountingClass == AccountingClass.CUSTOMER_DEPOSIT && m.debit.signum() > 0
                }.keys.map { it.ruleId }
                findings += ProductDebitBelowFloor(a.id, a.minBalance, debiting)
            }
        }
        val closures = result.accounts.associate { it.id to residues.closure(it.id) }
        violations += closureViolations(result, closures, book)
        val limitations = if (closures.values.any { c -> c.residues.any { it.signum() != 0 } }) {
            setOf(V1_CAPITALISATION_RESIDUE)
        } else {
            emptySet()
        }
        tierViolation(revision, portfolio, range)?.let { violations += "monotone-tier: $it" }
        val bound = evaluationBound(portfolio, range)
        if (result.evaluations > bound) violations += "bounded-termination: ${result.evaluations} > $bound"
        return ProductReplayReport(case, result, book.daysChecked, bound, violations, findings, closures, limitations)
    }

    /** `accrued-closure-reconciles` and `capitalisation-residue-bounded`, per account. */
    private fun closureViolations(
        result: ReplayResult,
        closures: Map<String, CapitalisationResidueTracker.Closure>,
        book: ProductShadowBook,
    ): List<String> = result.accounts.flatMap { a ->
        val c = closures.getValue(a.id)
        val booked = book.accruedPayable(a.id, a.currency)
        listOfNotNull(
            "accrued-closure-reconciles: ${a.id} implied ${c.implied} book $booked"
                .takeIf { c.implied.compareTo(booked) != 0 },
            c.residues.firstOrNull { it.abs() > CapitalisationResidueTracker.V1_RESIDUE_BOUND }
                ?.let { "capitalisation-residue-bounded: ${a.id} left $it" },
        )
    }

    /**
     * Upper bound on evaluations: per account one `onOpen`, two per customer posting and, per day,
     * at most one per schedule — and a revision registers at most one `onOpen` hook's worth of them.
     */
    fun evaluationBound(portfolio: ReplayPortfolio, range: ReplayRange): Long =
        portfolio.accounts.sumOf { 1L + 2L * it.postings.size + range.days * IrLimits.MAX_RULES_PER_HOOK }

    /**
     * Accrues every distinct opening balance of the portfolio (plus a cent either side of each tier
     * edge) on the range's first day and checks the accrual never falls as the balance rises.
     */
    private fun tierViolation(revision: ProductRevisionIr, portfolio: ReplayPortfolio, range: ReplayRange): String? {
        val interest = revision.interest ?: return null
        val tag = revision.hooks[Hook.SCHEDULED].orEmpty().firstOrNull { it.action == Action.AccrueInterest }?.trigger
            ?: return null
        val edges = interest.tiers.flatMap { listOfNotNull(it.from, it.to) }
            .flatMap { listOf(it - CENT, it, it + CENT) }
        val balances = (portfolio.accounts.map { it.initialState.balance } + edges)
            .filter { it.signum() >= 0 }.distinct().sorted()
        val clock = ProductClock(range.from.atStartOfDay(range.zone).toInstant(), range.zone)
        var previous = BigDecimal.ZERO
        var previousBalance = BigDecimal.ZERO
        for (b in balances) {
            val state = ProductState(currency = revision.currency, balance = b, openedOn = range.from.minusYears(1))
            val accrued = interpreter.evaluate(revision, state, ProductEvent(Hook.SCHEDULED, tag = tag), clock)
                .filterIsInstance<Instruction.Post>().filter { it.ruleId == interest.ruleId }
                .sumOf { p -> p.lines.first().amount }
            if (accrued < previous) return "balance $b accrues $accrued < $previous at $previousBalance"
            previous = accrued
            previousBalance = b
        }
        return null
    }

    private val CENT = BigDecimal("0.01")
}

/** One scenario run: the replay, how many day ends the shadow book proved, and every invariant violated. */
data class ProductReplayReport(
    val case: ProductReplayScenario.ReferenceCase,
    val result: ReplayResult,
    val daysChecked: Int,
    val evaluationBound: Long,
    val violations: List<String>,
    val findings: List<ProductDebitBelowFloor>,
    /** Per account: how the accrued-interest-payable position moved, and each capitalisation's residue. */
    val closures: Map<String, CapitalisationResidueTracker.Closure> = emptyMap(),
    /** Named IR-version limitations this run exhibited, e.g. [ProductReplayScenario.V1_CAPITALISATION_RESIDUE]. */
    val limitations: Set<String> = emptySet(),
)

/** An account the product's own debits ([rules]) took below its overdraft floor, down to [minBalance]. */
data class ProductDebitBelowFloor(val account: String, val minBalance: BigDecimal, val rules: List<String>)
