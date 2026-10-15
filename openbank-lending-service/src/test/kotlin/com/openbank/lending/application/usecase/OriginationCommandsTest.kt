// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.BorrowerAccountLookupPort
import com.openbank.lending.application.port.out.BorrowerCreditPort
import com.openbank.lending.application.port.out.CatalogLoanProfilePort
import com.openbank.lending.application.port.out.CollateralRepository
import com.openbank.lending.application.port.out.CollateralValuationPort
import com.openbank.lending.application.port.out.InstallmentRepository
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.application.port.out.ProvisioningRepository
import com.openbank.lending.application.port.out.RiskParameterSource
import com.openbank.lending.application.port.out.StarterCreditPolicy
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.SignApplicationRequest
import com.openbank.lending.domain.model.SubmitDocumentsRequest
import com.openbank.lending.domain.model.WithdrawApplicationRequest
import com.openbank.lending.infrastructure.adapter.NoOpOriginationWorkflowPort
import com.openbank.lending.infrastructure.compliance.CompliancePackGuard
import com.openbank.lending.infrastructure.compliance.OriginationConfig
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.compliance.CompliancePackRegistry
import com.openbank.libs.lending.origination.OriginationState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The ADR-0211 explicit origination commands (withdraw / submitDocuments / sign) and the
 * four-eyes SLA escalation, at the application-service level: machine validation, the CAS
 * claim, the evidence event, and the refusal paths. The generic advance/decide coverage lives
 * in [LendingServiceTest]; these tests pin what the explicit commands ADD.
 */
class OriginationCommandsTest {

    private val applications = mockk<LoanApplicationRepository>()
    private val loans = mockk<LoanRepository>()
    private val installments = mockk<InstallmentRepository>()
    private val collateral = mockk<CollateralRepository>()
    private val ledger = mockk<LedgerPostingPort>()
    private val valuation = mockk<CollateralValuationPort>()
    private val riskParameters = mockk<RiskParameterSource>()
    private val events = mockk<LoanEventEmitter>()
    private val clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val provisioning = mockk<ProvisioningRepository>()
    private val borrowerAccounts = mockk<BorrowerAccountLookupPort>()
    private val borrowerCredit = mockk<BorrowerCreditPort>()
    private val catalogLoanProfiles = mockk<CatalogLoanProfilePort>()

    private val emitted = mutableListOf<LendingOutboxMessage>()

    private val service = LendingService(
        applications,
        loans,
        installments,
        collateral,
        ledger,
        valuation,
        riskParameters,
        events,
        clock,
        provisioning,
        CompliancePackGuard(CompliancePackRegistry(), clock, enforced = false),
        OriginationConfig(false, false),
        NoOpOriginationWorkflowPort(),
        OriginationDecisionService(
            mockk<com.openbank.lending.application.port.out.CreditBureauPort> {
                every { assess(any(), any()) } returns Uni.createFrom().item(
                    com.openbank.lending.application.port.out.CreditAssessment(null, false, "test-bureau", true),
                )
            },
            StarterCreditPolicy(),
            CompliancePackGuard(CompliancePackRegistry(), clock, enforced = false),
            clock,
        ),
        borrowerAccounts,
        borrowerCredit,
        catalogLoanProfiles,
    )

    @BeforeEach
    fun stubEventEmitter() {
        emitted.clear()
        every { events.emit(any<LendingOutboxMessage>()) } answers {
            emitted.add(firstArg())
            Uni.createFrom().item(Unit)
        }
        every { applications.compareAndSetSignature(any(), any()) } returns Uni.createFrom().item(1)
        every { applications.compareAndSetStatus(any(), any(), any(), any(), any(), any()) } returns
            Uni.createFrom().item(1)
    }

