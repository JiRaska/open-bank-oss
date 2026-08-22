// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.account.application.port.`in`.AccountUseCase
import com.openbank.account.application.port.`in`.OpenAccountCommand
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.NotificationRequestPort
import com.openbank.account.application.port.out.WelcomeBonusPort
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID

private class TransientDownstreamFailure : RuntimeException("transaction-service down")

class PartyEventConsumerTest {

    private val accountUseCase: AccountUseCase = mockk(relaxed = true)
    private val accountRepository: AccountRepository = mockk()
    private val welcomeBonusPort: WelcomeBonusPort = mockk(relaxed = true)
    private val notificationRequestPort: NotificationRequestPort = mockk(relaxed = true)
    private val objectMapper = ObjectMapper()

    private fun consumer(bonusEnabled: Boolean) = PartyEventConsumer(
        accountUseCase = accountUseCase,
        accountRepository = accountRepository,
        objectMapper = objectMapper,
        defaultProductId = "00000000-0000-0000-0000-0000000000c2",
        savingsProductId = "00000000-0000-0000-0000-0000000000c3",
        defaultCurrency = "CZK",
        systemActorId = "00000000-0000-0000-0000-0000000005ec",
        welcomeBonusPort = welcomeBonusPort,
        welcomeBonusEnabled = bonusEnabled,
        welcomeBonusAmount = BigDecimal("100000.00"),
        welcomeBonusCurrency = "CZK",
        notificationRequestPort = notificationRequestPort,
    )

    private fun pendingAccount(id: UUID, type: AccountType = AccountType.CURRENT): Account = mockk {
        every { this@mockk.id } returns id
        every { status } returns AccountStatus.PENDING_ACTIVATION
        every { accountType } returns type
    }

    // Wire contract = the DEPLOYED producer (party-service KafkaPartyEventPublisher): a FLAT
    // envelope on topic openbank.party.events. These fixtures MUST mirror that producer's
    // serialized output byte-for-byte, otherwise a green unit test hides a runtime no-op.
    private fun createdEvent(partyId: UUID, legalName: String = "Jan Novák", partyType: String = "INDIVIDUAL") =
        """{"eventType":"PARTY_CREATED","partyId":"$partyId","partyType":"$partyType",""" +
            """"status":"PENDING_KYC","legalName":"$legalName","email":"jan@example.cz","occurredAt":"2026-06-11T08:00:00Z"}"""

    private fun activeEvent(partyId: UUID) =
        """{"eventType":"PARTY_UPDATED","partyId":"$partyId","partyType":"INDIVIDUAL",""" +
            """"status":"ACTIVE","legalName":"Jan Novák","email":"jan@example.cz","occurredAt":"2026-06-11T08:05:00Z"}"""

    @Test
    fun `PARTY_CREATED for an individual opens PENDING_ACTIVATION current and savings accounts`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { accountRepository.findByPartyId(partyId, any(), any()) } returns emptyList()
        val cmds = mutableListOf<OpenAccountCommand>()
        coEvery { accountUseCase.openAccount(capture(cmds)) } returns mockk(relaxed = true)

        consumer(bonusEnabled = false).consume(createdEvent(partyId, legalName = "Jan Novák"))

