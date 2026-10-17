// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.application.port.out.LedgerPostingPort
import com.openbank.billing.domain.FeeJournalCommand
import com.openbank.billing.domain.FeeReversalCommand
import com.openbank.billing.infrastructure.client.BillingJournalFactory
import com.openbank.billing.infrastructure.client.LedgerJournalLineRequest
import com.openbank.billing.infrastructure.outbox.LedgerOutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * ADR-0341 D3 differential proof for fee billing. [LegacyFeeOracle] is a verbatim copy of the legs
 * the previous release built: the fixed `a0..0002` (2100) debit and `a0..4003` (4003) credit, whatever
 * the currency. For CZK -- the only currency those two accounts can take -- the legs billing builds now,
 * resolved through the ledger's accountingClass map, land on EXACTLY those accounts, with the same
 * sides, amounts, base amounts and sub-account, for every deposit class, for the charge and the
 * reversal, and for an outbox row written before classes existed.
 *
 * For every OTHER currency there is nothing to be identical to: the ledger refuses a line whose
 * currency is not its GL account's ([LedgerRules]), and both fixed accounts are CZK, so the previous
 * release's EUR/USD/GBP fee journals were refused at the ledger and never posted. The tests below pin
 * that fact (so nobody "restores" a behaviour that never worked) and what the new path does instead:
 * the debit leg lands on the currency's own control account and the income leg, for which the chart has
 * no account, is an explicit unmapped-row failure.
 *
 * The map is held here as a literal and, in the last test, parsed out of the ledger's own migration so
 * the literal cannot drift away from what the database is seeded with.
 */
class FeeJournalsUnchangedTest {

    private val classes = listOf("CUSTOMER_DEPOSIT_DEMAND", "CUSTOMER_DEPOSIT_SAVINGS", "CUSTOMER_DEPOSIT_TERM")
    private val nonCzk = listOf("EUR", "USD", "GBP")

    private fun charge(ccy: String, cls: String) = FeeJournalCommand(
        idempotencyKey = "fee-2026-07-acc-1-f1-$ccy",
        cycleId = "2026-07",
        accountId = "0191c0de-0000-7000-8000-000000000042",
        feeId = "f1",
        amount = BigDecimal("150.00"),
        currency = ccy,
        description = "Fee charge: Maintenance",
        accountingClass = cls,
    )

    private fun reversal(ccy: String, cls: String) = FeeReversalCommand(
        idempotencyKey = "fee-reversal-2026-07-acc-1-f1-$ccy",
        originalIdempotencyKey = "fee-2026-07-acc-1-f1-$ccy",
        cycleId = "2026-07",
        accountId = "0191c0de-0000-7000-8000-000000000042",
        feeId = "f1",
        amount = BigDecimal("150.00"),
        currency = ccy,
        reason = "wrongly charged",
        accountingClass = cls,
    )

    private fun resolved(lines: List<LedgerJournalLineRequest>) = lines.map {
        Landed(
            LedgerMap.resolve(it.accountingClass, it.accountingRole, it.currencyCode),
            it.side,
            it.amount,
            it.currencyCode,
            it.baseAmount,
            it.baseCurrencyCode,
            it.subAccountId,
        )
    }

    @Test
    fun `a CZK charge lands on the same two accounts as before, for every deposit class`() {
        for (cls in classes) {
            val cmd = charge("CZK", cls)
            assertThat(resolved(BillingJournalFactory.buildLines(cmd)))
                .describedAs("charge $cls CZK")
                .isEqualTo(LegacyFeeOracle.charge(cmd))
        }
    }

    @Test
    fun `a CZK reversal lands on the same two accounts as before, for every deposit class`() {
        for (cls in classes) {
            val cmd = reversal("CZK", cls)
            assertThat(resolved(BillingJournalFactory.buildReversalLines(cmd)))
                .describedAs("reversal $cls CZK")
                .isEqualTo(LegacyFeeOracle.reversal(cmd))
        }
    }

