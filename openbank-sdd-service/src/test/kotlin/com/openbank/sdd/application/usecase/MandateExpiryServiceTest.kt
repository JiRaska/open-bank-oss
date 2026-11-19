// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.application.usecase

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.sdd.application.port.out.SddMandateRepository
import com.openbank.sdd.application.port.out.SddOutbox
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class MandateExpiryServiceTest {

    private val mandates = mockk<SddMandateRepository>()
    private val outbox = mockk<SddOutbox>()
    private val service = MandateExpiryService(mandates, outbox).apply {
        clock = { Instant.parse("2026-11-19T03:15:00Z") }
    }
    private val today = LocalDate.of(2026, 11, 19)

    private fun mandate(lastCollection: LocalDate) = SddMandate(
        id = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        debtorIban = "CZ6508000000192000145399",
        creditorIdentifier = "CZ12ZZZ123456789",
        umr = "UMR-1",
        scheme = SddScheme.CORE,
        sequenceType = SequenceType.RCUR,
        creditorName = "Creditor",
        debtorName = "Debtor",
        signatureDate = LocalDate.of(2020, 1, 1),
        status = MandateStatus.SUSPENDED,
        b2bConfirmed = false,
        lastCollectionDate = lastCollection,
        lastPreNotificationDate = null,
        createdAt = Instant.parse("2020-01-01T00:00:00Z"),
    )

    @Test
    fun `an idle mandate is saved EXPIRED and emits sdd mandate expired v1`() {
        val idle = mandate(LocalDate.of(2023, 11, 1))
        val message = slot<OutboxMessage>()
        every { mandates.save(any()) } answers { Uni.createFrom().item(firstArg<SddMandate>()) }
        every { outbox.append(capture(message)) } returns Uni.createFrom().voidItem()

        val expired = service.expireIfIdle(idle, today).await().indefinitely()

        assertThat(expired).isTrue()
        verify { mandates.save(match { it.id == idle.id && it.status == MandateStatus.EXPIRED }) }
        assertThat(message.captured.eventType).isEqualTo("sdd.mandate.expired.v1")
        assertThat(message.captured.aggregateId).isEqualTo(idle.id)
        assertThat(message.captured.payload)
            .contains("\"eventType\":\"sdd.mandate.expired.v1\"")
            .contains("\"status\":\"EXPIRED\"")
            .contains("\"occurredAt\":\"2026-11-19T03:15:00Z\"")
            .doesNotContain(idle.debtorIban)
    }

    @Test
    fun `a live mandate is neither saved nor announced`() {
        val live = mandate(LocalDate.of(2026, 10, 1))

        assertThat(service.expireIfIdle(live, today).await().indefinitely()).isFalse()

        verify(exactly = 0) { mandates.save(any()) }
        verify(exactly = 0) { outbox.append(any()) }
    }

    @Test
    fun `a failed outbox append surfaces so the transaction rolls back`() {
        val idle = mandate(LocalDate.of(2023, 11, 1))
        every { mandates.save(any()) } answers { Uni.createFrom().item(firstArg<SddMandate>()) }
        every { outbox.append(any()) } returns Uni.createFrom().failure(IllegalStateException("outbox down"))

        val result = runCatching { service.expireIfIdle(idle, today).await().indefinitely() }

        assertThat(result.exceptionOrNull()).hasMessage("outbox down")
    }
}
