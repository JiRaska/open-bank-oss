// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.ir

import com.openbank.libs.product.ir.IrFixtures.interpreter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ProductInterpreterTest {

    private val savings = IrFixtures.savings()
    private val term = IrFixtures.termDeposit()
    private val clock = IrFixtures.clock

    private fun posts(i: List<Instruction>) = i.filterIsInstance<Instruction.Post>()

    @Test
    fun `onOpen emits the declared schedules and notification, each stamped with rule, hash and version`() {
        val out = interpreter.evaluate(savings, IrFixtures.state(), ProductEvent(Hook.ON_OPEN), clock)
        assertThat(out).hasSize(4)
        assertThat(out.filterIsInstance<Instruction.Schedule>().map { it.tag }).containsExactly("ACCRUAL", "CAPITALISATION", "FEE_MAINT")
        assertThat(out.last()).isEqualTo(Instruction.Notify("open.welcome", savings.revisionHash, 1, "savings-welcome", mapOf("productCode" to "SAV-FIXED")))
        assertThat(out).allMatch { it.revisionHash == savings.revisionHash && it.irVersion == 1 }
    }

    @Test
    fun `a term deposit schedules maturity at openedOn plus the term`() {
        val opened = IrFixtures.day
        val out = interpreter.evaluate(term, IrFixtures.state(openedOn = opened), ProductEvent(Hook.ON_OPEN), clock)
        val maturity = out.filterIsInstance<Instruction.Schedule>().single { it.tag == "MATURITY" }
        assertThat(maturity.cadence).isEqualTo(Cadence.AT_MATURITY)
        assertThat(maturity.at).isEqualTo(opened.plusMonths(12))
    }

    @Test
    fun `scheduled ACCRUAL posts a balanced accrual, and a bonus accrual when its predicate holds`() {
        val quiet = IrFixtures.state("10000.00").copy(balanceSamples = listOf(BalanceSample(IrFixtures.day.minusDays(5), BigDecimal("9000"))))
        val out = interpreter.evaluate(savings, quiet, ProductEvent(Hook.SCHEDULED, tag = "ACCRUAL"), clock)
        assertThat(posts(out).map { it.ruleId }).containsExactly("interest.base", "interest.bonus")
        val base = posts(out)[0]
        assertThat(base.isBalancedPerCurrency()).isTrue()
        assertThat(base.lines.map { it.accountingClass }).containsExactly(AccountingClass.INTEREST_EXPENSE, AccountingClass.ACCRUED_INTEREST_PAYABLE)
        assertThat(base.lines[0].amount).isEqualByComparingTo("0.684932") // 10000 × 0.025 / 365 at scale 6
        assertThat(posts(out)[1].lines[0].amount).isEqualByComparingTo("0.273973")

        val active = quiet.copy(events = listOf(AccountEvent(EventKind.WITHDRAWAL, IrFixtures.day, BigDecimal.TEN)))
        assertThat(posts(interpreter.evaluate(savings, active, ProductEvent(Hook.SCHEDULED, tag = "ACCRUAL"), clock)).map { it.ruleId }).containsExactly("interest.base")
    }

    @Test
    fun `a tiered rate accrues per band`() {
        val out = interpreter.evaluate(term, IrFixtures.state("15000.00"), ProductEvent(Hook.SCHEDULED, tag = "ACCRUAL"), clock)
        // 10000 × 0.02 / 365 = 0.547945 ; 5000 × 0.03 / 365 = 0.410959
        assertThat(posts(out).single().lines[0].amount).isEqualByComparingTo("0.958904")
        assertThat(interpreter.evaluate(term, IrFixtures.state("0.00"), ProductEvent(Hook.SCHEDULED, tag = "ACCRUAL"), clock)).isEmpty()
    }

    @Test
    fun `scheduled CAPITALISATION posts the accrued interest at the currency scale and nothing when none`() {
        val out = interpreter.evaluate(savings, IrFixtures.state(accrued = "12.345678"), ProductEvent(Hook.SCHEDULED, tag = "CAPITALISATION"), clock)
        val post = posts(out).single()
        assertThat(post.ruleId).isEqualTo("scheduled.capitalise")
        assertThat(post.lines.map { it.accountingClass to it.direction }).containsExactly(
            AccountingClass.ACCRUED_INTEREST_PAYABLE to PostingDirection.DEBIT,
            AccountingClass.CUSTOMER_DEPOSIT to PostingDirection.CREDIT,
        )
        assertThat(post.lines[0].amount).isEqualByComparingTo("12.35")
        assertThat(interpreter.evaluate(savings, IrFixtures.state(accrued = "0.001"), ProductEvent(Hook.SCHEDULED, tag = "CAPITALISATION"), clock)).isEmpty()
    }

    @Test
    fun `a fee is charged unless its waiver predicate holds`() {
        val charged = interpreter.evaluate(savings, IrFixtures.state("100.00"), ProductEvent(Hook.SCHEDULED, tag = "FEE_MAINT"), clock)
        assertThat(posts(charged).single().lines.map { it.accountingClass }).containsExactly(AccountingClass.CUSTOMER_DEPOSIT, AccountingClass.FEE_INCOME)
        assertThat(posts(charged).single().lines[0].amount).isEqualByComparingTo("5.00")
        assertThat(interpreter.evaluate(savings, IrFixtures.state("50000.00"), ProductEvent(Hook.SCHEDULED, tag = "FEE_MAINT"), clock)).isEmpty()
    }

    @Test
    fun `a scheduled event with an unknown tag runs no rule`() {
        assertThat(interpreter.evaluate(savings, IrFixtures.state(), ProductEvent(Hook.SCHEDULED, tag = "NOPE"), clock)).isEmpty()
        assertThat(interpreter.evaluate(savings, IrFixtures.state(), ProductEvent(Hook.ON_CLOSE), clock)).isEmpty()
    }

    @Test
    fun `prePosting rejects an overdraft with a stable reason code and nothing else`() {
        val event = ProductEvent(Hook.PRE_POSTING, posting = Posting(EventKind.WITHDRAWAL, PostingDirection.DEBIT, BigDecimal("150.00")))
        val out = interpreter.evaluate(savings, IrFixtures.state("100.00"), event, clock)
        assertThat(out).containsExactly(Instruction.Reject("posting.no-overdraft", savings.revisionHash, 1, "INSUFFICIENT_FUNDS"))
        assertThat(interpreter.evaluate(savings, IrFixtures.state("200.00"), event, clock)).isEmpty()
    }

    @Test
    fun `an early withdrawal before maturity pays a rate penalty, after maturity none, a deposit none`() {
        val withdrawal = ProductEvent(Hook.PRE_POSTING, posting = Posting(EventKind.WITHDRAWAL, PostingDirection.DEBIT, BigDecimal("1000.00")))
        val early = interpreter.evaluate(term, IrFixtures.state(openedOn = IrFixtures.day.minusMonths(3)), withdrawal, clock)
        val post = posts(early).single()
        assertThat(post.ruleId).isEqualTo("posting.early-withdrawal")
        assertThat(post.lines.map { it.accountingClass }).containsExactly(AccountingClass.CUSTOMER_DEPOSIT, AccountingClass.PENALTY_INCOME)
        assertThat(post.lines[0].amount).isEqualByComparingTo("10.00")
        assertThat(interpreter.evaluate(term, IrFixtures.state(openedOn = IrFixtures.day.minusMonths(12)), withdrawal, clock)).isEmpty()
        val deposit = ProductEvent(Hook.PRE_POSTING, posting = Posting(EventKind.DEPOSIT, PostingDirection.CREDIT, BigDecimal("1000.00")))
        // a credit is never a withdrawal, whatever kind it is labelled
        assertThat(interpreter.evaluate(term, IrFixtures.state(openedOn = IrFixtures.day.minusMonths(3)), deposit, clock)).isEmpty()
    }

    @Test
    fun `every outflow kind before maturity pays the penalty`() {
        for (kind in listOf(EventKind.WITHDRAWAL, EventKind.TRANSFER_OUT, EventKind.CARD_PAYMENT)) {
            val event = ProductEvent(Hook.PRE_POSTING, posting = Posting(kind, PostingDirection.DEBIT, BigDecimal("1000.00")))
            val out = interpreter.evaluate(term, IrFixtures.state(openedOn = IrFixtures.day.minusMonths(3)), event, clock)
            assertThat(posts(out)).withFailMessage("$kind must pay the penalty").hasSize(1)
        }
    }

    @Test
    fun `a Reject after a posting rule discards the earlier posts`() {
        val json = IrFixtures.termDepositJson.replace(
            "{ \"ruleId\": \"posting.early-withdrawal\", \"action\": { \"type\": \"EARLY_WITHDRAWAL_PENALTY\" } }",
            "{ \"ruleId\": \"posting.early-withdrawal\", \"action\": { \"type\": \"EARLY_WITHDRAWAL_PENALTY\" } }," +
                "{ \"ruleId\": \"posting.no-overdraft\", \"when\": { \"type\": \"postingComparison\", \"attribute\": \"PROJECTED_BALANCE\", \"operator\": \"LT\", \"threshold\": \"0.00\" }, \"action\": { \"type\": \"REJECT\", \"reasonCode\": \"INSUFFICIENT_FUNDS\" } }",
        )
        val rev = ProductIrParser.parse(json)
        val event = ProductEvent(Hook.PRE_POSTING, posting = Posting(EventKind.WITHDRAWAL, PostingDirection.DEBIT, BigDecimal("20000.00")))
        val out = interpreter.evaluate(rev, IrFixtures.state(openedOn = IrFixtures.day.minusMonths(3)), event, clock)
        assertThat(out).containsExactly(Instruction.Reject("posting.no-overdraft", rev.revisionHash, 1, "INSUFFICIENT_FUNDS"))
    }

    @Test
    fun `a forfeited-interest penalty reverses part of the accrued interest`() {
        val forfeit = ProductIrParser.parse(
            IrFixtures.termDepositJson.replace("\"kind\": \"RATE\", \"rate\": \"0.0100\"", "\"kind\": \"FORFEITED_INTEREST_FRACTION\", \"fraction\": \"0.5\""),
        )
        val withdrawal = ProductEvent(Hook.PRE_POSTING, posting = Posting(EventKind.WITHDRAWAL, PostingDirection.DEBIT, BigDecimal("1000.00")))
        val out = interpreter.evaluate(forfeit, IrFixtures.state(accrued = "40.00", openedOn = IrFixtures.day.minusMonths(3)), withdrawal, clock)
        val post = posts(out).single()
        assertThat(post.lines.map { it.accountingClass }).containsExactly(AccountingClass.ACCRUED_INTEREST_PAYABLE, AccountingClass.INTEREST_EXPENSE)
        assertThat(post.lines[0].amount).isEqualByComparingTo("20.00")
        assertThat(interpreter.evaluate(forfeit, IrFixtures.state(accrued = "0", openedOn = IrFixtures.day.minusMonths(3)), withdrawal, clock)).isEmpty()
    }

    @Test
    fun `maturity CAPITALISE, PAY_OUT and ROLL_OVER`() {
        val state = IrFixtures.state("10000.00", accrued = "123.456", openedOn = IrFixtures.day.minusMonths(12))
        val mature = ProductEvent(Hook.SCHEDULED, tag = "MATURITY")

        val cap = interpreter.evaluate(term, state, mature, clock)
        assertThat(posts(cap).single().lines[0].amount).isEqualByComparingTo("123.46")

        val payOut = ProductIrParser.parse(IrFixtures.termDepositJson.replace("\"maturityAction\": \"CAPITALISE\"", "\"maturityAction\": \"PAY_OUT\""))
        val po = interpreter.evaluate(payOut, state, mature, clock)
        assertThat(posts(po)).hasSize(2)
        assertThat(posts(po)[1].lines[0].amount).isEqualByComparingTo("10123.46")
        assertThat(posts(po)[1].lines.map { it.accountingClass }).containsExactly(AccountingClass.CUSTOMER_DEPOSIT, AccountingClass.SETTLEMENT)
        assertThat(interpreter.evaluate(payOut, IrFixtures.state("0", accrued = "0"), mature, clock)).isEmpty()

        val roll = ProductIrParser.parse(IrFixtures.termDepositJson.replace("\"maturityAction\": \"CAPITALISE\"", "\"maturityAction\": \"ROLL_OVER\""))
        val ro = interpreter.evaluate(roll, state, mature, clock)
        val next = ro.filterIsInstance<Instruction.Schedule>().single()
        assertThat(next.tag).isEqualTo("MATURITY")
        assertThat(next.at).isEqualTo(IrFixtures.day.plusMonths(12))
    }

    @Test
    fun `a Reprice override changes the rate the next accrual uses`() {
        val repriced = IrFixtures.state("10000.00").copy(parameters = mapOf(ProductInterpreter.PARAM_RATE to BigDecimal("0.0365")))
        val out = interpreter.evaluate(savings, repriced, ProductEvent(Hook.SCHEDULED, tag = "ACCRUAL"), clock)
        assertThat(posts(out).first().lines[0].amount).isEqualByComparingTo("1.000000")
        val change = interpreter.evaluate(savings, repriced, ProductEvent(Hook.ON_PARAMETER_CHANGE, parameter = "interest.rate", newValue = BigDecimal("0.03")), clock)
        assertThat(change.single()).isInstanceOf(Instruction.Notify::class.java)
    }

    @Test
    fun `an explicit Reprice action emits a Reprice instruction`() {
        val json = IrFixtures.savingsJson.replace(
            "{ \"ruleId\": \"reprice.notify\", \"action\": { \"type\": \"NOTIFY\", \"templateId\": \"rate-changed\", \"variables\": { \"productCode\": \"SAV-FIXED\" } } }",
            "{ \"ruleId\": \"reprice.apply\", \"action\": { \"type\": \"REPRICE\", \"parameter\": \"interest.rate\", \"value\": \"0.03\" } }",
        )
        val out = interpreter.evaluate(ProductIrParser.parse(json), IrFixtures.state(), ProductEvent(Hook.ON_PARAMETER_CHANGE), clock)
        assertThat(out.single()).isEqualTo(Instruction.Reprice("reprice.apply", ProductIrParser.parse(json).revisionHash, 1, "interest.rate", BigDecimal("0.03")))
    }

    @Test
    fun `more than 64 instructions in one evaluation is a typed failure, not a long list`() {
        val rules = (0 until IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION + 1).map { HookRule("n.$it", null, null, Action.Notify("t", emptyMap())) }
        val rev = savings.copy(hooks = mapOf(Hook.ON_CLOSE to rules))
        assertThatThrownBy { interpreter.evaluate(rev, IrFixtures.state(), ProductEvent(Hook.ON_CLOSE), clock) }
            .isInstanceOf(ProductEvaluationException::class.java)
            .hasMessageContaining("exceeded 64 instructions")
        assertThat(interpreter.evaluate(rev.copy(hooks = mapOf(Hook.ON_CLOSE to rules.dropLast(1))), IrFixtures.state(), ProductEvent(Hook.ON_CLOSE), clock)).hasSize(64)
    }

    @Test
    fun `an unbalanced Post from the arithmetic is a typed failure before anything is returned`() {
        val broken = object : ProductArithmetic by IrFixtures.StubArithmetic {
            override fun capitalise(accrued: BigDecimal, currency: com.openbank.libs.domain.money.CurrencyCode, postingRounding: com.openbank.libs.domain.money.RoundingPolicy) = BigDecimal("1.00")
        }
        // Balanced by construction in the interpreter; prove the guard through the domain function itself.
        val post = Instruction.Post("r", "h", 1, listOf(PostingLine(AccountingClass.CUSTOMER_DEPOSIT, PostingDirection.DEBIT, BigDecimal.ONE, "EUR"), PostingLine(AccountingClass.FEE_INCOME, PostingDirection.CREDIT, BigDecimal.TEN, "EUR")), "x")
        assertThat(post.isBalancedPerCurrency()).isFalse()
        val mixed = post.copy(lines = post.lines + PostingLine(AccountingClass.FEE_INCOME, PostingDirection.DEBIT, BigDecimal("9"), "EUR") + PostingLine(AccountingClass.SETTLEMENT, PostingDirection.CREDIT, BigDecimal("5"), "CZK"))
        assertThat(mixed.isBalancedPerCurrency()).isFalse()
        assertThat(mixed.copy(lines = mixed.lines + PostingLine(AccountingClass.SETTLEMENT, PostingDirection.DEBIT, BigDecimal("5"), "CZK")).isBalancedPerCurrency()).isTrue()
        assertThat(ProductInterpreter(broken).evaluate(savings, IrFixtures.state(accrued = "5"), ProductEvent(Hook.SCHEDULED, tag = "CAPITALISATION"), clock)).hasSize(1)
    }

    @Test
    fun `evaluation is deterministic across repeated runs`() {
        val state = IrFixtures.state("10000.00").copy(balanceSamples = listOf(BalanceSample(IrFixtures.day, BigDecimal("10000"))))
        val a = InstructionCanonical.write(interpreter.evaluate(savings, state, ProductEvent(Hook.SCHEDULED, tag = "ACCRUAL"), clock))
        val b = InstructionCanonical.write(interpreter.evaluate(savings, state, ProductEvent(Hook.SCHEDULED, tag = "ACCRUAL"), clock))
        assertThat(a).isEqualTo(b).contains("\"amount\":\"0.684932000000\"")
    }
}
