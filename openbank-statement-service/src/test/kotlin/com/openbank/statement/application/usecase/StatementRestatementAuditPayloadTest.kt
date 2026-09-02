// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.statement.Fixtures
import com.openbank.statement.application.port.out.AccountInfoPort
import com.openbank.statement.application.port.out.BalancePort
import com.openbank.statement.application.port.out.BookedEntryPort
import com.openbank.statement.application.port.out.PocketAccountInfo
import com.openbank.statement.application.port.out.StatementPeriodRepository
import com.openbank.statement.domain.model.BalanceAnchor
import com.openbank.statement.domain.model.PeriodCloseStatus
import com.openbank.statement.domain.model.StatementPeriod
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Issue #3994/#5256: `account.statement.period.restated.v1` must carry `sourceService` and
 * `occurredAt` on the wire.
 *
 * Its two sibling event types (`period.closed.v1` in [StatementService], `period.close_failed.v1`
 * in `CloseOrchestrator`) were both patched by the fleet sweep; this third type was missed because
 * it lives in its own service class. Without `sourceService`, `AuditConsumer` attributes the row
 * from its topic table rather than the producer's own claim; without `occurredAt` it stamps the row
 * with the CONSUMER's ingest time and calls that the business time.
 *
 * The assertion reads the payload the PRODUCTION builder emits, parsed as JSON — not a substring
 * match on the template, and not a field on a data class (there is no data class here: the payload
 * is a hand-built string, which is precisely the shape a grep for the quoted key finds and a reader
 * of the event classes does not).
 */
class StatementRestatementAuditPayloadTest {

    private val accountId: UUID = Fixtures.ACCOUNT_ID
    private val currency = "CZK"
    private val from: LocalDate = LocalDate.parse("2026-01-01")
    private val to: LocalDate = LocalDate.parse("2026-01-31")
    private val closedAt: Instant = Instant.parse("2026-02-01T02:30:00Z")

    private val accountInfo: AccountInfoPort = mockk()
    private val bookedEntries: BookedEntryPort = mockk()
    private val balance: BalancePort = mockk()
    private val periods: StatementPeriodRepository = mockk()

    private fun standing() = StatementPeriod(
        id = UUID.randomUUID(),
        accountId = accountId,
        pocketCurrency = currency,
        periodFrom = from,
        periodTo = to,
        legalSequenceNumber = 1,
        electronicSequenceNumber = 1,
        openingBalance = BigDecimal("1000.00"),
        // Deliberately different from what the recomputation below yields, so the service takes the
        // supersede branch rather than its "nothing to correct" no-op.
        closingBalance = BigDecimal("1200.00"),
        entryCount = 2,
        closedAt = closedAt,
        status = PeriodCloseStatus.CLOSED,
    )

    @Test
    fun `the restated event carries sourceService and occurredAt on the wire`() {
        val entries = listOf(Fixtures.entry(ref = "TX-1", amount = "100.00"))
        val emitted = slot<OutboxMessage>()

        every { accountInfo.pocketAccount(accountId) } returns Uni.createFrom().item(
            PocketAccountInfo(accountId, "CZ6508000000192000145399", "Jan Novak", listOf(currency)),
        )
        every { periods.findByPeriod(accountId, currency, from, to) } returns Uni.createFrom().item(standing())
        every { bookedEntries.bookedEntries(accountId, currency, from, to) } returns Uni.createFrom().item(entries)
        every { periods.priorClosing(accountId, currency, from) } returns Uni.createFrom().item(BigDecimal("1000.00"))
        every { balance.closingBalance(accountId, currency, to) } returns Uni.createFrom().item(
            BalanceAnchor(BigDecimal("1100.00"), currency, to),
        )
        every { periods.nextLegalSequence(accountId, currency) } returns Uni.createFrom().item(2L)
        every { periods.supersedeAndReplace(any(), any(), capture(emitted)) } answers {
            Uni.createFrom().item(secondArg<StatementPeriod>())
        }

        val service = StatementRestatementService(accountInfo, bookedEntries, balance, periods)
        service.clock = { closedAt }
        service.restatePocketPeriod(accountId, currency, from, to).await().indefinitely()

        val payload = ObjectMapper().readTree(emitted.captured.payload)
        assertThat(payload.get("eventType")?.asText()).isEqualTo("account.statement.period.restated.v1")
        assertThat(payload.get("sourceService")?.asText()).isEqualTo("statement-service")
        // An EXACT instant against the stubbed clock seam, never isNotNull(): a non-null assertion
        // passes against Instant.EPOCH, and asText() yields the four-character text "null" for a
        // JSON null.
        assertThat(Instant.parse(payload.get("occurredAt").asText())).isEqualTo(closedAt)
    }
}
