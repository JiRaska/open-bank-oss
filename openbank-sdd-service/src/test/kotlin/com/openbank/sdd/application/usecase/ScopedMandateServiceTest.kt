// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.application.usecase

import com.openbank.sdd.application.port.`in`.ListMandatesUseCase
import com.openbank.sdd.application.port.`in`.ManageMandateUseCase
import com.openbank.sdd.application.port.`in`.RegisterMandateCommand
import com.openbank.sdd.application.port.`in`.RegisterMandateUseCase
import com.openbank.sdd.application.port.out.DebtorAccountOwnership
import com.openbank.sdd.application.port.out.DebtorAccountOwnershipPort
import com.openbank.sdd.domain.model.MandateAmendment
import com.openbank.sdd.domain.model.MandateInitiatorScope
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** ADR-0335 D6: a scoped initiator manages only its own creditor's mandates, for the verified subject party. */
class ScopedMandateServiceTest {
    private val pensionCid = "CZ00ZZZPENSION01"
    private val scope = MandateInitiatorScope.Scoped("service-account-openbank-pension", pensionCid)
    private val party = UUID.randomUUID()
    private val accountId = UUID.randomUUID()
    private val iban = "CZ6508000000192000145399"

    private val register = mockk<RegisterMandateUseCase>()
    private val manage = mockk<ManageMandateUseCase>()
    private val list = mockk<ListMandatesUseCase>()
    private val ownership = mockk<DebtorAccountOwnershipPort>()
    private val service = ScopedMandateService(register, manage, list, ownership)

    private fun command(cid: String = pensionCid, account: UUID = accountId) = RegisterMandateCommand(
        accountId = account, debtorIban = iban, creditorIdentifier = cid, umr = "PENS-1", scheme = SddScheme.CORE,
        sequenceType = SequenceType.RCUR, creditorName = "Pension", debtorName = "Debtor",
        signatureDate = LocalDate.of(2026, 10, 1),
    )

    private fun owned(active: Boolean = true, id: UUID? = accountId) = every { ownership.verify(iban, party) } returns
        Uni.createFrom().item(DebtorAccountOwnership(id != null, active, id))

    @Test
    fun `registers a pension-creditor mandate on the subject party's own active account`() {
        owned()
        every { register.register(any()) } returns Uni.createFrom().item(mandate(pensionCid))
        assertThat(
            service.register(command(), party, scope).await().indefinitely().creditorIdentifier,
        ).isEqualTo(pensionCid)
    }

    @Test
    fun `refuses another creditor identifier before asking anyone`() {
        assertRefused { service.register(command(cid = "CZ99ZZZOTHER0001"), party, scope) }
        verify(exactly = 0) { ownership.verify(any(), any()) }
    }

    @Test
    fun `refuses a debtor account the subject party does not own, holds inactive, or that is another account`() {
        owned(id = null)
        assertRefused { service.register(command(), party, scope) }
        owned(active = false)
        assertRefused { service.register(command(), party, scope) }
        owned(id = UUID.randomUUID())
        assertRefused { service.register(command(), party, scope) }
        verify(exactly = 0) { register.register(any()) }
    }

    @Test
    fun `an unconfigured creditor identifier permits nothing and a missing party is a 400`() {
        assertRefused {
            service.register(command(), party, MandateInitiatorScope.Scoped("service-account-openbank-pension", null))
        }
        assertThatThrownBy { service.register(command(), null, scope).await().indefinitely() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `cancels only its own creditor's mandates`() {
        val mine = UUID.randomUUID()
        val theirs = UUID.randomUUID()
        every { list.get(mine) } returns Uni.createFrom().item(mandate(pensionCid))
        every { list.get(theirs) } returns Uni.createFrom().item(mandate("CZ99ZZZOTHER0001"))
        every { manage.cancel(mine) } returns Uni.createFrom().item(mandate(pensionCid))
        service.cancel(mine, scope).await().indefinitely()
        assertRefused { service.cancel(theirs, scope) }
        verify(exactly = 0) { manage.cancel(theirs) }
    }

    @Test
    fun `a general caller passes straight through`() {
        every { register.register(any()) } returns Uni.createFrom().item(mandate("CZ99ZZZOTHER0001"))
        service.register(command(cid = "CZ99ZZZOTHER0001"), null, MandateInitiatorScope.General).await().indefinitely()
        verify(exactly = 0) { ownership.verify(any(), any()) }
    }

    private fun assertRefused(call: () -> Uni<SddMandate>) {
        assertThatThrownBy { call().await().indefinitely() }.isInstanceOf(MandateScopeViolationException::class.java)
    }

    private fun mandate(cid: String) = SddMandate(
        id = UUID.randomUUID(), accountId = accountId, debtorIban = iban, creditorIdentifier = cid, umr = "PENS-1",
        scheme = SddScheme.CORE, sequenceType = SequenceType.RCUR, creditorName = "C", debtorName = "D",
        signatureDate = LocalDate.of(2026, 10, 1), status = MandateStatus.ACTIVE, b2bConfirmed = false,
        lastCollectionDate = null, lastPreNotificationDate = null, createdAt = Instant.now(),
        amendments = emptyList<MandateAmendment>(),
    )
}