    private val partyId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    private fun application(
        state: OriginationState,
        proposer: String = "alice",
        decidedBy: String? = null,
        artefactHash: String? = null,
    ) = LoanApplication(
        partyId = partyId,
        requestedAmount = Money.of("12000.00", "EUR"),
        nominalAnnualRate = BigDecimal("0.12"),
        termPeriods = 12,
        firstDueDate = LocalDate.parse("2026-06-30"),
        proposedBy = proposer,
        status = state,
        decidedBy = decidedBy,
        signatureArtefactHash = artefactHash,
        createdAt = OffsetDateTime.now(clock),
    )

    private fun evidence(eventType: String): LendingOutboxMessage? = emitted.firstOrNull { it.eventType == eventType }

    // --- withdraw -----------------------------------------------------------------------------------

    @Test
    fun `withdraw moves a SUBMITTED application to WITHDRAWN with evidence`() {
        val app = application(OriginationState.SUBMITTED)
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        val result = service.withdraw(app.id, WithdrawApplicationRequest("no longer needed"), "officer-1")
            .await().indefinitely()

        assertThat(result.status).isEqualTo(OriginationState.WITHDRAWN)
        verify(exactly = 1) {
            applications.compareAndSetStatus(
                app.id,
                OriginationState.SUBMITTED,
                OriginationState.WITHDRAWN,
                null,
                null,
                null,
            )
        }
        val evidence = evidence("credit.application.transition")
        assertThat(evidence).isNotNull
        assertThat(evidence!!.payload).contains("\"toState\":\"WITHDRAWN\"")
        assertThat(evidence.payload).contains("no longer needed")
    }

