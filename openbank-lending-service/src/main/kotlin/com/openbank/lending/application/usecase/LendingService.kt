// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.`in`.AccrueInterestUseCase
import com.openbank.lending.application.port.`in`.ApplyForLoanUseCase
import com.openbank.lending.application.port.`in`.CollateralUseCase
import com.openbank.lending.application.port.`in`.DisburseLoanUseCase
import com.openbank.lending.application.port.`in`.ProvisioningUseCase
import com.openbank.lending.application.port.`in`.RescheduleLoanUseCase
import com.openbank.lending.application.port.`in`.RunProvisioningCycleUseCase
import com.openbank.lending.application.port.`in`.ServicingUseCase
import com.openbank.lending.application.port.`in`.WriteOffLoanUseCase
import com.openbank.lending.application.port.out.CatalogLoanProfile
import com.openbank.lending.application.port.out.CatalogLoanProfilePort
import com.openbank.lending.application.port.out.CollateralRepository
import com.openbank.lending.application.port.out.CollateralValuationPort
import com.openbank.lending.application.port.out.InstallmentRepository
import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.application.port.out.PostingKind
import com.openbank.lending.application.port.out.ProvisioningRepository
import com.openbank.lending.application.port.out.RiskParameterSource
import com.openbank.lending.application.port.out.TimerArmingOutcome
import com.openbank.lending.domain.model.AccrualOutcome
import com.openbank.lending.domain.model.ApplicationStateSummary
import com.openbank.lending.domain.model.Collateral
import com.openbank.lending.domain.model.CollateralDecisionRequest
import com.openbank.lending.domain.model.CollateralRequest
import com.openbank.lending.domain.model.CollateralStatus
import com.openbank.lending.domain.model.DecisionRequest
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanApplicationRequest
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanProvisioningRecord
import com.openbank.lending.domain.model.LoanStateSummary
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.lending.domain.model.ProvisioningRunOutcome
import com.openbank.lending.domain.model.ProvisioningSnapshot
import com.openbank.lending.domain.model.RescheduleRequest
import com.openbank.lending.domain.model.WriteOffRequest
import com.openbank.lending.infrastructure.compliance.OriginationConfig
import com.openbank.libs.domain.identifiers.CollateralId
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.Amortization
import com.openbank.libs.lending.Delinquency
import com.openbank.libs.lending.EclInputs
import com.openbank.libs.lending.Ifrs9
import com.openbank.libs.lending.compliance.CompliancePackEvaluator
import com.openbank.libs.lending.origination.OriginationActorKind
import com.openbank.libs.lending.origination.OriginationAdvance
import com.openbank.libs.lending.origination.OriginationState
import com.openbank.libs.lending.origination.OriginationStateMachine
import com.openbank.libs.lending.origination.OriginationTransition
import com.openbank.libs.lending.origination.OriginationTransitionResult
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The lending bounded-context application service (ADR-0028). Orchestrates the pure `openbank-libs`
 * primitives (`Amortization`, `Ifrs9`, `Delinquency`) over persisted state, emits ledger postings
 * through the outbox, and enforces the four-eyes credit-decision rule server-side. No credit math
 * lives here — it is all in libs.
 */
