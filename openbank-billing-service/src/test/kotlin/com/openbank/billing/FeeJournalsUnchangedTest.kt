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
 * ADR-0341 D3 differential proof for fee billing: for EVERY supported currency, every deposit
 * class and both journals (charge and reversal), the legs billing now builds -- resolved through
 * the ledger's accountingClass map -- land on exactly the GL accounts the previous release posted
 * to: the fixed `a0..0002` (2100) on the customer leg and `a0..4003` (4003) on the income leg,
 * whatever the currency. [LegacyFeeOracle] is a verbatim copy of the previous factory; never edit
 * it to make a test pass.
 *
 * The map is held here twice: as a literal ([LedgerMap.billing]) and, in the last test, parsed out of
 * the ledger's own migration so the literal cannot drift away from what the database is seeded with.
 */
class FeeJournalsUnchangedTest {

    private val currencies = listOf("CZK", "EUR", "USD", "GBP")
    private val classes = listOf("CUSTOMER_DEPOSIT_DEMAND", "CUSTOMER_DEPOSIT_SAVINGS", "CUSTOMER_DEPOSIT_TERM")

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
    fun `a charge lands on the same two accounts as before, in every currency and class`() {
        for (ccy in currencies) {
            for (cls in classes) {
                val cmd = charge(ccy, cls)
                assertThat(resolved(BillingJournalFactory.buildLines(cmd)))
                    .describedAs("charge $cls $ccy")
                    .isEqualTo(LegacyFeeOracle.charge(cmd))
            }
        }
    }

    @Test
    fun `a reversal lands on the same two accounts as before, in every currency and class`() {
        for (ccy in currencies) {
            for (cls in classes) {
                val cmd = reversal(ccy, cls)
                assertThat(resolved(BillingJournalFactory.buildReversalLines(cmd)))
                    .describedAs("reversal $cls $ccy")
                    .isEqualTo(LegacyFeeOracle.reversal(cmd))
            }
        }
    }

    @Test
    fun `an outbox row written before accounting classes lands on the old accounts in every currency`(): Unit =
        runBlocking {
            for (ccy in currencies) {
                val ledger = mockk<LedgerPostingPort>()
                val assessments = mockk<BillingAssessmentRepository>()
                val posted = slot<FeeJournalCommand>()
                coEvery { ledger.post(capture(posted)) } returns UUID.randomUUID()
                coEvery { assessments.markPosted(any(), any()) } returns Unit
                val legacyPayload = "{\"schemaVersion\":1,\"idempotencyKey\":\"fee-2026-07-acc-1-f1-$ccy\"," +
                    "\"cycleId\":\"2026-07\",\"accountId\":\"0191c0de-0000-7000-8000-000000000042\"," +
                    "\"feeId\":\"f1\",\"amount\":\"150.00\",\"currency\":\"$ccy\"," +
                    "\"description\":\"Fee charge: Maintenance\"}"
                assertThat(legacyPayload).doesNotContain("accountingClass")

                LedgerOutboxEventPublisher(
                    ledger,
                    assessments,
                    mockk(relaxed = true),
                    SimpleMeterRegistry(),
                ).publish(entry(legacyPayload))

                assertThat(posted.captured.accountingClass).isEqualTo("CUSTOMER_DEPOSIT_DEMAND")
                assertThat(resolved(BillingJournalFactory.buildLines(posted.captured)))
                    .describedAs("legacy fallback $ccy")
                    .isEqualTo(LegacyFeeOracle.charge(charge(ccy, "CUSTOMER_DEPOSIT_DEMAND")))
            }
        }

    @Test
    fun `the check is not vacuous - the per-currency control account would have moved a EUR fee`() {
        // The PRINCIPAL role names 2101 for EUR. Had billing kept it, this very comparison goes red:
        // the differential test can tell the defect from the fix.
        val wrongRole = BillingJournalFactory.buildLines(charge("EUR", "CUSTOMER_DEPOSIT_DEMAND"))
            .map { if (it.side == "DEBIT") it.copy(accountingRole = "PRINCIPAL") else it }
        assertThat(resolved(wrongRole))
            .isNotEqualTo(LegacyFeeOracle.charge(charge("EUR", "CUSTOMER_DEPOSIT_DEMAND")))
        // and a currency the map does not carry has no account at all rather than a guessed one
        val thrown = runCatching { resolved(BillingJournalFactory.buildLines(charge("PLN", classes.first()))) }
        assertThat(thrown.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the literal map equals the fee rows the ledger migration seeds`() {
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

    private val currencies = listOf("CZK", "EUR", "USD", "GBP")
    private val classes = listOf("CUSTOMER_DEPOSIT_DEMAND", "CUSTOMER_DEPOSIT_SAVINGS", "CUSTOMER_DEPOSIT_TERM")
    private val perCurrencyControl = mapOf("CZK" to "2100", "EUR" to "2101", "USD" to "2102", "GBP" to "2103")

    val billing: Map<Triple<String, String, String>, UUID> = buildMap {
        for (ccy in currencies) {
            for (cls in classes) {
                put(Triple(cls, "PRINCIPAL", ccy), idOf(perCurrencyControl.getValue(ccy)))
                put(Triple(cls, "FEE_SETTLEMENT", ccy), idOf("2100"))
            }
            put(Triple("FEE_INCOME_ACCOUNT", "FEE_INCOME", ccy), idOf("4003"))
        }
    }

    fun resolve(accountingClass: String, role: String, currency: String): UUID = requireNotNull(
        billing[Triple(accountingClass, role, currency)],
    ) { "the ledger map has no row for $accountingClass/$role/$currency" }
}

/**
 * ORACLE: a verbatim copy of the fee legs the previous release built (fixed `BillingLedgerConfig`
 * defaults `a0..0002` and `a0..4003`, for every currency).
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
