// SPDX-License-Identifier: MPL-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.\n// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.\n
package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.CreateDomesticPaymentCommand
import com.openbank.domestic.application.port.`in`.TransitionDomesticPaymentStatusCommand
import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.AmlCasePort
import com.openbank.domestic.application.port.out.AmlCaseRiskLevel
import com.openbank.domestic.application.port.out.DomesticPaymentEventPublisher
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.port.out.FraudScoreOutcome
import com.openbank.domestic.application.port.out.FraudScoringPort
import com.openbank.domestic.application.port.out.FraudVerdict
import com.openbank.domestic.application.port.out.SanctionsScreeningPort
import com.openbank.domestic.application.port.out.SchemeGatewayPort
import com.openbank.domestic.application.port.out.SchemeSubmissionOutcome
import com.openbank.domestic.application.port.out.ScreeningUnavailableException
import com.openbank.domestic.application.port.out.SettlementOutcome
import com.openbank.domestic.application.port.out.SettlementPort
import com.openbank.domestic.application.port.out.SettlementUnavailableException
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.domestic.domain.screening.ScreeningMatchStatus
import com.openbank.domestic.domain.screening.ScreeningResult
import com.openbank.domestic.domain.screening.ScreeningRole
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.temporal.client.WorkflowClient
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class DomesticPaymentServiceTest {
    private lateinit var paymentRepository: DomesticPaymentRepository
    private lateinit var eventPublisher: DomesticPaymentEventPublisher
    private lateinit var screeningPort: SanctionsScreeningPort
    private lateinit var amlCasePort: AmlCasePort
    private lateinit var fraudScoringPort: FraudScoringPort
    private lateinit var schemeGatewayPort: SchemeGatewayPort
    private lateinit var settlementPort: SettlementPort
    private lateinit var accountLookupPort: AccountLookupPort
    private lateinit var metrics: DomainMetrics
    private lateinit var workflowClient: WorkflowClient

    private lateinit var service: DomesticPaymentService

    @BeforeEach
    fun setUp() {
        paymentRepository = mockk()
        eventPublisher = mockk()
        screeningPort = mockk()
        amlCasePort = mockk()
        fraudScoringPort = mockk()
        schemeGatewayPort = mockk()
        settlementPort = mockk()
        accountLookupPort = mockk()
        metrics = mockk(relaxed = true)
        workflowClient = mockk()
        // temporalEnabled = false: these tests cover the synchronous screening/fraud path
        // (ADR-0101 P2 delegates to Temporal only when enabled), so workflowClient is unused.
        service = DomesticPaymentService(
            paymentRepository,
            eventPublisher,
            screeningPort,
            amlCasePort,
            fraudScoringPort,
            schemeGatewayPort,
            settlementPort,
            accountLookupPort,
            metrics,
            temporalEnabled = false,
            temporalTaskQueue = "openbank-domestic-payments",
            schemeSubmissionEnabled = false,
            workflowClient = workflowClient,
        )

        // Account lookup for server-side transferScope derivation: default to null (INTERNAL_CLIENT).
        coEvery { accountLookupPort.findPartyByIban(any()) } returns null

        // Fraud scoring is SHADOW (ADR-0084): default to ALLOW; never affects the payment outcome.
        coEvery { fraudScoringPort.score(any()) } returns FraudScoreOutcome(FraudVerdict.ALLOW, 0, "v0", emptyList())

        // Defaults reused by most tests: repo echoes the aggregate, publisher returns opaque payloads.
        coEvery { paymentRepository.save(any(), any()) } answers { firstArg() }
        coEvery { paymentRepository.update(any(), any()) } answers { firstArg() }
        every { eventPublisher.paymentCreatedPayload(any()) } returns "{\"event\":\"created\"}"
        every { eventPublisher.statusChangedPayload(any(), any()) } returns "{\"event\":\"status-changed\"}"
        coJustRun { amlCasePort.openCase(any()) }
    }

    private fun clear(role: ScreeningRole) = ScreeningResult("name", role, ScreeningMatchStatus.CLEAR, 0.0, null)

    @Test
    fun `create payment normalizes domestic fields and persists outbox event`(): Unit = runBlocking {
        val command = createCommand()

        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns clear(ScreeningRole.DEBTOR)
        coEvery { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns clear(ScreeningRole.CREDITOR)

        val result = service.createPayment(command)

        assertThat(result.status).isEqualTo(DomesticPaymentStatus.VALIDATED)
        assertThat(result.currency).isEqualTo("CZK")
        assertThat(result.priority).isEqualTo(DomesticPaymentPriority.URGENT)
        assertThat(result.transferScope).isEqualTo(DomesticTransferScope.EXTERNAL) // creditorBankCode="0100" → EXTERNAL
        assertThat(result.variableSymbol).isEqualTo("2026001")
        assertThat(result.messageForPayee).isEqualTo("Utility bill")
        assertThat(result.endToEndId).startsWith("DOMU")

        coVerify {
            paymentRepository.save(
                match {
                    it.creditorName == "Brno Utility" &&
                        it.statementLabel == "Monthly settlement" &&
                        it.currency == "CZK"
                },
                match<OutboxMessage> {
                    it.aggregateId == result.id &&
                        it.eventType == "domestic.payment.created" &&
                        it.payload.contains("created")
                },
            )
        }
        // SHADOW: the scorer is consulted on every created payment (ADR-0084 §4.1).
        coVerify(exactly = 1) { fraudScoringPort.score(any()) }
    }

    @Test
    fun `fraud shadow verdict is observed but never enforced`(): Unit = runBlocking {
        val command = createCommand()
        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns clear(ScreeningRole.DEBTOR)
        coEvery { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns clear(ScreeningRole.CREDITOR)
        coEvery { fraudScoringPort.score(any()) } returns
            FraudScoreOutcome(FraudVerdict.DECLINE, 99, "v0", listOf("velocity-cap"))

        val result = service.createPayment(command)

        assertThat(result.status).isEqualTo(DomesticPaymentStatus.VALIDATED)
        coVerify(exactly = 1) { fraudScoringPort.score(any()) }
    }

    @Test
    fun `create payment replays existing domestic payment on idempotency key`(): Unit = runBlocking {
        val existing = payment()
        coEvery { paymentRepository.findByIdempotencyKey(existing.idempotencyKey) } returns existing

        val result = service.createPayment(createCommand(idempotencyKey = existing.idempotencyKey))

        assertThat(result).isEqualTo(existing)
        coVerify(exactly = 0) { paymentRepository.save(any(), any()) }
    }

    @Test
    fun `create payment derives EXTERNAL scope for non-own-bank creditor`() = runBlocking<Unit> {
        val command = createCommand() // creditorBankCode = "0100" → EXTERNAL

        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns clear(ScreeningRole.DEBTOR)
        coEvery { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns clear(ScreeningRole.CREDITOR)

        val result = service.createPayment(command)

        assertThat(result.transferScope).isEqualTo(DomesticTransferScope.EXTERNAL)
    }

    @Test
    fun `create payment derives INTERNAL_CLIENT scope for own-bank creditor unknown party`() = runBlocking<Unit> {
        val command = createCommand(creditorBankCode = "0000")

        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns clear(ScreeningRole.DEBTOR)
        coEvery { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns clear(ScreeningRole.CREDITOR)

        val result = service.createPayment(command)

        assertThat(result.transferScope).isEqualTo(DomesticTransferScope.INTERNAL_CLIENT)
    }

    @Test
    fun `own accounts path persists correctly`(): Unit = runBlocking {
        val actorId = UUID.randomUUID()
        // own-bank creditor whose partyId matches the actor → OWN_ACCOUNTS
        coEvery { accountLookupPort.findPartyByIban(any()) } returns actorId
        val command = createCommand(
            creditorBankCode = "0000",
            actorId = actorId,
            technicalAccountCode = null,
        )

        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { settlementPort.settle(any()) } returns SettlementOutcome(settled = true, transactionId = null)

        val result = service.createPayment(command)

        assertThat(result.transferScope).isEqualTo(DomesticTransferScope.OWN_ACCOUNTS)
        coVerify {
            paymentRepository.save(
                match {
                    it.transferScope == DomesticTransferScope.OWN_ACCOUNTS &&
                        it.technicalAccountCode == null
                },
                any(),
            )
        }
    }

    @Test
    fun `clean screening validates the payment and persists both outbox messages`(): Unit = runBlocking {
        val command = createCommand()
        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns clear(ScreeningRole.DEBTOR)
        coEvery { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns clear(ScreeningRole.CREDITOR)

        val result = service.createPayment(command)

        assertThat(result.status).isEqualTo(DomesticPaymentStatus.VALIDATED)
        coVerify {
            paymentRepository.save(
                match { it.status == DomesticPaymentStatus.RECEIVED && it.idempotencyKey == command.idempotencyKey },
                match<OutboxMessage> { it.eventType == "domestic.payment.created" },
            )
        }
        coVerify {
            paymentRepository.update(
                match { it.status == DomesticPaymentStatus.VALIDATED },
                match<OutboxMessage> { it.eventType == "domestic.payment.status-changed" },
            )
        }
        coVerify(exactly = 0) { amlCasePort.openCase(any()) }
    }

    @Test
    fun `sanctioned creditor rejects the payment and opens a CRITICAL aml case`(): Unit = runBlocking {
        val command = createCommand()
        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns clear(ScreeningRole.DEBTOR)
        coEvery { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns
            ScreeningResult(
                "Brno Utility",
                ScreeningRole.CREDITOR,
                ScreeningMatchStatus.HIT,
                0.97,
                "BRNO UTILITY / OFAC",
            )

        val result = service.createPayment(command)

        assertThat(result.status).isEqualTo(DomesticPaymentStatus.REJECTED)
        assertThat(result.rejectReason).isEqualTo(DomesticRejectReason.SANCTIONS_HIT)
        coVerify {
            amlCasePort.openCase(
                match {
                    it.riskLevel == AmlCaseRiskLevel.CRITICAL &&
                        it.alertCode == "SANCTIONS_HIT" &&
                        it.paymentId == result.id &&
                        it.matchedEntity == "BRNO UTILITY / OFAC"
                },
            )
        }
        coVerify { paymentRepository.update(match { it.status == DomesticPaymentStatus.REJECTED }, any()) }
    }

    @Test
    fun `sub-threshold potential hit holds the payment in RECEIVED and opens a HIGH aml case`(): Unit = runBlocking {
        val command = createCommand()
        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns clear(ScreeningRole.DEBTOR)
        coEvery { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns
            ScreeningResult(
                "Brno Utility",
                ScreeningRole.CREDITOR,
                ScreeningMatchStatus.POTENTIAL_HIT,
                0.50,
                "B. UTILITY",
            )

        val result = service.createPayment(command)

        assertThat(result.status).isEqualTo(DomesticPaymentStatus.RECEIVED)
        coVerify {
            amlCasePort.openCase(
                match { it.riskLevel == AmlCaseRiskLevel.HIGH && it.alertCode == "AML_HOLD" },
            )
        }
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `screening unavailable holds the payment in RECEIVED and opens a MEDIUM aml case`(): Unit = runBlocking {
        val command = createCommand()
        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery {
            screeningPort.screen(any(), any(), any())
        } throws ScreeningUnavailableException(RuntimeException("down"))

        val result = service.createPayment(command)

        assertThat(result.status).isEqualTo(DomesticPaymentStatus.RECEIVED)
        coVerify {
            amlCasePort.openCase(
                match { it.riskLevel == AmlCaseRiskLevel.MEDIUM && it.alertCode == "SCREENING_UNAVAILABLE" },
            )
        }
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `scheme ACSC followed by successful settlement transitions payment to SETTLED`(): Unit = runBlocking {
        val serviceWithScheme = DomesticPaymentService(
            paymentRepository,
            eventPublisher,
            screeningPort,
            amlCasePort,
            fraudScoringPort,
            schemeGatewayPort,
            settlementPort,
            accountLookupPort,
            metrics,
            temporalEnabled = false,
            temporalTaskQueue = "openbank-domestic-payments",
            schemeSubmissionEnabled = true,
            workflowClient = workflowClient,
        )
        val command = createCommand()
        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery {
            screeningPort.screen(any(), any(), any())
        } returns clear(ScreeningRole.DEBTOR)
        coEvery { schemeGatewayPort.submit(any()) } returns SchemeSubmissionOutcome(accepted = true, reasonCode = null)
        coEvery { settlementPort.settle(any()) } returns SettlementOutcome(settled = true, transactionId = null)

        val result = serviceWithScheme.createPayment(command)

        assertThat(result.status).isEqualTo(DomesticPaymentStatus.SETTLED)
    }

    @Test
    fun `settlement unavailable holds payment in SENT_TO_CLEARING`(): Unit = runBlocking {
        val serviceWithScheme = DomesticPaymentService(
            paymentRepository,
            eventPublisher,
            screeningPort,
            amlCasePort,
            fraudScoringPort,
            schemeGatewayPort,
            settlementPort,
            accountLookupPort,
            metrics,
            temporalEnabled = false,
            temporalTaskQueue = "openbank-domestic-payments",
            schemeSubmissionEnabled = true,
            workflowClient = workflowClient,
        )
        val command = createCommand()
        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery {
            screeningPort.screen(any(), any(), any())
        } returns clear(ScreeningRole.DEBTOR)
        coEvery { schemeGatewayPort.submit(any()) } returns SchemeSubmissionOutcome(accepted = true, reasonCode = null)
        coEvery { settlementPort.settle(any()) } throws SettlementUnavailableException("tx-service down")

        val result = serviceWithScheme.createPayment(command)

        assertThat(result.status).isEqualTo(DomesticPaymentStatus.SENT_TO_CLEARING)
    }

    @Test
    fun `transition to rejected requires reject reason`() {
        val existing = payment(status = DomesticPaymentStatus.RECEIVED)
        coEvery { paymentRepository.findById(existing.id) } returns existing

        assertThatThrownBy {
            runBlocking {
                service.transitionStatus(
                    TransitionDomesticPaymentStatusCommand(
                        paymentId = existing.id,
                        targetStatus = DomesticPaymentStatus.REJECTED,
                        rejectReason = null,
                        rejectDetail = "Bank code invalid",
                    ),
                )
            }
        }
            .isInstanceOf(InvalidDomesticPaymentStateTransitionException::class.java)
            .hasMessageContaining("Reject reason is required")

        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    private fun createCommand(
        idempotencyKey: String = "dom-idem-1",
        technicalAccountCode: String? = null,
        creditorBankCode: String = " 0100 ",
        actorId: UUID? = null,
    ) = CreateDomesticPaymentCommand(
        idempotencyKey = idempotencyKey,
        debtorAccountId = UUID.randomUUID(),
        debtorAccountNumber = " 1234567890 ",
        debtorBankCode = " 0800 ",
        debtorName = "  Alice Example ",
        creditorAccountNumber = " 9876543210 ",
        creditorBankCode = creditorBankCode,
        creditorName = "  Brno Utility ",
        amount = BigDecimal("1500.00"),
        currency = " czk ",
        variableSymbol = " 2026001 ",
        specificSymbol = null,
        constantSymbol = " 0308 ",
        messageForPayee = "  Utility bill ",
        priority = DomesticPaymentPriority.URGENT,
        technicalAccountCode = technicalAccountCode,
        statementLabel = "  Monthly settlement  ",
        endToEndId = "   ",
        actorId = actorId,
    )

    private fun payment(status: DomesticPaymentStatus = DomesticPaymentStatus.RECEIVED) = DomesticPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "dom-idem-existing",
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorAccountNumber = "1234567890",
        debtorBankCode = "0800",
        debtorName = "Alice Example",
        creditorAccountNumber = "9876543210",
        creditorBankCode = "0100",
        creditorName = "Brno Utility",
        amount = BigDecimal("1500.00"),
        currency = "CZK",
        variableSymbol = "2026001",
        specificSymbol = null,
        constantSymbol = "0308",
        messageForPayee = "Utility bill",
        priority = DomesticPaymentPriority.URGENT,
        transferScope = DomesticTransferScope.INTERNAL_CLIENT,
        technicalAccountCode = null,
        statementLabel = "Monthly settlement",
        endToEndId = "DOMU123",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        settledAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}
