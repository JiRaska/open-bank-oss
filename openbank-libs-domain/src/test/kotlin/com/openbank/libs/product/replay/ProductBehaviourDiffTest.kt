// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.replay

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.openbank.libs.audit.AuditChain
import com.openbank.libs.product.ir.AccountEvent
import com.openbank.libs.product.ir.BalanceSample
import com.openbank.libs.product.ir.Cadence
import com.openbank.libs.product.ir.EventKind
import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.IrFixtures
import com.openbank.libs.product.ir.IrJson
import com.openbank.libs.product.ir.Posting
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.ir.ProductState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

class ProductBehaviourDiffTest {

    private val diff = ProductBehaviourDiff(IrFixtures.interpreter)
    private val from = LocalDate.of(2028, 1, 1)
    private val year = ReplayRange(from, LocalDate.of(2028, 12, 31), ZoneOffset.UTC)

    private fun withRate(base: String, rate: String): ProductRevisionIr {
        val tree = IrJson.parse(base).obj()

        val interest = tree["interest"].obj()
        return ProductIrParser.fromTree(tree + ("interest" to interest + ("rate" to rate)))
    }

    private fun account(
        id: String,
        balance: String,
        postings: List<ReplayPosting> = emptyList(),
        openedOn: LocalDate = from.minusYears(1),
    ) = ReplayAccount(id, ProductState(currency = "EUR", balance = BigDecimal(balance), openedOn = openedOn), postings)

    private fun deposit(on: LocalDate, amount: String) =
        ReplayPosting(on, Posting(EventKind.DEPOSIT, PostingDirection.CREDIT, BigDecimal(amount)))

    private fun withdrawal(on: LocalDate, amount: String) =
        ReplayPosting(on, Posting(EventKind.WITHDRAWAL, PostingDirection.DEBIT, BigDecimal(amount)))

    private val portfolio = ReplayPortfolio(
        listOf(
            account("a-small", "0.00", listOf(deposit(from.plusDays(40), "300.00"))),
            account(
                "a-mid",
                "3650.00",
                listOf(withdrawal(from.plusDays(100), "50.00"), withdrawal(from.plusDays(101), "999999.00")),
            ),
            account("a-large", "73000.00"),
            account("a-late", "1000.00", openedOn = LocalDate.of(2028, 2, 29)),
        ),
        seed = 7L,
    )

    @Test
    fun `identical revisions produce an empty diff`() {
        val rev = IrFixtures.savings()
        val d = diff.diff(rev, rev, portfolio, year)
        assertThat(d.identical).isTrue()
        assertThat(d.tree["accounts"] as List<*>).isEmpty()
        assertThat(d.tree["signChanged"] as List<*>).isEmpty()
        assertThat(d.tree["thresholdCrossed"] as List<*>).isEmpty()
        val aggregate = d.tree["aggregate"].obj()
        assertThat(aggregate["posted"] as List<*>).isEmpty()
        assertThat(aggregate["instructionCounts"] as List<*>).isEmpty()
        assertThat(d.a.conserved).isTrue()
    }

    @Test
    fun `the same input gives byte-identical output`() {
        val a = IrFixtures.savings()
        val b = withRate(IrFixtures.savingsJson, "0.0275")
        val first = diff.diff(a, b, portfolio, year).canonicalJson
        val second = ProductBehaviourDiff(IrFixtures.interpreter).diff(a, b, portfolio, year).canonicalJson
        assertThat(AuditChain.sha256Hex(second)).isEqualTo(AuditChain.sha256Hex(first))
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `a rate rise moves only the base accrual, by exactly the independent amount, for 30 days`() {
        val a = IrFixtures.savings()
        val b = withRate(IrFixtures.savingsJson, "0.0275")
        // 3650.00 × 0.0025 / 365 = 0.025 per day exactly, so the expectation has no rounding in it.
        val flat = ReplayPortfolio(listOf(account("x", "3650.00"), account("y", "36500.00")))
        val range = ReplayRange(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 30), ZoneOffset.UTC)
        val d = diff.diff(a, b, flat, range)

        val expected = (BigDecimal("3650.00") + BigDecimal("36500.00")) * BigDecimal("0.0025") /
            BigDecimal(365) * BigDecimal(30)

        val rows = d.tree["aggregate"].obj()["posted"].rows()
        assertThat(rows.map { it["ruleId"] to it["accountingClass"] }).containsExactly(
            "interest.base" to "ACCRUED_INTEREST_PAYABLE",
            "interest.base" to "INTEREST_EXPENSE",
        )
        assertThat(BigDecimal(rows[0]["deltaNet"] as String)).isEqualByComparingTo(expected)
        assertThat(BigDecimal(rows[1]["deltaNet"] as String)).isEqualByComparingTo(expected.negate())
        assertThat(d.identical).isFalse()
        assertThat(d.tree["changedAccounts"]).isEqualTo(2)
    }