@ApplicationScoped
// TooManyFunctions: this is the single application service for the whole bounded context
// (ADR-0028), implementing eight cohesive inbound-port interfaces — splitting it would scatter
// one aggregate's orchestration across files for no behavioral benefit.
// LargeClass: the accrued-interest work (#1245) pushed it over the budget. Suppressed rather than
// split in a money-path fix whose point is to be reviewable — an extraction here would bury the
// three-line economic change in a file move. The same call CustomerEdgeResource made. Splitting the
// servicing/provisioning loops out is a real follow-up, not a drive-by.
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
class LendingService @Inject constructor(
    private val applications: LoanApplicationRepository,
    private val loans: LoanRepository,
    private val installments: InstallmentRepository,
    private val collateral: CollateralRepository,
    private val ledger: LedgerPostingPort,
    private val valuation: CollateralValuationPort,
    private val riskParameters: RiskParameterSource,
    private val events: LoanEventEmitter,
    private val clock: Clock,
    private val provisioning: ProvisioningRepository,
    private val complianceGuard: com.openbank.lending.infrastructure.compliance.CompliancePackGuard,
    private val originationConfig: OriginationConfig,
    private val workflowPort: com.openbank.lending.application.port.out.OriginationWorkflowPort,
    private val decisionEngine: OriginationDecisionService,
    private val borrowerAccounts: com.openbank.lending.application.port.out.BorrowerAccountLookupPort,
    private val borrowerCredit: com.openbank.lending.application.port.out.BorrowerCreditPort,
    private val catalogLoanProfiles: CatalogLoanProfilePort = UnusedCatalogLoanProfilePort,
) : ApplyForLoanUseCase,
    DisburseLoanUseCase,
    ServicingUseCase,
    AccrueInterestUseCase,
    WriteOffLoanUseCase,
    RescheduleLoanUseCase,
    CollateralUseCase,
    ProvisioningUseCase,
    RunProvisioningCycleUseCase {

    private val log = Logger.getLogger(LendingService::class.java)

    private val machine = OriginationStateMachine()

    private fun transition(application: LoanApplication, from: OriginationState, to: OriginationState, actor: String) =
        OriginationTransition(
            applicationId = application.id.value.toString(),
            from = from,
            to = to,
            actor = actor,
            actorKind = OriginationActorKind.HUMAN,
            reason = "origination transition $from -> $to",
            occurredAt = clock.instant(),
            packVersion = application.packVersion?.toString(),
        )

    private fun evaluateAndAdvance(
        existing: LoanApplication,
        next: OriginationState,
        actor: String,
    ): Uni<LoanApplication> = decisionEngine.evaluate(existing).flatMap { outcome ->
        val target = if (outcome.declined) OriginationState.DECLINED else next
        when (val result = machine.apply(transition(outcome.recorded, existing.status, target, actor))) {
            is OriginationTransitionResult.Rejected ->
                Uni.createFrom().failure(IllegalStateException(result.reason))
            is OriginationTransitionResult.Applied ->
                claimTransition(existing.status, outcome.recorded.copy(status = result.newState))
                    .signalWorkflow()
                    .call { _ -> events.emit(outcome.evidence) }
        }
    }

    private fun mandatoryStepsFor(application: LoanApplication): Set<OriginationState> =
        complianceGuard.resolveOriginationPack(application.jurisdiction, application.productType)
            ?.let { CompliancePackEvaluator.mandatorySteps(it) }
            ?: emptySet()

    private fun reflectionDaysFor(application: LoanApplication): Int? =
        complianceGuard.resolveOriginationPack(application.jurisdiction, application.productType)
            ?.pack?.reflectionPeriodDays

    /**
     * Claim an origination transition, or refuse.
     *
     * Every caller here has decided [updated] from a snapshot read in an earlier transaction, so it
     * must not write blindly: the row may have moved on since. The conditional UPDATE re-tests the
     * state the decision was taken against, and `0` rows claimed means someone else got there first
     * — the same `IllegalStateException` the caller would have received a moment later, which the
     * resource already maps (422 on advance, 409 on decide). Nothing downstream of a refusal runs:
     * no evidence event, no workflow signal (issue #3850).
     *
     * On success the claimed application is returned from memory. `update` returned the re-read
     * entity, which differs in one respect worth naming: `update` writes only status and the three
     * decision fields, so the ASSESSMENT leg's engine outputs (`decisionOutcome`, price band, reason
     * codes, input hash) were never persisted and the re-read blanked them out of the response. That
     * persistence gap is pre-existing and is NOT fixed here — it needs its own change and its own
     * test. What changes is only that the response now carries the values the engine computed
     * instead of nulls.
     */
    private fun claimTransition(from: OriginationState, updated: LoanApplication): Uni<LoanApplication> =
        applications.compareAndSetStatus(
            updated.id,
            from,
            updated.status,
            updated.decidedBy,
            updated.decisionReason,
            updated.decidedAt,
        ).flatMap { claimed ->
            if (claimed == 0) {
                Uni.createFrom().failure(
                    IllegalStateException(
                        "Concurrent modification: application ${updated.id} is no longer in $from",
                    ),
                )
            } else {
                Uni.createFrom().item(updated)
            }
        }

    private fun Uni<LoanApplication>.signalWorkflow(): Uni<LoanApplication> =
        call { app -> armTimers(app.id, app.status, reflectionDaysFor(app)) }

    /**
     * Reports a state entry to the durable-timer backend and **reads the answer** (#6085).
     *
     * The port used to return `Uni<Unit>` from both implementations, so the offline no-op's
     * discard was indistinguishable from the Temporal adapter's success and no call site could
     * have noticed. [TimerArmingOutcome] gives the two outcomes different values; this is the
     * consumer that acts on the difference. A field nothing reads is a latent trap, not a control.
     *
     * It deliberately does not fail the chain: arming a timer accompanies the transition, it is
     * not the transition, and refusing here would take the whole origination path down in an
     * offline build (ADR-0028 D3). Making a *shipped* image reach this branch is what
     * `LendingAdapterBindingVerifier` prevents, at boot, before any application exists.
     */
    private fun armTimers(
        applicationId: LoanApplicationId,
        state: OriginationState,
        reflectionPeriodDays: Int?,
    ): Uni<TimerArmingOutcome> =
        workflowPort.stateEntered(applicationId, state, reflectionPeriodDays).invoke { outcome ->
            if (outcome != TimerArmingOutcome.ARMED) {
                log.warnf(
                    "application %s entered %s with NO durable timer armed (%s): the document-SLA, " +
                        "offer-expiry and reflection-period waits are unenforced for it.",
                    applicationId.value,
                    state,
                    outcome,
                )
            }
        }

    private fun Uni<LoanApplication>.emitEvidence(
        from: String,
        actor: String,
        actorKind: OriginationActorKind,
        reason: String,
    ): Uni<LoanApplication> = call { app ->
        events.emit(transitionEvidence(app, from, app.status, actor, actorKind, reason))
    }

    /**
     * Canonical credit evidence event (ADR-0214 D1/D2): every origination transition lands in the
     * transactional outbox in the same commit as the state change, in a PII-minimised envelope —
     * identifiers, versions and hashes, never application data. correlationId == applicationId, so
     * the audit chain reconstructs the whole lifecycle from one key.
     */
    private fun transitionEvidence(
        application: LoanApplication,
        from: String,
        to: OriginationState,
        actor: String,
        actorKind: OriginationActorKind,
        reason: String,
    ): LendingOutboxMessage {
        val id = application.id.value
        val payload = buildString {
            append("""{"eventType":"credit.application.transition",""")
            append(""""aggregateType":"LOAN_APPLICATION",""")
            append(""""aggregateId":"$id",""")
            append(""""loanApplicationId":"$id",""")
            append(""""partyId":"${application.partyId}",""")
            append(""""fromState":"$from",""")
            append(""""toState":"${to.name}",""")
            append(""""actorId":"$actor",""")
            append(""""actorKind":"${actorKind.name}",""")
            append(""""reason":"${reason.replace("\"", "'")}",""")
            append(""""packVersion":${application.packVersion ?: "null"},""")
            append(""""occurredAt":"${clock.instant()}",""")
            append(""""correlationId":"$id",""")
            append(""""sourceService":"lending"}""")
        }
        return LendingOutboxMessage(
            aggregateId = id,
            eventType = "credit.application.transition",
            payload = payload,
        )
    }

    // --- Origination --------------------------------------------------------------------------------

    override fun apply(request: LoanApplicationRequest, proposedBy: String): Uni<LoanApplication> =
        request.catalogOfferingId?.let { offeringId ->
            catalogLoanProfiles.resolvePublished(offeringId).flatMap { profile ->
                applyCatalogProfile(request, proposedBy, profile)
            }
        } ?: applyLegacy(request, proposedBy, null)

    private fun applyCatalogProfile(
        request: LoanApplicationRequest,
        proposedBy: String,
        profile: CatalogLoanProfile,
    ): Uni<LoanApplication> {
        require(request.requestedAmount.currency.code == profile.currency) {
            "Requested currency does not match catalog"
        }
        require(request.termPeriods == profile.tenorMonths) { "Requested term does not match catalog" }
        require(request.periodsPerYear == MONTHS_PER_YEAR) { "Catalog loans require monthly periods" }
        require(request.method == profile.method) { "Requested amortization method does not match catalog" }
        require(profile.minPrincipal == null || request.requestedAmount.amount >= profile.minPrincipal) {
            "Requested amount is below the catalog minimum"
        }
        require(profile.maxPrincipal == null || request.requestedAmount.amount <= profile.maxPrincipal) {
            "Requested amount exceeds the catalog maximum"
        }
        return applyLegacy(request.copy(nominalAnnualRate = profile.nominalAnnualRate), proposedBy, profile.snapshot)
    }

    private fun applyLegacy(
        request: LoanApplicationRequest,
        proposedBy: String,
        catalogSnapshot: com.openbank.lending.domain.model.CatalogLoanSnapshot?,
    ): Uni<LoanApplication> {
        complianceGuard.checkOriginationAllowed(request.jurisdiction, request.productType)
        require(request.requestedAmount.isPositive()) { "Requested amount must be positive" }
        require(request.termPeriods > 0) { "Term must be at least one period" }
        require(request.nominalAnnualRate.signum() >= 0) { "Nominal rate cannot be negative" }
        require(proposedBy.isNotBlank()) { "Proposer identity is required" }
        val now = OffsetDateTime.now(clock)
        val pack = complianceGuard.resolveOriginationPack(request.jurisdiction, request.productType)
        val application = LoanApplication(
            partyId = request.partyId,
            requestedAmount = request.requestedAmount,
            nominalAnnualRate = request.nominalAnnualRate,
            termPeriods = request.termPeriods,
            periodsPerYear = request.periodsPerYear,
            method = request.method,
            firstDueDate = request.firstDueDate,
            status = OriginationState.SUBMITTED,
            // Trusted: the authenticated maker, captured by the adapter from the JWT subject.
            proposedBy = proposedBy,
            createdAt = now,
            jurisdiction = request.jurisdiction,
            productType = request.productType,
            packVersion = pack?.pack?.version,
            verifiedIncomeMonthly = request.verifiedIncomeMonthly,
            existingDebtServiceMonthly = request.existingDebtServiceMonthly,
            ageYears = request.ageYears,
            residency = request.residency,
            employmentTenureMonths = request.employmentTenureMonths,
            catalogSnapshot = catalogSnapshot,
        )
        return applications.save(application).call { saved ->
            val state = if (originationConfig.autoApprove) straightThrough(saved).status else saved.status
            armTimers(saved.id, state, reflectionDaysFor(saved))
        }.map { saved ->
            if (originationConfig.autoApprove) straightThrough(saved) else saved
        }.call { saved ->
            events.emit(
                transitionEvidence(
                    application.copy(status = saved.status),
                    "NONE",
                    saved.status,
                    if (originationConfig.autoApprove) OriginationConfig.SANDBOX_ACTOR else proposedBy,
                    OriginationActorKind.HUMAN,
                    "application submitted",
                ),
            )
        }
    }

    private data object UnusedCatalogLoanProfilePort : CatalogLoanProfilePort {
        override fun resolvePublished(offeringId: UUID): Uni<CatalogLoanProfile> =
            Uni.createFrom().failure(IllegalStateException("catalog loan profiles are not configured"))
    }

    /**
     * Sandbox straight-through drive (ADR-0211 D5, never in production): validate the full
     * forward path through the state machine, then persist the terminal STP state once.
     */
    private fun straightThrough(application: LoanApplication): LoanApplication {
        val mandatory = mandatoryStepsFor(application)
        var state = application.status
        while (state != OriginationState.READY_TO_DISBURSE) {
            val next = OriginationAdvance.nextState(state, mandatory)
                ?: return application.copy(status = state)
            val result = machine.apply(transition(application, state, next, OriginationConfig.SANDBOX_ACTOR))
            check(result is OriginationTransitionResult.Applied) {
                "STP drive refused at $state -> $next: ${(result as OriginationTransitionResult.Rejected).reason}"
            }
            state = result.newState
        }
        return application.copy(status = state, decidedBy = OriginationConfig.SANDBOX_ACTOR)
    }

    override fun advance(id: LoanApplicationId, actor: String): Uni<LoanApplication> =
        applications.findById(id).flatMap { existing ->
            when {
                existing == null ->
                    Uni.createFrom().failure(IllegalArgumentException("Application not found: $id"))
                actor.isBlank() ->
                    Uni.createFrom().failure(IllegalArgumentException("Actor identity is required"))
                else -> {
                    val next = OriginationAdvance.nextState(existing.status, mandatoryStepsFor(existing))
                    when {
                        next == null ->
                            Uni.createFrom().failure(
                                IllegalStateException("No forward transition from ${existing.status}"),
                            )
                        existing.status == OriginationState.ASSESSMENT ->
                            evaluateAndAdvance(existing, next, actor)
                        else -> when (
                            val result = machine.apply(transition(existing, existing.status, next, actor))
                        ) {
                            is OriginationTransitionResult.Rejected ->
                                Uni.createFrom().failure(IllegalStateException(result.reason))
                            is OriginationTransitionResult.Applied ->
                                claimTransition(existing.status, existing.copy(status = result.newState))
                                    .signalWorkflow()
                                    .emitEvidence(
                                        existing.status.name,
                                        actor,
                                        OriginationActorKind.HUMAN,
                                        "operator advance",
                                    )
                        }
                    }
                }
            }
        }

    override fun expireIfInState(id: LoanApplicationId, expectedState: String, actor: String): Uni<LoanApplication> =
        applications.findById(id).flatMap { existing ->
            when {
                existing == null ->
                    Uni.createFrom().failure(IllegalArgumentException("Application not found: $id"))
                existing.status.name != expectedState ->
                    Uni.createFrom().item(existing)
                else -> when (
                    val result = machine.apply(
                        transition(existing, existing.status, OriginationState.EXPIRED, actor).copy(
                            actorKind = OriginationActorKind.SYSTEM,
                            reason = "durable timer elapsed in $expectedState (ADR-0211 D2)",
                        ),
                    )
                ) {
                    is OriginationTransitionResult.Rejected ->
                        Uni.createFrom().failure(IllegalStateException(result.reason))
                    is OriginationTransitionResult.Applied ->
                        claimTransition(existing.status, existing.copy(status = result.newState))
                            .signalWorkflow()
                            .emitEvidence(
                                existing.status.name,
                                actor,
                                OriginationActorKind.SYSTEM,
                                "durable timer elapsed",
                            )
                }
            }
        }

    override fun advanceIfInState(id: LoanApplicationId, expectedState: String, actor: String): Uni<LoanApplication> =
        applications.findById(id).flatMap { existing ->
            when {
                existing == null ->
                    Uni.createFrom().failure(IllegalArgumentException("Application not found: $id"))
                existing.status.name != expectedState ->
                    Uni.createFrom().item(existing)
                else -> advance(id, actor)
            }
        }

    override fun decide(id: LoanApplicationId, decision: DecisionRequest, decidedBy: String): Uni<LoanApplication> =
        applications.findById(id).flatMap { existing ->
            when {
                existing == null ->
                    Uni.createFrom().failure(IllegalArgumentException("Application not found: $id"))
                existing.status != OriginationState.FOUR_EYES ->
                    Uni.createFrom().failure(
                        IllegalStateException("Application is not awaiting a four-eyes decision: ${existing.status}"),
                    )
                decidedBy.isBlank() ->
                    Uni.createFrom().failure(IllegalArgumentException("Decider identity is required"))
                // Four-eyes: the decider must not be the proposer. Both identities are the authenticated
                // JWT subject (never client-supplied), so the separation cannot be spoofed (ADR-0028 D5).
                decidedBy == existing.proposedBy ->
                    Uni.createFrom().failure(
                        IllegalStateException("Four-eyes violation: approver must differ from proposer"),
                    )
                else -> {
                    val target = if (decision.approve) OriginationState.OFFERED else OriginationState.DECLINED
                    when (val result = machine.apply(transition(existing, existing.status, target, decidedBy))) {
                        is OriginationTransitionResult.Rejected ->
                            Uni.createFrom().failure(IllegalStateException(result.reason))
                        is OriginationTransitionResult.Applied -> claimTransition(
                            existing.status,
                            existing.copy(
                                status = result.newState,
                                decidedBy = decidedBy,
                                decisionReason = decision.reason,
                                decidedAt = OffsetDateTime.now(clock),
                            ),
                        ).signalWorkflow()
                            .emitEvidence(
                                existing.status.name,
                                decidedBy,
                                OriginationActorKind.HUMAN,
                                decision.reason ?: "four-eyes decision",
                            )
                    }
                }
            }
        }

    override fun getApplication(id: LoanApplicationId): Uni<LoanApplication?> = applications.findById(id)

    override fun listApplications(partyId: UUID): Uni<List<LoanApplication>> = applications.findByParty(partyId)

    override fun listRecentApplications(status: String?, limit: Int): Uni<List<LoanApplication>> =
        applications.findRecent(status, limit.coerceIn(1, MAX_LIST_LIMIT))

    // Deliberately UNCAPPED: the point of a summary is that it is not a page. The result set is
    // bounded by the number of (state, currency) pairs, not by the size of the book.
    override fun summariseApplications(): Uni<List<ApplicationStateSummary>> = applications.summariseByState()

    override fun summariseLoans(): Uni<List<LoanStateSummary>> = loans.summariseByState()

    // --- Disbursement (origination → servicing) -----------------------------------------------------

    override fun disburse(applicationId: LoanApplicationId, disbursedBy: String): Uni<Loan> =
        applications.findById(applicationId).flatMap { application ->
            when {
                application == null ->
                    Uni.createFrom().failure(IllegalArgumentException("Application not found: $applicationId"))
                application.status != OriginationState.READY_TO_DISBURSE ->
                    Uni.createFrom().failure(
                        IllegalStateException(
                            "Only a READY_TO_DISBURSE application can be disbursed: ${application.status}",
                        ),
                    )
                disbursedBy.isBlank() ->
                    Uni.createFrom().failure(IllegalArgumentException("Disburser identity is required"))
                // Segregation of duties: the officer releasing cash must not be the one who approved it
                // (three-eyes over the money-out step, EBA/GL/2020/06). Identities are trusted JWT subjects.
                disbursedBy == application.decidedBy ->
                    Uni.createFrom().failure(
                        IllegalStateException("Segregation of duties: disburser must differ from approver"),
                    )
                else -> bookLoan(application, disbursedBy)
            }
        }

    @Suppress("LongMethod") // ADR-0100: clock-stamp fields push this 3 lines past threshold
    private fun bookLoan(application: LoanApplication, disbursedBy: String): Uni<Loan> {
        val now = OffsetDateTime.now(clock)
        val loan = Loan(
            applicationId = application.id,
            partyId = application.partyId,
            principal = application.requestedAmount,
            nominalAnnualRate = application.nominalAnnualRate,
            termPeriods = application.termPeriods,
            periodsPerYear = application.periodsPerYear,
            method = application.method,
            firstDueDate = application.firstDueDate,
            disbursedAt = now,
            createdAt = now,
        )
        // Generate the contractual schedule from the pure libs primitive.
        val schedule = Amortization.schedule(
            principal = loan.principal,
            nominalAnnualRate = loan.nominalAnnualRate,
            termPeriods = loan.termPeriods,
            firstDueDate = loan.firstDueDate,
            periodsPerYear = loan.periodsPerYear,
            method = loan.method,
        )
        val rows = schedule.installments.map { i ->
            LoanInstallment(
                loanId = loan.id,
                number = i.number,
                dueDate = i.dueDate,
                openingBalance = i.openingBalance,
                principal = i.principal,
                interest = i.interest,
                payment = i.payment,
                closingBalance = i.closingBalance,
            )
        }
        return loans.save(loan)
            .flatMap { saved -> installments.saveAll(rows).map { saved } }
            .flatMap { saved ->
                when (
                    val st = machine.apply(
                        transition(application, application.status, OriginationState.DISBURSED, disbursedBy),
                    )
                ) {
                    is OriginationTransitionResult.Rejected ->
                        Uni.createFrom().failure(IllegalStateException(st.reason))
                    is OriginationTransitionResult.Applied ->
                        // Claimed, not blind-written: without the predicate two concurrent
                        // disbursements of one READY_TO_DISBURSE application both post cash to the
                        // ledger. The claim runs before the posting, so the loser pays nothing.
                        // It does NOT make disbursement atomic — the loan row and its schedule are
                        // written before the claim, so a refused racer still leaves an unreferenced
                        // loan behind. That is pre-existing (today BOTH racers book one) and needs
                        // its own change; see the pull request for #3850.
                        claimTransition(application.status, application.copy(status = st.newState))
                            .call { savedApp ->
                                events.emit(
                                    transitionEvidence(
                                        savedApp,
                                        application.status.name,
                                        st.newState,
                                        disbursedBy,
                                        OriginationActorKind.HUMAN,
                                        "disbursement booked",
                                    ),
                                )
                            }
                            .map { saved }
                }
            }
            .flatMap { saved ->
                // Cash leaves the bank in two bookings, not one. The ledger journal below only ever
                // touches internal GL accounts (Loans Receivable, Funding Clearing — see
                // LendingJournalFactory's KDoc); it records that the bank now holds a loan asset, but
                // it does not, and structurally cannot, move a customer's balance. Without the credit
                // that follows it, the borrower is left owing a loan they were never paid — confirmed
                // live: 44 active loans, 6.6M CZK principal, none of it ever reaching an account (#3931).
                //
                // The credit resolves the borrower's own CURRENT account and asks transaction-service
                // to book it — the same two-step shape account-service already uses for the welcome
                // bonus. It fails loud on either step: a lookup miss or a failed credit surfaces as a
                // failed disbursement rather than a loan silently booked with nowhere for the money to
                // go. (The origination state was already claimed DISBURSED above; making that claim and
                // this credit atomic together is the pre-existing gap #3850 already tracks, not new
                // scope here — a failure past this point needs the same operator attention #3850 does.)
                ledger.post(
                    LedgerPosting(
                        "loan:${saved.id.value}:disbursement",
                        saved.partyId,
                        saved.principal,
                        PostingKind.DISBURSEMENT,
                    ),
                )
                    .flatMap {
                        borrowerAccounts.findCurrentAccount(saved.partyId, saved.principal.currency.code)
                    }
                    .flatMap { accountId ->
                        if (accountId == null) {
                            Uni.createFrom().failure<Unit>(
                                IllegalStateException(
                                    "Loan ${saved.id.value}: party ${saved.partyId} has no active " +
                                        "CURRENT account in ${saved.principal.currency.code} — " +
                                        "disbursement booked to the ledger but the borrower was not paid",
                                ),
                            )
                        } else {
                            borrowerCredit.credit(
                                "loan:${saved.id.value}:disbursement-credit",
                                accountId,
                                saved.principal,
                            )
                        }
                    }
                    .flatMap {
                        events.emit(
                            LendingOutboxMessage(
                                aggregateId = saved.id.value,
                                eventType = "loan.disbursed",
                                // #3914: occurredAt is the loan's own `disbursedAt` — the instant the disbursement
                                // happened on the aggregate — not the serialisation instant.
                                // Issue #3994/#5256: sourceService is the strongest (EVENT-sourced) attribution
                                // AuditConsumer.resolveSourceService reads. EventAttribution.TopicAttribution
                                // already maps openbank.lending.events -> lending-service correctly (TOPIC-sourced),
                                // and audit-service subscribes to this topic today (openbank-audit-service's
                                // application.yaml consumed-topics list), so this is a live attribution upgrade.
                                // Value matches the "lending" literal already used by the 3 previously-fixed
                                // event types in this file/OriginationDecisionService/TerminationService — not
                                // "lending-service" as the topic-fallback table would say (a pre-existing,
                                // self-consistent naming choice this PR preserves rather than introduces).
                                payload = """{"aggregateType":"LOAN","aggregateId":"${saved.id.value}",""" +
                                    """"loanId":"${saved.id.value}","partyId":"${saved.partyId}",""" +
                                    """"principal":"${saved.principal}",""" +
                                    """"occurredAt":"${saved.disbursedAt.toInstant()}",""" +
                                    """"sourceService":"lending"}""",
                            ),
                        )
                    }
                    .map { saved }
            }
    }

    // --- Servicing ----------------------------------------------------------------------------------

    override fun getLoan(id: LoanId): Uni<Loan?> = loans.findById(id)

    override fun getSchedule(id: LoanId): Uni<List<LoanInstallment>> = installments.findByLoan(id)

    override fun listLoans(partyId: UUID): Uni<List<Loan>> = loans.findByParty(partyId)

    override fun listActiveLoans(limit: Int): Uni<List<Loan>> = loans.findActive(limit.coerceIn(1, MAX_LIST_LIMIT))

    override fun recordRepayment(loanId: LoanId, installmentId: UUID): Uni<LoanInstallment> =
        loans.findById(loanId).flatMap { loan ->
            when {
                loan == null ->
                    Uni.createFrom().failure(IllegalArgumentException("Loan not found: $loanId"))
                // A WRITTEN_OFF loan's installments still read paid=false / interestAccrued=true —
                // writeOff derecognizes on the ledger and never mutates the rows. Without this guard a
                // recovery payment posts PRINCIPAL_REPAYMENT against an asset already credited off by
                // WRITE_OFF, and INTEREST_SETTLEMENT against a receivable already credited off by
                // WRITE_OFF_INTEREST — driving both GLs negative. The idempotency keys differ, so the
                // ledger cannot collapse them.
                //
                // A recovery on a written-off loan is NOT a repayment: the asset is gone, so there is
                // nothing to reduce. It is Dr Funding Clearing / Cr Loan Loss Expense (recovery income)
                // and needs its own use case — refused here rather than silently mis-booked (#1245).
                loan.status == LoanStatus.WRITTEN_OFF ->
                    Uni.createFrom().failure(
                        IllegalStateException(
                            "Loan $loanId is WRITTEN_OFF: a recovery is not a repayment and must be " +
                                "booked as recovery income, not against a derecognized asset",
                        ),
                    )
                else -> recordRepaymentAgainst(loanId, installmentId)
            }
        }

    private fun recordRepaymentAgainst(loanId: LoanId, installmentId: UUID): Uni<LoanInstallment> =
        installments.findByLoan(loanId).flatMap { schedule ->
            val target = schedule.firstOrNull { it.id == installmentId }
            when {
                target == null ->
                    Uni.createFrom().failure(IllegalArgumentException("Installment not found on loan $loanId"))
                target.paid ->
                    Uni.createFrom().failure(IllegalStateException("Installment already paid"))
                else -> {
                    val paidAt = OffsetDateTime.now(clock)
                    installments.markPaid(installmentId, paidAt)
                        .flatMap {
                            // Split posting: principal repayment + the interest leg. If the scheduled pass already
                            // accrued this installment's interest income, the cash only *settles* the receivable
                            // (INTEREST_SETTLEMENT); otherwise it is repaid early and we recognize income directly
                            // (INTEREST). Either way interest income is booked exactly once.
                            val interestKind =
                                if (target.interestAccrued) PostingKind.INTEREST_SETTLEMENT else PostingKind.INTEREST
                            ledger.post(
                                LedgerPosting(
                                    "loan:$loanId:inst:${target.number}:principal",
                                    target.loanId.value,
                                    target.principal,
                                    PostingKind.PRINCIPAL_REPAYMENT,
                                ),
                            )
                                .flatMap {
                                    ledger.post(
                                        LedgerPosting(
                                            "loan:$loanId:inst:${target.number}:interest",
                                            target.loanId.value,
                                            target.interest,
                                            interestKind,
                                        ),
                                    )
                                }
                                .map { target.copy(paid = true, paidAt = paidAt) }
                        }
                }
            }
        }

    // --- Servicing posting loop: accrual-basis interest recognition ----------------------------------

    /**
     * Recognize interest income for every installment that has fallen due but is not yet accrued
     * (IAS 1 accrual basis), booking each to the ledger as an accrual against a receivable. Idempotent:
     * the `interestAccrued` flag is set per row, so a re-run never double-recognizes. Repayment later
     * settles the receivable rather than re-recognizing income (see [recordRepayment]).
     */
    override fun accrueDueInterest(asOf: LocalDate, limit: Int): Uni<AccrualOutcome> =
        installments.findAccruable(asOf, limit).flatMap { due ->
            Multi.createFrom().iterable(due)
                .onItem().transformToUniAndConcatenate { installment -> accrueOne(installment) }
                .collect().asList()
                .map { AccrualOutcome(asOf = asOf, installmentsAccrued = it.size) }
        }

    private fun accrueOne(installment: LoanInstallment): Uni<LoanInstallment> {
        // A zero-interest row (e.g. a pure-principal or bullet leg) has nothing to recognize: flag it so
        // the pass does not revisit it, but emit no ledger posting. Build the mark-only Uni lazily in this
        // branch — constructing it eagerly would invoke the repository even on the interest-bearing path.
        val accruedAt = OffsetDateTime.now(clock)
        if (!installment.interest.isPositive()) {
            return installments.markAccrued(installment.id, accruedAt).map { installment }
        }
        return ledger.post(
            LedgerPosting(
                "loan:${installment.loanId.value}:inst:${installment.number}:accrual",
                installment.loanId.value,
                installment.interest,
                PostingKind.INTEREST_ACCRUAL,
            ),
        )
            .flatMap { installments.markAccrued(installment.id, accruedAt) }
            .flatMap {
                events.emit(
                    LendingOutboxMessage(
                        aggregateId = installment.loanId.value,
                        eventType = "loan.interest_accrued",
                        // #3914: occurredAt is `accruedAt`, the very instant stamped on the installment by
                        // markAccrued above — the recognition event itself, not the emit.
                        // Issue #3994/#5256: see the loan.disbursed sourceService comment above.
                        payload = """{"aggregateType":"LOAN","aggregateId":"${installment.loanId.value}",""" +
                            """"loanId":"${installment.loanId.value}","installment":${installment.number},""" +
                            """"interest":"${installment.interest}","dueDate":"${installment.dueDate}",""" +
                            """"occurredAt":"${accruedAt.toInstant()}",""" +
                            """"sourceService":"lending"}""",
                    ),
                )
            }
            .map { installment }
    }

    // --- Write-off (collections terminal step, IFRS 9 Stage 3 → derecognition) -----------------------

    override fun writeOff(loanId: LoanId, request: WriteOffRequest): Uni<Loan> =
        loans.findById(loanId).flatMap { loan ->
            when {
                loan == null ->
                    Uni.createFrom().failure(IllegalArgumentException("Loan not found: $loanId"))
                loan.status != LoanStatus.ACTIVE ->
                    Uni.createFrom().failure(
                        IllegalStateException("Only an ACTIVE loan can be written off: ${loan.status}"),
                    )
                else -> installments.findByLoan(loanId).flatMap { schedule ->
                    val outstanding = outstandingBalance(loan, schedule)
                    if (!outstanding.isPositive()) {
                        Uni.createFrom().failure(
                            IllegalStateException("Nothing to write off: outstanding balance is zero"),
                        )
                    } else {
                        // Book the loss and remove the asset from the books; we never mutate balances ourselves.
                        // Ledger first, row mutation last (as accrueOne does — NOT recordRepayment, which
                        // marks the row paid before posting): a crash between them leaves the loan ACTIVE and
                        // the retry replays the same idempotency keys, which the ledger collapses.
                        ledger.post(
                            LedgerPosting(
                                "loan:${loanId.value}:writeoff",
                                loan.partyId,
                                outstanding,
                                PostingKind.WRITE_OFF,
                            ),
                        )
                            .flatMap { derecognizeAccruedInterest(loan, schedule) }
                            .flatMap { loans.update(loan.copy(status = LoanStatus.WRITTEN_OFF)) }
                            .flatMap { written ->
                                // #3914: Loan carries no writtenOffAt column, so the derecognition instant is read
                                // from the clock at the point the write-off completes — the house convention
                                // already used by TerminationService and OriginationDecisionService. Emitted
                                // once into a local so payload and any future reuse cannot disagree.
                                val writtenOffAt = clock.instant()
                                // Issue #3994/#5256: see the loan.disbursed sourceService comment above.
                                val wPayload = """{"aggregateType":"LOAN","aggregateId":"${written.id.value}",""" +
                                    """"loanId":"${written.id.value}",""" +
                                    """"partyId":"${written.partyId}",""" +
                                    """"writtenOff":"$outstanding",""" +
                                    """"writtenOffBy":"${request.writtenOffBy}",""" +
                                    """"occurredAt":"$writtenOffAt",""" +
                                    """"sourceService":"lending"}"""
                                events.emit(
                                    LendingOutboxMessage(
                                        aggregateId = written.id.value,
                                        eventType = "loan.written_off",
                                        payload = wPayload,
                                    ),
                                ).map { written }
                            }
                    }
                }
            }
        }

    /**
     * Derecognize the loan's accrued-but-unpaid interest receivable at write-off. Every unpaid
     * installment the servicing pass already accrued has an Interest Receivable balance the principal-only
     * [PostingKind.WRITE_OFF] never touches and would otherwise orphan on the books forever.
     *
     * The income stays recognized: an installment can only be accrued once it has fallen due
     * (`findAccruable` gates on `dueDate <= asOf`), so it was genuinely earned — what failed is
     * collection, and an uncollectible receivable is a credit loss, not a revenue reversal.
     *
     * ONE POSTING PER INSTALLMENT, deliberately. An aggregate `loan:<id>:writeoff:interest` key over a
     * summed amount looks simpler and is unsafe: the loan is still ACTIVE until `loans.update` runs, so
     * `findAccruable` can accrue another installment between a crash and the retry. The retry would then
     * recompute a LARGER sum under the SAME key, and the ledger — which dedupes on the key without
     * comparing payloads — would silently return the smaller original journal, stranding the difference
     * on the GL forever. Per-installment keys make a newly-accrued row post its own journal and the
     * already-handled ones collapse, which is correct under any interleaving (#1245).
     */
    private fun derecognizeAccruedInterest(loan: Loan, schedule: List<LoanInstallment>): Uni<Unit> {
        val accruedUnpaid = schedule.filter { !it.paid && it.interestAccrued && it.interest.isPositive() }
        if (accruedUnpaid.isEmpty()) {
            return Uni.createFrom().item(Unit)
        }
        return Multi.createFrom().iterable(accruedUnpaid)
            .onItem().transformToUniAndConcatenate { installment ->
                ledger.post(
                    LedgerPosting(
                        "loan:${loan.id.value}:inst:${installment.number}:writeoff-interest",
                        loan.partyId,
                        installment.interest,
                        PostingKind.WRITE_OFF_INTEREST,
                    ),
                )
            }
            .collect().asList()
            .replaceWith(Unit)
    }

    // --- Reschedule / restructuring (forbearance, issue #667/#668) ----------------------------------

    /**
     * Replace an ACTIVE loan's remaining unpaid schedule with a new contractual repayment plan. The
     * new schedule is generated from the outstanding balance (net of any [RescheduleRequest.principalForgiveness])
     * at the new rate/term/first-due-date, via the same pure [Amortization.schedule] primitive
     * [bookLoan] uses at origination. Already-paid installments are untouched; new rows continue the
     * installment numbering after the last paid one, so a future repayment's ledger reference
     * (`"loan:<id>:inst:<number>:..."`) can never collide with an already-posted one.
     */
    @Suppress("LongMethod") // reschedule mirrors bookLoan's shape: validation + schedule build + persist + post
    override fun reschedule(loanId: LoanId, request: RescheduleRequest, rescheduledBy: String): Uni<Loan> =
        loans.findById(loanId).flatMap { loan ->
            when {
                loan == null ->
                    Uni.createFrom().failure(IllegalArgumentException("Loan not found: $loanId"))
                loan.status != LoanStatus.ACTIVE ->
                    Uni.createFrom().failure(
                        IllegalStateException("Only an ACTIVE loan can be rescheduled: ${loan.status}"),
                    )
                rescheduledBy.isBlank() ->
                    Uni.createFrom().failure(IllegalArgumentException("Rescheduler identity is required"))
                request.newTermPeriods <= 0 ->
                    Uni.createFrom().failure(IllegalArgumentException("New term must be at least one period"))
                request.newNominalAnnualRate.signum() < 0 ->
                    Uni.createFrom().failure(IllegalArgumentException("New nominal rate cannot be negative"))
                request.principalForgiveness.amount.signum() < 0 ->
                    Uni.createFrom().failure(IllegalArgumentException("Principal forgiveness cannot be negative"))
                else -> installments.findByLoan(loanId).flatMap { schedule ->
                    rescheduleAgainst(loan, schedule, request)
                }
            }
        }

    private fun rescheduleAgainst(loan: Loan, schedule: List<LoanInstallment>, request: RescheduleRequest): Uni<Loan> {
        val outstanding = outstandingBalance(loan, schedule)
        if (!outstanding.isPositive()) {
            return Uni.createFrom().failure(IllegalStateException("Nothing to reschedule: outstanding balance is zero"))
        }
        // A newFirstDueDate on or before an already-accrued installment's dueDate re-charges a period
        // that was already recognized: the old accrual (now capitalized into newPrincipal) AND the new
        // schedule's first installment both cover it. Nothing rejected this before — it double-charged
        // on the pre-capitalization code too, just by a smaller amount. Forbearance moves due dates
        // FORWARD; a backdated one is a data error, not a business case.
        val lastAccruedDue = schedule.filter { !it.paid && it.interestAccrued }.maxOfOrNull { it.dueDate }
        if (lastAccruedDue != null && !request.newFirstDueDate.isAfter(lastAccruedDue)) {
            return Uni.createFrom().failure(
                IllegalArgumentException(
                    "newFirstDueDate ${request.newFirstDueDate} must be after the last accrued " +
                        "installment's dueDate $lastAccruedDue — interest for that period is already " +
                        "recognized and would be charged twice",
                ),
            )
        }
        if (request.principalForgiveness.amount > outstanding.amount) {
            return Uni.createFrom().failure(
                IllegalArgumentException(
                    "Forgiveness ${request.principalForgiveness} exceeds outstanding balance $outstanding",
                ),
            )
        }
        // Accrued-but-unpaid interest on the installments this reschedule is about to discard. Each such
        // row already posted an INTEREST_ACCRUAL whose INTEREST_SETTLEMENT can now never happen, so the
        // receivable would be stranded on the GL forever (#1245 / audit N-1). It is CAPITALIZED into the
        // restructured principal, not reversed — see [capitalizeAccruedUnpaidInterest].
        val capitalized = accruedUnpaidInterest(loan, schedule)
        val newPrincipal = outstanding.plus(capitalized).minus(request.principalForgiveness)
        // A monotonically-increasing, DURABLY PERSISTED generation number, not just loan.version + 1
        // computed-and-discarded — the idempotency key below must never repeat across two separate
        // reschedules of the same loan, or the second one's forgiveness would replay the first one's
        // journal instead of posting its own (the ledger dedupes on this exact string).
        val generation = loan.version + 1
        val forgive = if (request.principalForgiveness.isPositive()) {
            ledger.post(
                LedgerPosting(
                    "loan:${loan.id.value}:reschedule:$generation:forgiveness",
                    loan.partyId,
                    request.principalForgiveness,
                    PostingKind.RESCHEDULE_FORGIVENESS,
                ),
            )
        } else {
            Uni.createFrom().item(Unit)
        }
        return forgive
            .flatMap { capitalizeAccruedUnpaidInterest(loan, schedule) }
            .flatMap { buildAndPersistNewSchedule(loan, schedule, newPrincipal, generation, request) }
    }

    /** Accrued-but-unpaid interest on the installments a reschedule will discard. */
    private fun accruedUnpaidInterest(loan: Loan, schedule: List<LoanInstallment>): Money = schedule
        .filter { !it.paid && it.interestAccrued }
        .fold(Money.zero(loan.principal.currency.code)) { acc, i -> acc.plus(i.interest) }

    /**
     * Roll the accrued-but-unpaid interest of every installment this reschedule discards into the
     * restructured principal: `Dr Loans Receivable / Cr Interest Receivable`, one posting per row.
     *
     * WHY CAPITALIZE AND NOT REVERSE. `interestAccrued` has exactly one writer — `markAccrued`, called
     * only from [accrueOne], fed only by `findAccruable` (`WHERE dueDate <= :asOf`). So every row here
     * has ALREADY FALLEN DUE and its interest was genuinely earned. Reversing the accrual
     * (Dr Interest Income) would un-earn it: the borrower would owe nothing for a period they held the
     * money, no relief would be booked against Loan Loss Expense, and `loan.rescheduled` would report
     * `principalForgiveness: 0.00` while real relief had been granted — debt forgiveness as a silent
     * side effect, bypassing the one mechanism ADR-0028 gives relief: an explicit
     * [PostingKind.RESCHEDULE_FORGIVENESS]. (ADR-0028 does not *say* relief may happen only that way —
     * it is silent on accrued interest at reschedule, which is why this bug existed. The argument above
     * stands on its own; the ADR merely shows relief is meant to be explicit and auditable.) This is
     * also the treatment [derecognizeAccruedInterest] applies on write-off, for the identical fact
     * pattern.
     *
     * The "new schedule re-accrues the same income twice" worry does not apply *given a sane
     * `newFirstDueDate`: [Amortization.schedule] charges `opening × periodRate` from `newFirstDueDate`
     * forward, so a first period starting after the last accrued due date cannot re-charge it. That
     * precondition is enforced in [rescheduleAgainst] — without it, `newFirstDueDate` on or before an
     * accrued installment's `dueDate` double-charges that period (both here and, historically, on the
     * pre-capitalization code).
     *
     * Caveat worth knowing: "fell due therefore earned" is this service's *current* recognition model,
     * not the IFRS 9 test. Under IFRS 9 5.4.1(b) a credit-impaired (Stage 3) asset accrues on the NET
     * carrying amount, not gross — and a delinquent loan being forborne is very likely Stage 3. That
     * indicts [accrueOne]'s gross accrual, not this function: given the accrual happened at gross,
     * capitalizing is the internally consistent exit.
     *
     * Ledger first, row deletion last: a crash between them leaves the old rows in place and the retry
     * replays the same per-installment keys, which the ledger collapses.
     */
    private fun capitalizeAccruedUnpaidInterest(loan: Loan, schedule: List<LoanInstallment>): Uni<Unit> {
        // Zero-interest rows are flagged accrued without ever posting (see accrueOne): nothing to move.
        val accruedUnpaid = schedule.filter { !it.paid && it.interestAccrued && it.interest.isPositive() }
        if (accruedUnpaid.isEmpty()) {
            return Uni.createFrom().item(Unit)
        }
        return Multi.createFrom().iterable(accruedUnpaid)
            .onItem().transformToUniAndConcatenate { installment ->
                ledger.post(
                    LedgerPosting(
                        "loan:${loan.id.value}:inst:${installment.number}:capitalization",
                        loan.partyId,
                        installment.interest,
                        PostingKind.INTEREST_CAPITALIZATION,
                    ),
                )
            }
            .collect().asList()
            .replaceWith(Unit)
    }

    private fun buildAndPersistNewSchedule(
        loan: Loan,
        schedule: List<LoanInstallment>,
        newPrincipal: Money,
        generation: Long,
        request: RescheduleRequest,
    ): Uni<Loan> {
        // Continue numbering after the HIGHEST existing number, not the paid count. Two bugs, both live
        // before this (#1245), both silent:
        //   * `paidCount` recycles a discarded unpaid row's number. That row already posted
        //     "loan:<id>:inst:<n>:accrual", and deleteUnpaid frees the number so UNIQUE(loan_id, number)
        //     does not catch it — so the replacement row's own accrual collapses into the discarded row's
        //     journal and the income is NEVER POSTED.
        //   * recordRepayment pays by installment id with no ordering constraint, so the paid set need not
        //     be a prefix: pay only #5 of 12 and `paidCount` = 1 makes the new row #5 collide with the
        //     SURVIVING paid row #5 — a hard UNIQUE violation.
        // maxOfOrNull runs over the full schedule (paid + about-to-be-deleted), and surviving paid rows
        // always hold numbers below the new block, so numbering is strictly monotonic across generations.
        val lastNumber = schedule.maxOfOrNull { it.number } ?: 0
        val newSchedule = Amortization.schedule(
            principal = newPrincipal,
            nominalAnnualRate = request.newNominalAnnualRate,
            termPeriods = request.newTermPeriods,
            firstDueDate = request.newFirstDueDate,
            periodsPerYear = loan.periodsPerYear,
            method = loan.method,
        )
        val rows = newSchedule.installments.map { i ->
            LoanInstallment(
                loanId = loan.id,
                number = lastNumber + i.number,
                dueDate = i.dueDate,
                openingBalance = i.openingBalance,
                principal = i.principal,
                interest = i.interest,
                payment = i.payment,
                closingBalance = i.closingBalance,
            )
        }
        return installments.deleteUnpaid(loan.id)
            .flatMap { installments.saveAll(rows) }
            // Persist the bumped version — the durable half of the idempotency key computed above.
            .flatMap { loans.update(loan.copy(version = generation)) }
            .flatMap { updated ->
                events.emit(
                    LendingOutboxMessage(
                        aggregateId = updated.id.value,
                        eventType = "loan.rescheduled",
                        // #3914: no rescheduledAt column on Loan; clock at the completed reschedule, same
                        // house convention as write-off above.
                        // Issue #3994/#5256: see the loan.disbursed sourceService comment above.
                        payload = """{"aggregateType":"LOAN","aggregateId":"${updated.id.value}",""" +
                            """"loanId":"${updated.id.value}","partyId":"${updated.partyId}",""" +
                            """"newPrincipal":"$newPrincipal",""" +
                            """"newNominalAnnualRate":"${request.newNominalAnnualRate}",""" +
                            """"newTermPeriods":${request.newTermPeriods},""" +
                            """"principalForgiveness":"${request.principalForgiveness}",""" +
                            """"occurredAt":"${clock.instant()}",""" +
                            """"sourceService":"lending"}""",
                    ),
                ).map { updated }
            }
    }

    // --- Collateral (four-eyes, ADR-0028 follow-up issue #621) --------------------------------------

    override fun register(loanId: LoanId, request: CollateralRequest, registeredBy: String): Uni<Collateral> {
        require(request.haircut.signum() >= 0 && request.haircut <= BigDecimal.ONE) {
            "Haircut must be within [0,1]: ${request.haircut}"
        }
        require(registeredBy.isNotBlank()) { "Registrant identity is required" }
        val now = OffsetDateTime.now(clock)
        return valuation.revalue(request.type.name, request.marketValue).flatMap { valued ->
            collateral.save(
                Collateral(
                    loanId = loanId,
                    type = request.type,
                    description = request.description,
                    marketValue = valued,
                    haircut = request.haircut,
                    valuedAt = now,
                    // Four-eyes: registration alone does not make the collateral usable to reduce a
                    // loan's LGD — see applyCollateral, which only sums APPROVED items.
                    status = CollateralStatus.PENDING,
                    registeredBy = registeredBy,
                    createdAt = now,
                ),
            )
        }
    }

    override fun decide(id: CollateralId, decision: CollateralDecisionRequest, decidedBy: String): Uni<Collateral> =
        collateral.findById(id).flatMap { existing ->
            when {
                existing == null ->
                    Uni.createFrom().failure(IllegalArgumentException("Collateral not found: $id"))
                existing.status != CollateralStatus.PENDING ->
                    Uni.createFrom().failure(
                        IllegalStateException("Collateral is not awaiting a decision: ${existing.status}"),
                    )
                decidedBy.isBlank() ->
                    Uni.createFrom().failure(IllegalArgumentException("Decider identity is required"))
                // Four-eyes: the decider must not be the maker who registered it. Both identities are the
                // authenticated JWT subject (never client-supplied), so this cannot be spoofed.
                decidedBy == existing.registeredBy ->
                    Uni.createFrom().failure(
                        IllegalStateException("Four-eyes violation: approver must differ from registrant"),
                    )
                else -> collateral.update(
                    existing.copy(
                        status = if (decision.approve) CollateralStatus.APPROVED else CollateralStatus.REJECTED,
                        decidedBy = decidedBy,
                        decidedAt = OffsetDateTime.now(clock),
                    ),
                )
            }
        }

    override fun list(loanId: LoanId): Uni<List<Collateral>> = collateral.findByLoan(loanId)

    // --- Provisioning (IFRS 9) ----------------------------------------------------------------------

    override fun assess(loanId: LoanId, asOf: LocalDate): Uni<ProvisioningSnapshot> =
        loans.findById(loanId).flatMap { loan ->
            if (loan == null) {
                Uni.createFrom().failure(IllegalArgumentException("Loan not found: $loanId"))
            } else {
                snapshotFor(loan, asOf)
            }
        }

    /** The IFRS 9 stage/ECL computation shared by the on-demand [assess] read and the scheduled cycle. */
    private fun snapshotFor(loan: Loan, asOf: LocalDate): Uni<ProvisioningSnapshot> =
        installments.findByLoan(loan.id).flatMap { schedule ->
            val outstanding = outstandingBalance(loan, schedule)
            val oldestUnpaidDue = schedule.filter { !it.paid }.minByOrNull { it.dueDate }?.dueDate
            val dpd = Delinquency.daysPastDue(oldestUnpaidDue, asOf)
            riskParameters.parametersFor(loan, outstanding).flatMap { inputs ->
                collateral.findByLoan(loan.id).map { registered ->
                    val adjustedInputs = applyCollateral(inputs, registered)
                    val ecl = Ifrs9.assess(daysPastDue = dpd, inputs = adjustedInputs)
                    ProvisioningSnapshot(
                        loanId = loan.id,
                        asOf = asOf,
                        outstandingBalance = outstanding,
                        daysPastDue = dpd,
                        bucket = Delinquency.bucket(dpd),
                        stage = ecl.stage,
                        horizon = ecl.horizon,
                        expectedCreditLoss = ecl.expectedCreditLoss,
                        modelVersion = adjustedInputs.modelVersion,
                    )
                }
            }
        }

    /**
     * Collateral-adjusted LGD (ADR-0028 D1, first increment; four-eyes-filtered per the ADR-0028
     * follow-up, issue #621). Sums every **APPROVED** [registered] collateral item's haircut-adjusted
     * value (`marketValue * (1 - haircut)`, all items must share [inputs]' `exposureAtDefault` currency —
     * the loan book is single-currency) and reduces [inputs]' flat LGD by that cover relative to the
     * exposure, via the pure [Ifrs9.collateralAdjustedLgd]. PD is deliberately left untouched by this
     * increment.
     *
     * A [CollateralStatus.PENDING] or [CollateralStatus.REJECTED] item is excluded: a single maker
     * registering an inflated collateral value must not be able to unilaterally lower a loan's
     * provisioning before a different checker approves it (the four-eyes gap this increment closes).
     *
     * A loan with no APPROVED collateral (the common/default case today, and every loan before this
     * increment shipped) takes the empty-after-filter short-circuit and returns [inputs] unchanged —
     * byte-identical to pre-collateral behaviour, no regression for the existing loan book.
     *
     * **First-pass caveats (see [Ifrs9.collateralAdjustedLgd] and the ADR-0028 delivery note):** no
     * real-time revaluation — this reads whatever `marketValue`/`haircut` was last declared/revalued at
     * registration time, which can be stale; no legal perfection-of-security-interest verification — a
     * registered collateral row is a data claim, not a confirmed enforceable priority.
     */
    private fun applyCollateral(inputs: EclInputs, registered: List<Collateral>): EclInputs {
        val approved = registered.filter { it.status == CollateralStatus.APPROVED }
        if (approved.isEmpty()) return inputs
        val currency = inputs.exposureAtDefault.currency
        val haircutAdjustedTotal = approved
            .filter { it.marketValue.currency == currency }
            .fold(BigDecimal.ZERO) { acc, item ->
                acc + item.marketValue.amount.multiply(BigDecimal.ONE - item.haircut)
            }
        val effectiveLgd = Ifrs9.collateralAdjustedLgd(
            lgd = inputs.lgd,
            haircutAdjustedCollateralValue = haircutAdjustedTotal,
            exposureAtDefault = inputs.exposureAtDefault.amount,
        )
        return inputs.copy(lgd = effectiveLgd)
    }

    // --- Provisioning cycle: scheduled IFRS 9 stage/ECL re-bucketing, delta-vs-prior-period posting ---

    /**
     * Re-buckets every ACTIVE loan's IFRS 9 stage/ECL for [period] and posts only the **delta** versus
     * the loan's most recent earlier period to the ledger (mirrors the FX-revaluation delta pattern in
     * `openbank-ledger-service`: a signed movement, zero-delta skipped, never a full re-post). Idempotent
     * per `(loanId, period)` — a loan already provisioned for [period] is left untouched.
     */
    override fun runProvisioningCycle(period: String, asOf: LocalDate, limit: Int): Uni<ProvisioningRunOutcome> =
        loans.findActive(limit).flatMap { active ->
            Multi.createFrom().iterable(active)
                .onItem().transformToUniAndConcatenate { loan -> provisionOne(loan, period, asOf) }
                .collect().asList()
                .map { results ->
                    ProvisioningRunOutcome(
                        period = period,
                        loansAssessed = results.size,
                        journalsPosted = results.count { it },
                    )
                }
        }

    /** Provisions one loan for [period]; returns whether a ledger delta was posted (for the outcome tally). */
    private fun provisionOne(loan: Loan, period: String, asOf: LocalDate): Uni<Boolean> =
        provisioning.findByLoanAndPeriod(loan.id, period).flatMap { already ->
            if (already != null) {
                // Idempotent re-run: this loan is already provisioned for this period — do nothing.
                Uni.createFrom().item(false)
            } else {
                snapshotFor(loan, asOf).flatMap { snapshot -> postProvisioningDelta(loan, period, snapshot) }
            }
        }

    /**
     * #3914: both events emitted here carry `occurredAt` = `record.createdAt`, the instant THIS
     * provisioning cycle ran, which is when the stage transition and the ECL delta were determined.
     * The `asOf` field next to it is the accounting DATE and is a different fact. Without
     * `occurredAt`, AuditConsumer records its own ingest time as the business time.
     */
    private fun postProvisioningDelta(loan: Loan, period: String, snapshot: ProvisioningSnapshot): Uni<Boolean> =
        provisioning.findLatestBefore(loan.id, period).flatMap { prior ->
            val priorEcl = prior?.expectedCreditLoss ?: Money.zero(snapshot.expectedCreditLoss.currency.code)
            val delta = snapshot.expectedCreditLoss.minus(priorEcl)
            val record = LoanProvisioningRecord(
                loanId = loan.id,
                period = period,
                asOf = snapshot.asOf,
                outstandingBalance = snapshot.outstandingBalance,
                daysPastDue = snapshot.daysPastDue,
                bucket = snapshot.bucket,
                stage = snapshot.stage,
                expectedCreditLoss = snapshot.expectedCreditLoss,
                createdAt = OffsetDateTime.now(clock),
                modelVersion = snapshot.modelVersion,
            )
            // A genuine IFRS 9 stage transition (Stage 1/2/3) is a distinct signal from an ECL delta: ECL
            // can move within the same stage (PD/EAD drift), and — first cycle aside — a stage can change
            // with a zero-delta ECL in edge cases. Downstream consumers (e.g. AnaCredit's overdue/stage
            // projection, issue #638) care about the transition itself, not the ledger movement, so this
            // emits independently of whether a provisioning journal posts below.
            val stageChanged = prior != null && prior.stage != snapshot.stage
            val stageEvent = if (stageChanged) {
                events.emit(
                    LendingOutboxMessage(
                        aggregateId = loan.id.value,
                        eventType = "loan.stage_changed",
                        payload = stageChangedPayload(loan, prior!!, snapshot, period, record),
                    ),
                )
            } else {
                Uni.createFrom().item(Unit)
            }
            stageEvent.flatMap {
                if (delta.isZero()) {
                    // ECL unchanged since the last cycle: keep the audit-trail row, post nothing to the
                    // ledger. The stage-changed event (if any) has already been emitted above.
                    provisioning.save(record).map { false }
                } else {
                    ledger.post(
                        LedgerPosting(
                            "loan:${loan.id.value}:provisioning:$period",
                            loan.partyId,
                            delta,
                            PostingKind.PROVISIONING,
                        ),
                    )
                        .flatMap { provisioning.save(record) }
                        .flatMap {
                            events.emit(
                                LendingOutboxMessage(
                                    aggregateId = loan.id.value,
                                    eventType = "loan.provisioned",
                                    payload = provisionedPayload(loan, period, snapshot, delta, record),
                                ),
                            )
                        }
                        .map { true }
                }
            }
        }

    /**
     * partyId added for ADR-0220 D6's arrears exclusion (engagement-service's
     * LendingArrearsEventConsumer) — additive field, existing consumers (anacredit-service's
     * LoanStageEventConsumer) parse via readTree and ignore unknown fields, so this cannot break
     * them. `sourceService` (issue #3994/#5256): see the loan.disbursed sourceService comment above.
     */
    private fun stageChangedPayload(
        loan: Loan,
        prior: LoanProvisioningRecord,
        snapshot: ProvisioningSnapshot,
        period: String,
        record: LoanProvisioningRecord,
    ): String = """{"aggregateType":"LOAN","aggregateId":"${loan.id.value}",""" +
        """"loanId":"${loan.id.value}","partyId":"${loan.partyId}",""" +
        """"previousStage":"${prior.stage}","newStage":"${snapshot.stage}",""" +
        """"daysPastDue":${snapshot.daysPastDue},"period":"$period","asOf":"${snapshot.asOf}",""" +
        """"occurredAt":"${record.createdAt.toInstant()}","sourceService":"lending"}"""

    /** `sourceService` (issue #3994/#5256): see the loan.disbursed sourceService comment above. */
    private fun provisionedPayload(
        loan: Loan,
        period: String,
        snapshot: ProvisioningSnapshot,
        delta: Money,
        record: LoanProvisioningRecord,
    ): String = """{"aggregateType":"LOAN","aggregateId":"${loan.id.value}",""" +
        """"loanId":"${loan.id.value}","partyId":"${loan.partyId}","period":"$period",""" +
        """"stage":"${snapshot.stage}",""" +
        """"expectedCreditLoss":"${snapshot.expectedCreditLoss}",""" +
        """"delta":"$delta","occurredAt":"${record.createdAt.toInstant()}",""" +
        """"sourceService":"lending"}"""

    /** Outstanding principal = opening balance of the first unpaid installment, else fully repaid. */
    private fun outstandingBalance(loan: Loan, schedule: List<LoanInstallment>): Money {
        val firstUnpaid = schedule.filter { !it.paid }.minByOrNull { it.number }
        return firstUnpaid?.openingBalance ?: Money.zero(loan.principal.currency.code)
    }

    private companion object {
        const val MAX_LIST_LIMIT = 100
        const val MONTHS_PER_YEAR = 12
    }
}