    @Test
    fun `withdraw from SIGNED is refused by the graph - a signed contract is not abandoned`() {
        val app = application(OriginationState.SIGNED, decidedBy = "checker-1")
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        assertThatThrownBy {
            service.withdraw(app.id, WithdrawApplicationRequest("no longer needed"), "officer-1")
                .await().indefinitely()
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not allowed")

        verify(exactly = 0) { applications.compareAndSetStatus(any(), any(), any(), any(), any(), any()) }
        assertThat(emitted).isEmpty()
    }

    @Test
    fun `withdraw from a terminal state is refused with no claim and no evidence`() {
        val app = application(OriginationState.DECLINED, decidedBy = "checker-1")
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        assertThatThrownBy {
            service.withdraw(app.id, WithdrawApplicationRequest("no longer needed"), "officer-1")
                .await().indefinitely()
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("terminal")

        verify(exactly = 0) { applications.compareAndSetStatus(any(), any(), any(), any(), any(), any()) }
        assertThat(emitted).isEmpty()
    }

    @Test
    fun `losing the withdraw race refuses and emits nothing`() {
        val app = application(OriginationState.SUBMITTED)
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        every { applications.compareAndSetStatus(any(), any(), any(), any(), any(), any()) } returns
            Uni.createFrom().item(0)

        assertThatThrownBy {
            service.withdraw(app.id, WithdrawApplicationRequest("no longer needed"), "officer-1")
                .await().indefinitely()
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no longer in SUBMITTED")

        assertThat(emitted).isEmpty()
    }

    @Test
    fun `withdrawal reason below the minimum length is refused by the request itself`() {
        assertThatThrownBy { WithdrawApplicationRequest("short") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least")
    }

    // --- submitDocuments ----------------------------------------------------------------------------

    @Test
    fun `submitDocuments moves DOCS_REQUIRED to ASSESSMENT with evidence`() {
        val app = application(OriginationState.DOCS_REQUIRED)
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        val result = service.submitDocuments(app.id, SubmitDocumentsRequest("pack received by mail"), "officer-1")
            .await().indefinitely()

        assertThat(result.status).isEqualTo(OriginationState.ASSESSMENT)
        verify(exactly = 1) {
            applications.compareAndSetStatus(
                app.id,
                OriginationState.DOCS_REQUIRED,
                OriginationState.ASSESSMENT,
                null,
                null,
                null,
            )
        }
        val evidence = evidence("credit.application.transition")
        assertThat(evidence).isNotNull
        assertThat(evidence!!.payload).contains("\"toState\":\"ASSESSMENT\"")
    }

    @Test
    fun `submitDocuments outside DOCS_REQUIRED is refused`() {
        val app = application(OriginationState.KYC_PENDING)
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        assertThatThrownBy {
            service.submitDocuments(app.id, SubmitDocumentsRequest(), "officer-1").await().indefinitely()
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not awaiting document submission")

        verify(exactly = 0) { applications.compareAndSetStatus(any(), any(), any(), any(), any(), any()) }
        assertThat(emitted).isEmpty()
    }

    // --- sign ---------------------------------------------------------------------------------------

    @Test
    fun `sign moves AWAITING_SIGNATURE to SIGNED and persists the artefact hash in the same claim`() {
        val app = application(OriginationState.AWAITING_SIGNATURE, decidedBy = "checker-1")
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        val result = service.sign(app.id, SignApplicationRequest("a".repeat(64)), "customer-signer")
            .await().indefinitely()

        assertThat(result.status).isEqualTo(OriginationState.SIGNED)
        verify(exactly = 1) {
            applications.compareAndSetSignature(
                match { it.status == OriginationState.SIGNED && it.signatureArtefactHash == "a".repeat(64) },
                OriginationState.AWAITING_SIGNATURE,
            )
        }
        verify(exactly = 0) { applications.compareAndSetStatus(any(), any(), any(), any(), any(), any()) }
        val evidence = evidence("credit.application.transition")
        assertThat(evidence).isNotNull
        assertThat(evidence!!.payload).contains("\"toState\":\"SIGNED\"")
    }

    @Test
    fun `sign outside AWAITING_SIGNATURE is refused`() {
        val app = application(OriginationState.OFFERED, decidedBy = "checker-1")
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        assertThatThrownBy {
            service.sign(app.id, SignApplicationRequest("a".repeat(64)), "customer-signer").await().indefinitely()
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not awaiting signature")

        verify(exactly = 0) { applications.compareAndSetSignature(any(), any()) }
        assertThat(emitted).isEmpty()
    }

    @Test
    fun `losing the sign race refuses and emits nothing`() {
        val app = application(OriginationState.AWAITING_SIGNATURE, decidedBy = "checker-1")
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        every { applications.compareAndSetSignature(any(), any()) } returns Uni.createFrom().item(0)

        assertThatThrownBy {
            service.sign(app.id, SignApplicationRequest("a".repeat(64)), "customer-signer").await().indefinitely()
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no longer in AWAITING_SIGNATURE")

        assertThat(emitted).isEmpty()
    }

    // --- four-eyes SLA escalation -------------------------------------------------------------------

    @Test
    fun `escalation on a still-pending four-eyes application records evidence and reports true`() {
        val app = application(OriginationState.FOUR_EYES)
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        val escalated = service.escalateFourEyesSlaIfPending(app.id, "temporal-timer").await().indefinitely()

        assertThat(escalated).isTrue()
        val evidence = evidence("credit.application.four_eyes_sla_escalation")
        assertThat(evidence).isNotNull
        assertThat(evidence!!.payload).contains("\"state\":\"FOUR_EYES\"")
        assertThat(evidence.payload).contains("no auto-approval")
    }

    @Test
    fun `escalation on an already-decided application is a no-op and emits nothing`() {
        val app = application(OriginationState.OFFERED, decidedBy = "checker-1")
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        val escalated = service.escalateFourEyesSlaIfPending(app.id, "temporal-timer").await().indefinitely()

        assertThat(escalated).isFalse()
        assertThat(emitted).isEmpty()
    }

    @Test
    fun `escalation on a missing application fails like every other command`() {
        val id = LoanApplicationId.random()
        every { applications.findById(id) } returns Uni.createFrom().nullItem()

        assertThatThrownBy { service.escalateFourEyesSlaIfPending(id, "temporal-timer").await().indefinitely() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Application not found")
    }
}