        coVerify(exactly = 2) { accountUseCase.openAccount(any()) }
        val current = cmds.single { it.accountType == AccountType.CURRENT }
        assertThat(current.partyId).isEqualTo(partyId)
        assertThat(current.legalName).isEqualTo("Jan Novák") // required for sanctions screening (ADR-0032 §C)
        assertThat(current.initialStatus).isEqualTo(AccountStatus.PENDING_ACTIVATION)
        assertThat(current.idempotencyKey).isEqualTo("onboarding-account-$partyId")
        val savings = cmds.single { it.accountType == AccountType.SAVINGS }
        assertThat(savings.partyId).isEqualTo(partyId)
        assertThat(savings.legalName).isEqualTo("Jan Novák")
        assertThat(savings.initialStatus).isEqualTo(AccountStatus.PENDING_ACTIVATION)
        assertThat(savings.idempotencyKey).isEqualTo("onboarding-savings-$partyId")
        assertThat(savings.productId).isEqualTo(UUID.fromString("00000000-0000-0000-0000-0000000000c3"))
    }

    @Test
    fun `PARTY_CREATED is idempotent — existing current and savings accounts mean no re-open`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { accountRepository.findByPartyId(partyId, any(), any()) } returns listOf(
            pendingAccount(UUID.randomUUID(), AccountType.CURRENT),
            pendingAccount(UUID.randomUUID(), AccountType.SAVINGS),
        )

        consumer(bonusEnabled = false).consume(createdEvent(partyId))

        coVerify(exactly = 0) { accountUseCase.openAccount(any()) }
    }

    @Test
    fun `PARTY_CREATED backfills only the missing savings account for a pre-rollout party`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { accountRepository.findByPartyId(partyId, any(), any()) } returns listOf(
            pendingAccount(UUID.randomUUID(), AccountType.CURRENT),
        )
        val cmds = mutableListOf<OpenAccountCommand>()
        coEvery { accountUseCase.openAccount(capture(cmds)) } returns mockk(relaxed = true)

        consumer(bonusEnabled = false).consume(createdEvent(partyId))

        coVerify(exactly = 1) { accountUseCase.openAccount(any()) }
        assertThat(cmds.single().accountType).isEqualTo(AccountType.SAVINGS)
    }

    @Test
    fun `PARTY_CREATED for a non-individual is ignored (operator-opened)`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()

        consumer(bonusEnabled = false).consume(createdEvent(partyId, partyType = "COMPANY"))

        coVerify(exactly = 0) { accountRepository.findByPartyId(any(), any(), any()) }
        coVerify(exactly = 0) { accountUseCase.openAccount(any()) }
    }

    @Test
    fun `party ACTIVE activates both accounts and grants the bonus only to the current one`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val currentId = UUID.randomUUID()
        val savingsId = UUID.randomUUID()
        coEvery { accountRepository.findByPartyId(partyId, any(), any()) } returns listOf(
            pendingAccount(currentId, AccountType.CURRENT),
            pendingAccount(savingsId, AccountType.SAVINGS),
        )

        consumer(bonusEnabled = true).consume(activeEvent(partyId))

        coVerify { accountUseCase.activateAccount(currentId) }
        coVerify { accountUseCase.activateAccount(savingsId) }
        // One credit per CUSTOMER: the savings account activates dry.
        coVerify(exactly = 1) { welcomeBonusPort.grantWelcomeBonus(any(), any(), any()) }
        coVerify {
            welcomeBonusPort.grantWelcomeBonus(currentId, BigDecimal("100000.00"), "CZK")
        }
        // A successful grant also notifies the party (in-app feed + push).
        coVerify(exactly = 1) { notificationRequestPort.notifyIncomingCredit(partyId, BigDecimal("100000.00"), "CZK") }
    }

    @Test
    fun `party ACTIVE activates the account but skips the bonus when disabled`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        coEvery { accountRepository.findByPartyId(partyId, any(), any()) } returns listOf(pendingAccount(accountId))

        consumer(bonusEnabled = false).consume(activeEvent(partyId))

        coVerify { accountUseCase.activateAccount(accountId) }
        coVerify(exactly = 0) { welcomeBonusPort.grantWelcomeBonus(any(), any(), any()) }
        coVerify(exactly = 0) { notificationRequestPort.notifyIncomingCredit(any(), any(), any()) }
    }

    /**
     * Replaces `a failing welcome bonus does not propagate out of the consumer or notify`, which
     * asserted that a failed grant is swallowed because "the grant is best-effort". It is not: the
     * party goes ACTIVE exactly once, so there is no later event to retry on, and the customer
     * simply never receives the money — with an ERROR line as the only trace (#5698). The bonus now
     * retries — through consume()'s existing withBoundedRetry, not a second nested one — and then
     * propagates, so the record dead-letters and the grant can be re-driven.
     *
     * The notification that follows the grant IS best-effort and stays swallowed: by then the money
     * is booked, so the event is complete without it.
     */
    @Test
    fun `a failing welcome bonus propagates after retries so the record is dead-lettered`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        coEvery { accountRepository.findByPartyId(partyId, any(), any()) } returns listOf(pendingAccount(accountId))
        coEvery { welcomeBonusPort.grantWelcomeBonus(any(), any(), any()) } throws
            TransientDownstreamFailure()

        assertThrows<TransientDownstreamFailure> {
            runBlocking { consumer(bonusEnabled = true).consume(activeEvent(partyId)) }
        }

        coVerify { accountUseCase.activateAccount(accountId) }
        // MAX_PROJECTION_ATTEMPTS: consume()'s own retry loop is the only one on this path.
        coVerify(exactly = 4) { welcomeBonusPort.grantWelcomeBonus(any(), any(), any()) }
        // No bonus → no "you received money" notification.
        coVerify(exactly = 0) { notificationRequestPort.notifyIncomingCredit(any(), any(), any()) }
    }

    // ── GDPR Art. 17 ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `PARTY_ERASED anonymises legalName via anonymizeByPartyId`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { accountRepository.anonymizeByPartyId(partyId) } returns 2

        consumer(bonusEnabled = false).consume(erasedEvent(partyId))

        coVerify(exactly = 1) { accountRepository.anonymizeByPartyId(partyId) }
    }

    @Test
    fun `PARTY_ERASED anonymisation failure propagates after retries so the record is dead-lettered`(): Unit =
        runBlocking {
            val partyId = UUID.randomUUID()
            coEvery { accountRepository.anonymizeByPartyId(partyId) } throws RuntimeException("DB down")

            // A transient DB failure must NOT be swallowed — swallowing left the GDPR erasure
            // silently incomplete AND (on the account-open path) dropped the customer's account.
            // It now propagates so SmallRye dead-letters the record for replay.
            assertThatThrownBy {
                runBlocking { consumer(bonusEnabled = false).consume(erasedEvent(partyId)) }
            }.isInstanceOf(RuntimeException::class.java)

            // Retried up to the bound before giving up (idempotent projection, safe to re-run).
            coVerify(exactly = 4) { accountRepository.anonymizeByPartyId(partyId) }
        }

    // ── Poison pill vs transient failure ──────────────────────────────────────────────────────

    @Test
    fun `a malformed event is dropped as a poison pill without touching the projection`(): Unit = runBlocking {
        // Must NOT throw (that would nack an unprocessable event to the DLQ forever) and must not
        // attempt any projection.
        consumer(bonusEnabled = false).consume("}{ not json")
        consumer(bonusEnabled = false).consume("""{"eventType":"PARTY_CREATED","partyId":"not-a-uuid"}""")

        coVerify(exactly = 0) { accountRepository.findByPartyId(any(), any(), any()) }
        coVerify(exactly = 0) { accountUseCase.openAccount(any()) }
    }

    @Test
    fun `a transient projection failure on PARTY_CREATED retries then propagates for dead-lettering`(): Unit =
        runBlocking {
            val partyId = UUID.randomUUID()
            coEvery { accountRepository.findByPartyId(partyId, any(), any()) } throws
                RuntimeException("sanctions screening unavailable")

            // A well-formed event we could not project yet must NOT be acked-and-dropped — it
            // propagates after a bounded retry so the record is dead-lettered, never lost.
            assertThatThrownBy {
                runBlocking { consumer(bonusEnabled = false).consume(createdEvent(partyId)) }
            }.isInstanceOf(RuntimeException::class.java)

            coVerify(exactly = 4) { accountRepository.findByPartyId(partyId, any(), any()) }
        }

    @Test
    fun `an unknown event type is a no-op ack, not a poison pill`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val unknown = """{"eventType":"PARTY_MERGED","partyId":"$partyId","occurredAt":"2026-06-28T10:00:00Z"}"""

        // Parses fine, dispatch has no branch for it → nothing happens, no throw.
        consumer(bonusEnabled = false).consume(unknown)

        coVerify(exactly = 0) { accountUseCase.openAccount(any()) }
        coVerify(exactly = 0) { accountRepository.anonymizeByPartyId(any()) }
    }

    private fun erasedEvent(partyId: UUID) =
        """{"eventType":"PARTY_ERASED","partyId":"$partyId","occurredAt":"2026-06-28T10:00:00Z"}"""
}
