// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.CreateDomesticPaymentCommand
import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.DomesticPaymentProposalDraftRepository
import com.openbank.domestic.application.port.out.PaymentProposalAuthority
import com.openbank.domestic.application.port.out.PaymentProposalAuthorityPort
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticTransferScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class DomesticPaymentProposalDraftServiceTest {
    private val accounts = mockk<AccountLookupPort>()
    private val authority = mockk<PaymentProposalAuthorityPort>()
    private val drafts = mockk<DomesticPaymentProposalDraftRepository>()
    private val service = DomesticPaymentProposalDraftService(
        accounts,
        authority,
        drafts,
        Clock.fixed(Instant.parse("2026-09-18T10:00:00Z"), ZoneOffset.UTC),
    )

    @BeforeEach
    fun setUp() {
        coEvery { accounts.findAccountIdByIban(any()) } returns ACCOUNT
        coEvery { accounts.findPartyByAccountId(ACCOUNT) } returns OWNER
        coEvery { authority.authorize(ACCOUNT, MAKER, any(), "CZK") } returns PaymentProposalAuthority(GRANT, OWNER)
        coEvery { drafts.findByMakerAndKey(MAKER, any()) } returns null
        coEvery { drafts.saveOrGetWinner(any()) } answers { firstArg() }
    }

    @Test
    fun `saves only an immutable maker draft and exact retry replays it`(): Unit = runBlocking {
        val first = service.create(MAKER, command()) as CreatePaymentProposalDraftOutcome.Saved
        assertThat(first.replayed).isFalse()
        assertThat(first.draft.instruction.synthetic).isTrue()
        assertThat(first.draft.makerPartyId).isEqualTo(MAKER)
        assertThat(first.draft.ownerPartyId).isEqualTo(OWNER)
        assertThat(first.draft.expiresAt).isEqualTo(Instant.parse("2026-09-25T10:00:00Z"))

        coEvery { drafts.findByMakerAndKey(MAKER, "proposal-key") } returns first.draft
        val retry = service.create(MAKER, command().copy(actorId = UUID.randomUUID(), actorScope = "spoof"))
        assertThat(retry).isEqualTo(CreatePaymentProposalDraftOutcome.Saved(first.draft, true))
        coVerify(exactly = 1) { drafts.saveOrGetWinner(any()) }
    }

    @Test
    fun `same key with changed payee conflicts rather than replaying`(): Unit = runBlocking {
        val first = service.create(MAKER, command()) as CreatePaymentProposalDraftOutcome.Saved
        coEvery { drafts.findByMakerAndKey(MAKER, "proposal-key") } returns first.draft

        assertThat(service.create(MAKER, command().copy(creditorAccountNumber = "123456789")))
            .isEqualTo(CreatePaymentProposalDraftOutcome.IdempotencyConflict)
    }

    @Test
    fun `missing authority and mismatched owner both fail closed before storage`(): Unit = runBlocking {
        coEvery { authority.authorize(ACCOUNT, MAKER, any(), "CZK") } returns null
        assertThat(service.create(MAKER, command())).isEqualTo(CreatePaymentProposalDraftOutcome.Refused)
        coEvery { authority.authorize(ACCOUNT, MAKER, any(), "CZK") } returns
            PaymentProposalAuthority(GRANT, UUID.randomUUID())
        assertThat(service.create(MAKER, command())).isEqualTo(CreatePaymentProposalDraftOutcome.Refused)
        coVerify(exactly = 0) { drafts.saveOrGetWinner(any()) }
    }

    @Test
    fun `debtor coordinates resolving to another account fail closed`(): Unit = runBlocking {
        coEvery { accounts.findAccountIdByIban(any()) } returns UUID.randomUUID()
        assertThat(service.create(MAKER, command())).isEqualTo(CreatePaymentProposalDraftOutcome.Refused)
        coVerify(exactly = 0) { authority.authorize(any(), any(), any(), any()) }
    }

    @Test
    fun `draft refuses executable payment context and unpersistable amount`() {
        assertThatThrownBy {
            runBlocking {
                service.create(MAKER, command().copy(transferScope = DomesticTransferScope.EXTERNAL))
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            runBlocking {
                service.create(MAKER, command().copy(amount = BigDecimal("1.0000001")))
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            runBlocking {
                service.create(MAKER, command().copy(amount = BigDecimal("1E+100000000")))
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { drafts.saveOrGetWinner(any()) }
    }

    @Test
    fun `history is maker scoped and remains available without a live grant`(): Unit = runBlocking {
        val own = (service.create(MAKER, command()) as CreatePaymentProposalDraftOutcome.Saved).draft
        coEvery { drafts.findById(own.id) } returns own
        coEvery { authority.authorize(any(), any(), any(), any()) } returns null
        coEvery { drafts.listByMaker(MAKER, null, 21) } returns listOf(own)

        assertThat(service.findForMaker(MAKER, own.id)).isEqualTo(own)
        assertThat(service.findForMaker(OWNER, own.id)).isNull()
        assertThat(service.listForMaker(MAKER, null, 20).items).containsExactly(own)
        coVerify(exactly = 0) { authority.authorize(any(), OWNER, any(), any()) }
    }

    @Test
    fun `foreign cursor cannot restart pagination at first page`(): Unit = runBlocking {
        val foreign = (service.create(MAKER, command()) as CreatePaymentProposalDraftOutcome.Saved).draft
        coEvery { drafts.findById(foreign.id) } returns foreign

        assertThat(service.listForMaker(OWNER, foreign.id, 20))
            .isEqualTo(MakerDraftPage(emptyList(), null))
        coVerify(exactly = 0) { drafts.listByMaker(any(), any(), any()) }
    }

    private fun command() = CreateDomesticPaymentCommand(
        idempotencyKey = "proposal-key",
        debtorAccountId = ACCOUNT,
        debtorAccountNumber = "1234567890",
        debtorBankCode = "0800",
        debtorName = "Example s.r.o.",
        creditorAccountNumber = "9876543210",
        creditorBankCode = "0100",
        creditorName = "Supplier",
        amount = BigDecimal("1500.00"),
        currency = "CZK",
        variableSymbol = null,
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = null,
        priority = DomesticPaymentPriority.STANDARD,
        statementLabel = null,
        endToEndId = null,
        synthetic = true,
    )

    private companion object {
        val ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val MAKER = UUID.fromString("00000000-0000-0000-0000-000000000102")
        val OWNER = UUID.fromString("00000000-0000-0000-0000-000000000103")
        val GRANT = UUID.fromString("00000000-0000-0000-0000-000000000104")
    }
}
