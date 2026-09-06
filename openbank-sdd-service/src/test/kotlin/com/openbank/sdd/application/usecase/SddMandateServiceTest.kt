// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.application.usecase

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.sdd.Fixtures
import com.openbank.sdd.application.port.`in`.RegisterMandateCommand
import com.openbank.sdd.application.port.out.SddMandateRepository
import com.openbank.sdd.application.port.out.SddOutbox
import com.openbank.sdd.domain.authorise.AuthorisationResult
import com.openbank.sdd.domain.authorise.CollectionInstruction
import com.openbank.sdd.domain.authorise.DebtorControls
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import com.openbank.sdd.domain.refund.RefundDecision
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class SddMandateServiceTest {

    private val mandates = mockk<SddMandateRepository>()
    private val outbox = mockk<SddOutbox>(relaxed = false)
    private val service = SddMandateService(mandates, outbox)

    private fun registerCmd(scheme: SddScheme = SddScheme.CORE) = RegisterMandateCommand(
        accountId = Fixtures.ACCOUNT_ID,
        debtorIban = "CZ6508000000192000145399",
        creditorIdentifier = "DE98ZZZ09999999999",
        umr = "UMR-0001",
        scheme = scheme,
        sequenceType = SequenceType.FRST,
        creditorName = "Energie a.s.",
        debtorName = "Jan Novak",
        signatureDate = LocalDate.parse("2026-01-01"),
    )

    private fun stubSaveAndOutbox() {
        every { mandates.save(any()) } answers { Uni.createFrom().item(firstArg<SddMandate>()) }
        every { outbox.append(any()) } returns Uni.createFrom().voidItem()
    }

    @Test
    fun `registering a new Core mandate persists it ACTIVE and emits an event`() {
        every { mandates.findByReference(any(), any()) } returns Uni.createFrom().nullItem()
        stubSaveAndOutbox()

        val saved = service.register(registerCmd(SddScheme.CORE)).await().indefinitely()

        assertThat(saved.status).isEqualTo(MandateStatus.ACTIVE)
        verify(exactly = 1) { mandates.save(any()) }
        verify(exactly = 1) { outbox.append(any()) }
    }

    @Test
    fun `registering a new B2B mandate starts PENDING_CONFIRMATION`() {
        every { mandates.findByReference(any(), any()) } returns Uni.createFrom().nullItem()
        stubSaveAndOutbox()

        val saved = service.register(registerCmd(SddScheme.B2B)).await().indefinitely()

        assertThat(saved.status).isEqualTo(MandateStatus.PENDING_CONFIRMATION)
        assertThat(saved.b2bConfirmed).isFalse()
    }

    @Test
    fun `registering an existing reference is idempotent and does not re-save`() {
        val existing = Fixtures.mandate()
        every { mandates.findByReference(any(), any()) } returns Uni.createFrom().item(existing)

        val result = service.register(registerCmd()).await().indefinitely()

        assertThat(result.id).isEqualTo(existing.id)
        verify(exactly = 0) { mandates.save(any()) }
    }

    @Test
    fun `confirming a pending B2B mandate activates it`() {
        val pending = Fixtures.mandate(scheme = SddScheme.B2B, status = MandateStatus.PENDING_CONFIRMATION, b2bConfirmed = false)
        every { mandates.findById(pending.id) } returns Uni.createFrom().item(pending)
        stubSaveAndOutbox()

        val confirmed = service.confirm(pending.id).await().indefinitely()

        assertThat(confirmed.status).isEqualTo(MandateStatus.ACTIVE)
        assertThat(confirmed.b2bConfirmed).isTrue()
    }

    @Test
    fun `operating on an unknown mandate id fails with MandateNotFound`() {
        every { mandates.findById(any()) } returns Uni.createFrom().nullItem()
        assertThatThrownBy { service.confirm(UUID.randomUUID()).await().indefinitely() }
            .isInstanceOf(MandateNotFoundException::class.java)
    }

    @Test
    fun `authorising a valid collection accepts, stamps the collection and emits the authorised event`() {
        val mandate = Fixtures.mandate(sequenceType = SequenceType.FRST)
        every { mandates.findByReference(any(), any()) } returns Uni.createFrom().item(mandate)
        val savedSlot = slot<SddMandate>()
        every { mandates.save(capture(savedSlot)) } answers { Uni.createFrom().item(firstArg<SddMandate>()) }
        val msgSlot = slot<OutboxMessage>()
        every { outbox.append(capture(msgSlot)) } returns Uni.createFrom().voidItem()

        val result = service.authorise(
            CollectionInstruction("DE98ZZZ09999999999", "UMR-0001", SddScheme.CORE, SequenceType.FRST, BigDecimal("42.00"), "EUR", LocalDate.parse("2026-03-01")),
            DebtorControls(),
        ).await().indefinitely()

        assertThat(result).isInstanceOf(AuthorisationResult.Accept::class.java)
        assertThat(savedSlot.captured.sequenceType).isEqualTo(SequenceType.RCUR)
        assertThat(savedSlot.captured.lastCollectionDate).isEqualTo(LocalDate.parse("2026-03-01"))
        assertThat(msgSlot.captured.eventType).isEqualTo("sdd.collection.authorised.v1")
    }

    @Test
    fun `re-authorising the SAME collection (same dueDate) replays the decision with no side effects`() {
        // #8351: an authorised collection is uniquely (mandateId, umr, dueDate) — the triple the
        // debit consumer dedups under. A retried authorise for the same dueDate must return the
        // same Accept WITHOUT re-stamping the mandate or re-emitting the outbox event; a
        // different dueDate is a new collection and flows through the normal Accept branch.
        val mandate = Fixtures.mandate(
            sequenceType = SequenceType.RCUR,
            lastCollectionDate = LocalDate.parse("2026-03-01"),
        )
        every { mandates.findByReference(any(), any()) } returns Uni.createFrom().item(mandate)

        val result = service.authorise(
            CollectionInstruction(
                "DE98ZZZ09999999999",
                "UMR-0001",
                SddScheme.CORE,
                SequenceType.RCUR,
                BigDecimal("42.00"),
                "EUR",
                LocalDate.parse("2026-03-01"),
            ),
            DebtorControls(),
        ).await().indefinitely()

        assertThat(result).isInstanceOf(AuthorisationResult.Accept::class.java)
        verify(exactly = 0) { mandates.save(any()) }
        verify(exactly = 0) { outbox.append(any()) }
    }

    @Test
    fun `a refused or rejected collection neither persists nor emits`() {
        every { mandates.findByReference(any(), any()) } returns Uni.createFrom().nullItem()

        val result = service.authorise(
            CollectionInstruction(
                "DE98ZZZ09999999999",
                "UMR-0001",
                SddScheme.CORE,
                SequenceType.RCUR,
                BigDecimal("42.00"),
                "EUR",
                LocalDate.parse("2026-03-01"),
            ),
            DebtorControls(),
        ).await().indefinitely()

        assertThat(result).isInstanceOf(AuthorisationResult.Reject::class.java)
        verify(exactly = 0) { mandates.save(any()) }
        verify(exactly = 0) { outbox.append(any()) }
    }

    @Test
    fun `refund assessment for an authorised Core debit within 8 weeks is unconditional`() {
        val mandate = Fixtures.mandate(scheme = SddScheme.CORE)
        every { mandates.findById(mandate.id) } returns Uni.createFrom().item(mandate)

        val d = service.assessRefund(mandate.id, LocalDate.parse("2026-02-01"), LocalDate.parse("2026-03-01"))
            .await().indefinitely()

        assertThat(d).isInstanceOf(RefundDecision.Eligible::class.java)
    }

    @Test
    fun `the backoffice queue clamps limit into 1_100 and passes the status filter through`() {
        val captured = mutableListOf<Int>()
        every { mandates.findRecent(any(), any()) } answers {
            captured += secondArg<Int>()
            Uni.createFrom().item(emptyList<SddMandate>())
        }

        service.listRecent(null, 0).await().indefinitely()
        service.listRecent(null, 10_000).await().indefinitely()
        service.listRecent("ACTIVE", 25).await().indefinitely()

        assertThat(captured).containsExactly(1, 100, 25)
        verify(exactly = 1) { mandates.findRecent("ACTIVE", 25) }
        verify(exactly = 2) { mandates.findRecent(null, any()) }
    }
}