    @Test
    fun `sign changes and materiality crossings are listed per account`() {
        val a = IrFixtures.savings()
        val b = withRate(IrFixtures.savingsJson, "0.2000")
        val d = diff.diff(a, b, portfolio, year, DiffOptions(BigDecimal("100.00")))
        assertThat(d.tree["thresholdCrossed"] as List<*>).contains("a-large")
        assertThat(d.tree["thresholdCrossed"] as List<*>).doesNotContain("a-small")
        // a-late (1000.00, opened on the leap day) pays more in fees than it earns at 2.5 %; at 20 % its
        // interest overtakes the fees, so its product outcome changes sign.
        val expected = d.a.accounts.zip(d.b.accounts)
            .filter { (x, y) -> x.outcome.signum() != y.outcome.signum() }.map { it.first.id }
        assertThat(d.a.accounts.first { it.id == "a-late" }.outcome.signum()).isNegative()
        assertThat(d.tree["signChanged"] as List<*>).isEqualTo(expected).contains("a-late")
    }

    @Test
    fun `the output validates against its trusted-profile JSON Schema`() {
        val mapper = ObjectMapper()
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(checkNotNull(javaClass.getResourceAsStream("/product-ir/behaviour-diff.v1.schema.json")))
        val outputs = listOf(
            diff.diff(IrFixtures.savings(), withRate(IrFixtures.savingsJson, "0.0300"), portfolio, year),
            diff.diff(IrFixtures.savings(), IrFixtures.savings(), ReplayPortfolio(emptyList()), year),
            diff.diff(IrFixtures.termDeposit(), IrFixtures.termDeposit(), portfolio, year),
        )
        for (o in outputs) assertThat(schema.validate(mapper.readTree(o.canonicalJson))).isEmpty()
        val text = javaClass.getResource("/product-ir/behaviour-diff.v1.schema.json")!!.readText()
        val objects = mapper.readTree(text).findParents("type")
            .filter { it.get("type")?.asText() == "object" && it.has("properties") }
        assertThat(objects).isNotEmpty.allMatch { it.get("additionalProperties")?.asBoolean() == false }
        assertThat(text).doesNotContain("\"\$ref\": \"http").doesNotContain("\"default\"")
    }

    @Test
    fun `replay rejects an overdrawing posting, counts it and leaves the balance alone`() {
        val r = ProductReplay(IrFixtures.interpreter).run(IrFixtures.savings(), portfolio, year)
        val mid = r.accounts.first { it.id == "a-mid" }
        assertThat(mid.rejectedPostings).isEqualTo(1)
        assertThat(mid.acceptedPostings).isEqualTo(1)
        assertThat(mid.counts[CountKey("posting.no-overdraft", "REJECT")]).isEqualTo(1)
        assertThat(r.conserved).isTrue()
        assertThat(r.evaluations).isPositive()
    }

    @Test
    fun `an account opened inside the range is silent until its opening day, a leap day`() {
        val seen = ArrayList<LocalDate>()
        val listener = object : ReplayListener {
            override fun onInstructions(
                accountId: String,
                on: LocalDate,
                currency: String,
                instructions: List<Instruction>,
            ) {
                if (accountId == "a-late") seen += on
            }
        }
        ProductReplay(IrFixtures.interpreter).run(IrFixtures.savings(), portfolio, year, listener)
        assertThat(seen.first()).isEqualTo(LocalDate.of(2028, 2, 29))
    }

    @Test
    fun `the listener sees customer postings and every day end`() {
        var days = 0
        val postings = ArrayList<String>()
        val listener = object : ReplayListener {
            override fun onCustomerPosting(accountId: String, on: LocalDate, currency: String, posting: Posting) {
                postings += "$accountId@$on"
            }

            override fun onDayEnd(on: LocalDate) {
                days++
            }
        }
        ProductReplay(IrFixtures.interpreter).run(IrFixtures.savings(), portfolio, year, listener)
        assertThat(days).isEqualTo(366)
        assertThat(postings).containsExactly("a-small@2028-02-10", "a-mid@2028-04-10")
    }