    @Test
    fun `an outbox row written before accounting classes lands on the old accounts in CZK`(): Unit = runBlocking {
        val ledger = mockk<LedgerPostingPort>()
        val assessments = mockk<BillingAssessmentRepository>()
        val posted = slot<FeeJournalCommand>()
        coEvery { ledger.post(capture(posted)) } returns UUID.randomUUID()
        coEvery { assessments.markPosted(any(), any()) } returns Unit
        val legacyPayload = "{\"schemaVersion\":1,\"idempotencyKey\":\"fee-2026-07-acc-1-f1-CZK\"," +
            "\"cycleId\":\"2026-07\",\"accountId\":\"0191c0de-0000-7000-8000-000000000042\"," +
            "\"feeId\":\"f1\",\"amount\":\"150.00\",\"currency\":\"CZK\"," +
            "\"description\":\"Fee charge: Maintenance\"}"
        assertThat(legacyPayload).doesNotContain("accountingClass")

        LedgerOutboxEventPublisher(ledger, assessments, mockk(relaxed = true), SimpleMeterRegistry())
            .publish(entry(legacyPayload))

        assertThat(posted.captured.accountingClass).isEqualTo("CUSTOMER_DEPOSIT_DEMAND")
        assertThat(resolved(BillingJournalFactory.buildLines(posted.captured)))
            .isEqualTo(LegacyFeeOracle.charge(charge("CZK", "CUSTOMER_DEPOSIT_DEMAND")))
    }

    @Test
    fun `the previous release never posted a non-CZK fee - the ledger refuses its fixed CZK accounts`() {
        for (ccy in nonCzk) {
            val oracle = LegacyFeeOracle.charge(charge(ccy, classes.first()))
            assertThat(oracle.map { LedgerRules.refusal(it) })
                .describedAs("previous release, $ccy fee")
                .allSatisfy { assertThat(it).isNotNull().contains("does not match GL account") }
        }
        // ...and a CZK fee is accepted by the same rule, so the rule is not simply refusing everything
        assertThat(LegacyFeeOracle.charge(charge("CZK", classes.first())).map { LedgerRules.refusal(it) })
            .containsOnly(null)
    }

    @Test
    fun `a non-CZK fee debits its own control account and its income leg is an explicit unmapped failure`() {
        val expectedControl = mapOf("EUR" to "2101", "USD" to "2102", "GBP" to "2103")
        for (ccy in nonCzk) {
            for (cls in classes) {
                val debit = BillingJournalFactory.buildLines(charge(ccy, cls)).single { it.side == "DEBIT" }
                val account = LedgerMap.resolve(debit.accountingClass, debit.accountingRole, debit.currencyCode)
                assertThat(account).describedAs("$cls $ccy").isEqualTo(LedgerMap.idOf(expectedControl.getValue(ccy)))
                // and the ledger would ACCEPT that leg: the currency is the account's
                assertThat(LedgerRules.accountCurrency(account)).isEqualTo(ccy)
            }
            val credit = BillingJournalFactory.buildLines(charge(ccy, classes.first())).single { it.side == "CREDIT" }
            val thrown = runCatching {
                LedgerMap.resolve(credit.accountingClass, credit.accountingRole, credit.currencyCode)
            }
            assertThat(thrown.exceptionOrNull())
                .describedAs("fee income in $ccy")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("FEE_INCOME_ACCOUNT/FEE_INCOME/$ccy")
        }
    }