    @Test
    fun `a term deposit matures once and its early withdrawal is penalised`() {
        val opened = LocalDate.of(2028, 3, 1)
        val td = ReplayPortfolio(
            listOf(account("t", "20000.00", listOf(withdrawal(opened.plusMonths(2), "100.00")), openedOn = opened)),
        )
        val r = ProductReplay(
            IrFixtures.interpreter,
        ).run(IrFixtures.termDeposit(), td, ReplayRange(opened, opened.plusMonths(14), ZoneOffset.UTC))
        val t = r.accounts.single()
        assertThat(t.counts.filterKeys { it.kind == "POST" && it.ruleId.contains("mature") }.values.sum()).isEqualTo(1)
        assertThat(t.posted.keys.map { it.accountingClass.name }).contains("PENALTY_INCOME")
        assertThat(r.conserved).isTrue()
    }

    @Test
    fun `quarterly and annual schedules fire at period ends and on-event never`() {
        val tree = IrJson.parse(IrFixtures.savingsJson).obj()

        val hooks = tree["hooks"].obj()
        fun sched(id: String, tag: String, cadence: Cadence) = mapOf(
            "ruleId" to id,
            "action" to mapOf("type" to "SCHEDULE", "hook" to "scheduled", "tag" to tag, "cadence" to cadence.name),
        )
        fun note(id: String, tag: String) = mapOf(
            "ruleId" to id,
            "trigger" to tag,
            "action" to
                mapOf(
                    "type" to "NOTIFY",
                    "templateId" to tag.lowercase(),
                    "variables" to emptyMap<String, String>(),
                ),
        )
        val rev = ProductIrParser.fromTree(
            tree + (
                "hooks" to hooks + mapOf(
                    "onOpen" to
                        listOf(
                            sched("open.q", "Q", Cadence.QUARTERLY),
                            sched("open.y", "Y", Cadence.ANNUALLY),
                            sched("open.e", "E", Cadence.ON_EVENT),
                        ),
                    "scheduled" to listOf(note("scheduled.q", "Q"), note("scheduled.y", "Y"), note("scheduled.e", "E")),
                )
                ),
        )
        val r = ProductReplay(IrFixtures.interpreter).run(rev, ReplayPortfolio(listOf(account("q", "10.00"))), year)
        val c = r.accounts.single().counts
        assertThat(c[CountKey("scheduled.q", "NOTIFY")]).isEqualTo(4)
        assertThat(c[CountKey("scheduled.y", "NOTIFY")]).isEqualTo(1)
        assertThat(c[CountKey("scheduled.e", "NOTIFY")]).isNull()
    }

    @Test
    fun `history older than the longest predicate window is dropped and does not change behaviour`() {
        val rev = IrFixtures.savings()
        assertThat(ProductReplay.historyWindow(rev)).isEqualTo(31)
        val old = account("h", "5000.00").let {
            it.copy(
                initialState = it.initialState.copy(
                    events = listOf(AccountEvent(EventKind.WITHDRAWAL, from.minusDays(400), BigDecimal.TEN)),
                    balanceSamples = listOf(
                        BalanceSample(from.minusDays(400), BigDecimal("5000.00")),
                        BalanceSample(from.minusDays(1), BigDecimal("5000.00")),
                    ),
                ),
            )
        }
        val replay = ProductReplay(IrFixtures.interpreter)
        val pruned = replay.run(rev, ReplayPortfolio(listOf(old)), year)
        val kept = replay.run(rev, ReplayPortfolio(listOf(old)), year, historyDays = ReplayLimits.MAX_DAYS.toInt())
        assertThat(pruned.accounts.single().posted).isEqualTo(kept.accounts.single().posted)
    }

    @Test
    fun `inputs are validated`() {
        assertThatThrownBy {
            ReplayRange(from, from.minusDays(1), ZoneOffset.UTC)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ReplayRange(from, from.plusDays(ReplayLimits.MAX_DAYS), ZoneOffset.UTC) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ReplayPortfolio(listOf(account("d", "1.00"), account("d", "2.00"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { account(" ", "1.00") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { account("o", "1.00", listOf(deposit(from.plusDays(2), "1.00"), deposit(from, "1.00"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DiffOptions(BigDecimal("-1")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(Movement(BigDecimal.ONE, BigDecimal.TEN).net).isEqualByComparingTo("9")
        assertThat(ReplayListener.NONE.onDayEnd(from)).isEqualTo(Unit)
    }
}

/** Typed views of a parsed JSON tree, checked per key instead of an unchecked cast. */
private fun Any?.obj(): Map<String, Any?> = (this as Map<*, *>).entries.associate { (k, v) -> k as String to v }

private fun Any?.rows(): List<Map<String, Any?>> = (this as List<*>).map { it.obj() }