    @Test
    fun `the check is not vacuous - a wrong class or role is caught by the CZK comparison`() {
        val cmd = charge("CZK", "CUSTOMER_DEPOSIT_DEMAND")
        val moved = BillingJournalFactory.buildLines(cmd).map { leg ->
            val isDebit = leg.side == "DEBIT"
            if (isDebit) leg.copy(accountingClass = "FEE_INCOME_ACCOUNT", accountingRole = "FEE_INCOME") else leg
        }
        // both legs now resolve to 4003: the debit has visibly moved off 2100
        assertThat(resolved(moved)).isNotEqualTo(LegacyFeeOracle.charge(cmd))
        val unknownClass = runCatching { LedgerMap.resolve("CUSTOMER_DEPOSIT_UNKNOWN", "PRINCIPAL", "CZK") }
        assertThat(unknownClass.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the literal map equals the billing rows the ledger migration seeds`() {
        val migration = listOf(
            Path.of("../openbank-ledger-service/src/main/resources/db/migration/V31__accounting_class_gl_map.sql"),
            Path.of("openbank-ledger-service/src/main/resources/db/migration/V31__accounting_class_gl_map.sql"),
        ).firstOrNull { Files.exists(it) }
        assertThat(migration).describedAs("the ledger migration must be readable from the billing build").isNotNull()
        val row = Regex("""\('([A-Z_]+)',\s*'([A-Z_]+)',\s*'([A-Z]{3})',\s*'(\d{4})'\)""")
        val seeded = Files.readAllLines(checkNotNull(migration)).flatMap { line ->
            row.findAll(line).map { m ->
                Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3]) to m.groupValues[4]
            }.toList()
        }.filter { (key, _) -> key.first.startsWith("CUSTOMER_DEPOSIT_") || key.first == "FEE_INCOME_ACCOUNT" }
            .toMap()
        assertThat(seeded).isNotEmpty()
        assertThat(seeded.mapValues { (_, code) -> LedgerMap.idOf(code) }).isEqualTo(LedgerMap.billing)
    }

    private fun entry(payload: String) = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = "billing.fee.post-intent.v1",
        payload = payload,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sentAt = null,
        lastError = null,
    )
}

/** What a leg lands on, with everything else a posting is made of. */
private data class Landed(
    val account: UUID,
    val side: String,
    val amount: BigDecimal,
    val currency: String,
    val baseAmount: BigDecimal,
    val baseCurrency: String,
    val subAccountId: UUID?,
)

/** The billing rows of the ledger map (V31), as chart ids; 2100 is the chart's `...0002`. */
private object LedgerMap {
    fun idOf(code: String): UUID = UUID.fromString(
        if (code == "2100") "a0000000-0000-0000-0000-000000000002" else "a0000000-0000-0000-0000-00000000$code",
    )

    private val classes = listOf("CUSTOMER_DEPOSIT_DEMAND", "CUSTOMER_DEPOSIT_SAVINGS", "CUSTOMER_DEPOSIT_TERM")
    private val control = mapOf("CZK" to "2100", "EUR" to "2101", "USD" to "2102", "GBP" to "2103")

    val billing: Map<Triple<String, String, String>, UUID> = buildMap {
        for ((ccy, code) in control) {
            for (cls in classes) put(Triple(cls, "PRINCIPAL", ccy), idOf(code))
        }
        put(Triple("FEE_INCOME_ACCOUNT", "FEE_INCOME", "CZK"), idOf("4003"))
    }

    fun resolve(accountingClass: String, role: String, currency: String): UUID = requireNotNull(
        billing[Triple(accountingClass, role, currency)],
    ) { "the ledger map has no row for $accountingClass/$role/$currency" }
}

/** The one ledger rule that matters here: a line's currency must be its GL account's (LedgerService). */
private object LedgerRules {
    private val currencyOf = mapOf(
        LedgerMap.idOf("2100") to "CZK",
        LedgerMap.idOf("2101") to "EUR",
        LedgerMap.idOf("2102") to "USD",
        LedgerMap.idOf("2103") to "GBP",
        LedgerMap.idOf("4003") to "CZK",
    )

    fun accountCurrency(account: UUID): String = currencyOf.getValue(account)

    fun refusal(leg: Landed): String? = if (accountCurrency(leg.account) == leg.baseCurrency) {
        null
    } else {
        "Line base currency ${leg.baseCurrency} does not match GL account currency ${accountCurrency(leg.account)}"
    }
}

/**
 * ORACLE: a verbatim copy of the fee legs the previous release built (fixed `BillingLedgerConfig`
 * defaults `a0..0002` and `a0..4003`, for every currency). Never edit it to make a test pass.
 */
private object LegacyFeeOracle {
    private val feeReceivable = UUID.fromString("a0000000-0000-0000-0000-000000000002")
    private val feeIncome = UUID.fromString("a0000000-0000-0000-0000-000000004003")

    private fun sub(accountId: String): UUID = runCatching { UUID.fromString(accountId) }
        .getOrElse { UUID.nameUUIDFromBytes(accountId.toByteArray(Charsets.UTF_8)) }

    fun charge(c: FeeJournalCommand) = listOf(
        landed(feeReceivable, "DEBIT", c.amount, c.currency, sub(c.accountId)),
        landed(feeIncome, "CREDIT", c.amount, c.currency, null),
    )

    fun reversal(c: FeeReversalCommand) = listOf(
        landed(feeReceivable, "CREDIT", c.amount, c.currency, sub(c.accountId)),
        landed(feeIncome, "DEBIT", c.amount, c.currency, null),
    )

    private fun landed(account: UUID, side: String, amount: BigDecimal, ccy: String, sub: UUID?) =
        Landed(account, side, amount, ccy, amount, ccy, sub)
}
