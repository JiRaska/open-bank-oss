// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.`in`.TerminateLoanUseCase
import com.openbank.lending.application.port.out.CollateralRepository
import com.openbank.lending.application.port.out.InstallmentRepository
import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.application.port.out.PostingKind
import com.openbank.lending.domain.model.ForbearanceAssessment
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.lending.domain.model.LoanTerminationPolicy
import com.openbank.lending.infrastructure.compliance.CompliancePackGuard
import com.openbank.lending.infrastructure.persistence.entity.SettlementQuoteEntity
import com.openbank.lending.infrastructure.persistence.repository.SettlementQuoteRepository
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.lending.Amortization
import com.openbank.libs.lending.Delinquency
import com.openbank.libs.lending.Settlement
import com.openbank.libs.lending.SettlementQuote
import com.openbank.libs.lending.compliance.TerminationGround
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

private const val FALLBACK_DPD_THRESHOLD = 90

/**
 * Termination and early-exit lifecycle (ADR-0215). Transitions are guarded by
 * [LoanTerminationPolicy]; every one emits a `credit.loan.transition` evidence event
 * into the outbox in the same transaction (ADR-0214), and collateral is released with
 * the terminal closure (D5). Grounds, notice periods and compensation caps come only
 * from the pinned pack — no pack, no programmatic termination (fail-closed, D5).
 */
@Suppress("LongParameterList", "TooManyFunctions")
@ApplicationScoped
class TerminationService(
    private val loans: LoanRepository,
    private val applications: LoanApplicationRepository,
    private val installments: InstallmentRepository,
    private val collateral: CollateralRepository,
    private val ledger: LedgerPostingPort,
    private val events: LoanEventEmitter,
    private val complianceGuard: CompliancePackGuard,
    private val quotes: SettlementQuoteRepository,
    private val clock: Clock,
) : TerminateLoanUseCase {

    override fun requestSettlementQuote(loanId: LoanId, actor: String): Uni<SettlementQuote> =
        withLoanQuote(loanId) { loan ->
            LoanTerminationPolicy.requireAllowed(loan.status, LoanStatus.EARLY_REPAYMENT_REQUESTED)
            installments.findByLoan(loan.id).flatMap { rows ->
                val paidThrough = rows.filter { it.paid }.maxOfOrNull { it.number } ?: 0
                val schedule = Amortization.schedule(
                    principal = loan.principal,
                    nominalAnnualRate = loan.nominalAnnualRate,
                    termPeriods = loan.termPeriods,
                    periodsPerYear = loan.periodsPerYear,
                    firstDueDate = loan.firstDueDate,
                )
                packFor(loan).flatMap { pack ->
                    val cap = pack?.pack?.earlyRepaymentCompensationCap
                    val quote = Settlement.quote(
                        schedule = schedule,
                        paidThroughInstallment = paidThrough,
                        asOf = LocalDate.now(clock),
                        contractualCompensationRate = cap ?: BigDecimal.ZERO,
                        legalCompensationCap = cap,
                    )
                    val entity = SettlementQuoteEntity().apply {
                        id = com.openbank.libs.domain.identifiers.Ids.newId()
                        this.loanId = loan.id.value
                        asOfDate = quote.asOfDate
                        validUntil = quote.validUntil
                        outstandingPrincipal = quote.outstandingPrincipal.amount
                        accruedInterest = quote.accruedInterest.amount
                        compensation = quote.compensation.amount
                        unappliedCredit = quote.unappliedCredit.amount
                        total = quote.total.amount
                        currency = quote.total.currency.code
                        createdAt = OffsetDateTime.now(clock)
                    }
                    persistQuote(entity).flatMap {
                        transitionLoan(
                            loan,
                            LoanStatus.EARLY_REPAYMENT_REQUESTED,
                            actor,
                            "early repayment requested",
                        )
                    }.flatMap { requested ->
                        transitionLoan(
                            requested,
                            LoanStatus.SETTLEMENT_QUOTED,
                            actor,
                            "settlement quote issued (valid until ${quote.validUntil})",
                        )
                    }.map { quote }
                }
            }
        }

    override fun settle(loanId: LoanId, actor: String): Uni<Loan> = withLoan(loanId) { loan ->
        LoanTerminationPolicy.requireAllowed(loan.status, LoanStatus.SETTLED)
        latestQuote(loan.id).flatMap { quote ->
            requireNotNull(quote) { "No settlement quote for loan ${loan.id.value}" }
            require(quote.settledAt == null) { "Quote ${quote.id} is already settled" }
            require(!LocalDate.now(clock).isAfter(quote.validUntil)) {
                "Settlement quote ${quote.id} expired on ${quote.validUntil} — request a new one (ADR-0215 D2)"
            }
            val currency = quote.currency
            val compensation = com.openbank.libs.domain.money.Money.of(quote.compensation, currency)
            val total = com.openbank.libs.domain.money.Money.of(quote.total, currency)
            val principalPart = total - compensation
            markQuoteSettled(quote).flatMap {
                ledger.post(
                    posting(loan, principalPart, PostingKind.SETTLEMENT, "settle-${quote.id}-principal"),
                )
            }.flatMap {
                if (compensation.isPositive()) {
                    ledger.post(
                        posting(
                            loan,
                            compensation,
                            PostingKind.EARLY_REPAYMENT_COMPENSATION,
                            "settle-${quote.id}-compensation",
                        ),
                    )
                } else {
                    Uni.createFrom().item(Unit)
                }
            }.flatMap {
                transitionLoan(loan, LoanStatus.SETTLED, actor, "settlement received against quote ${quote.id}")
            }.flatMap { settled ->
                transitionLoan(settled, LoanStatus.CLOSED, actor, "loan closed by early settlement")
            }.flatMap { closed ->
                releaseCollateral(closed).map { closed }
            }
        }
    }

    override fun withdraw(loanId: LoanId, actor: String): Uni<Loan> = withLoan(loanId) { loan ->
        LoanTerminationPolicy.requireAllowed(loan.status, LoanStatus.WITHDRAWN)
        packFor(loan).flatMap { pack ->
            val coolingOff = pack?.pack?.coolingOffDays
                ?: error(
                    "no active compliance pack for ${loan.id.value} — withdrawal window unverifiable (fail-closed)",
                )
            val deadline = loan.disbursedAt.toLocalDate().plusDays(coolingOff.toLong())
            require(!LocalDate.now(clock).isAfter(deadline)) {
                "cooling-off window of $coolingOff days closed on $deadline (ADR-0215 D4)"
            }
            installments.findByLoan(loan.id).flatMap { rows ->
                val paidThrough = rows.filter { it.paid }.maxOfOrNull { it.number } ?: 0
                val schedule = Amortization.schedule(
                    principal = loan.principal,
                    nominalAnnualRate = loan.nominalAnnualRate,
                    termPeriods = loan.termPeriods,
                    periodsPerYear = loan.periodsPerYear,
                    firstDueDate = loan.firstDueDate,
                )
                val outstanding = if (paidThrough == 0) {
                    schedule.installments.first().openingBalance
                } else {
                    schedule.balanceAfter(paidThrough)
                }
                val dayInterest = Settlement.withdrawalInterest(
                    outstanding,
                    loan.nominalAnnualRate,
                    loan.disbursedAt.toLocalDate(),
                    LocalDate.now(clock),
                )
                transitionLoan(loan, LoanStatus.WITHDRAWN, actor, "statutory withdrawal within cooling-off")
                    .flatMap { withdrawn ->
                        transitionLoan(withdrawn, LoanStatus.UNWOUND, actor, "contract unwound (ADR-0215 D4)")
                    }.flatMap { unwound ->
                        unwindJournal(unwound, outstanding, dayInterest)
                            .flatMap { emitDomainEvent(unwound, "loan.withdrawn") }
                            .flatMap { releaseCollateral(unwound) }
                            .map { unwound }
                    }
            }
        }
    }

    override fun markDelinquent(loanId: LoanId, actor: String): Uni<Loan> = withLoan(loanId) { loan ->
        LoanTerminationPolicy.requireAllowed(loan.status, LoanStatus.DELINQUENT)
        val dpd = daysPastDue(loan.id)
        require(dpd > 0) { "no past-due installment — cannot mark DELINQUENT" }
        transitionLoan(loan, LoanStatus.DELINQUENT, actor, "oldest unpaid installment is $dpd days past due")
    }

    override fun markDefaulted(loanId: LoanId, actor: String): Uni<Loan> = withLoan(loanId) { loan ->
        LoanTerminationPolicy.requireAllowed(loan.status, LoanStatus.DEFAULTED)
        val dpd = daysPastDue(loan.id)
        packFor(loan).flatMap { pack ->
            val threshold = pack?.pack?.terminationRules?.defaultDpdThreshold ?: FALLBACK_DPD_THRESHOLD
            require(Delinquency.isDefaulted(dpd, threshold)) {
                "DPD $dpd does not cross the CRR Art. 178 threshold of $threshold"
            }
            transitionLoan(loan, LoanStatus.DEFAULTED, actor, "defaulted at $dpd DPD (threshold $threshold)")
        }
    }

    override fun recordForbearance(loanId: LoanId, assessment: ForbearanceAssessment, actor: String): Uni<Loan> =
        withLoan(loanId) { loan ->
            LoanTerminationPolicy.requireAllowed(loan.status, LoanStatus.FORBEARANCE_ASSESSED)
            transitionLoan(
                loan,
                LoanStatus.FORBEARANCE_ASSESSED,
                actor,
                "forbearance assessed: ${assessment.outcome} (${assessment.optionsEvaluated})",
            )
        }

    override fun proposeTermination(loanId: LoanId, ground: String, actor: String): Uni<Loan> =
        withLoan(loanId) { loan ->
            require(loan.status == LoanStatus.FORBEARANCE_ASSESSED) {
                "termination requires a recorded forbearance assessment first: ${loan.status}"
            }
            require(actor.isNotBlank()) { "maker identity is required" }
            packFor(loan).flatMap { pack ->
                val rules = pack?.pack?.terminationRules
                    ?: error(
                        "no active compliance pack — termination grounds unverifiable (fail-closed, ADR-0215 D5)",
                    )
                val permitted = rules.permittedGrounds.map { it.name }
                val groundEnum = TerminationGround.entries.firstOrNull { it.name == ground }
                require(groundEnum != null && groundEnum.name in permitted) {
                    "ground '$ground' is not permitted by the pinned pack (allowed: $permitted)"
                }
                loans.update(
                    loan.copy(
                        terminatedBy = actor,
                        terminatedAt = OffsetDateTime.now(clock),
                    ),
                ).call { _ ->
                    events.emit(loanEvidence(loan, loan.status, loan.status, actor, "termination proposed: $ground"))
                }
            }
        }

    override fun decideTermination(loanId: LoanId, approve: Boolean, actor: String): Uni<Loan> =
        withLoan(loanId) { loan ->
            val maker = loan.terminatedBy
                ?: error("no termination proposal for loan ${loan.id.value}")
            check(actor != maker) {
                "four-eyes violation: termination checker must differ from maker '$maker'"
            }
            if (!approve) {
                loans.update(loan.copy(terminatedBy = null, terminatedAt = null))
            } else {
                packFor(loan).flatMap { pack ->
                    val noticeDays = pack?.pack?.terminationRules?.noticePeriodDays
                        ?: error("no active compliance pack — notice period unknown (fail-closed)")
                    val noticed = loan.copy(noticeEndsOn = LocalDate.now(clock).plusDays(noticeDays.toLong()))
                    LoanTerminationPolicy.requireAllowed(noticed.status, LoanStatus.TERMINATION_NOTICED)
                    transitionLoan(
                        noticed,
                        LoanStatus.TERMINATION_NOTICED,
                        actor,
                        "termination noticed, notice period $noticeDays days",
                    )
                }
            }
        }

    override fun accelerate(loanId: LoanId, actor: String): Uni<Loan> = withLoan(loanId) { loan ->
        LoanTerminationPolicy.requireAllowed(loan.status, LoanStatus.ACCELERATED)
        val noticeEndsOn = loan.noticeEndsOn
            ?: error("no termination notice for loan ${loan.id.value}")
        require(!LocalDate.now(clock).isBefore(noticeEndsOn)) {
            "notice period ends $noticeEndsOn — acceleration is not yet lawful (ADR-0215 D1)"
        }
        transitionLoan(loan, LoanStatus.ACCELERATED, actor, "accelerated after notice elapsed")
            .flatMap { accelerated -> emitDomainEvent(accelerated, "loan.accelerated").map { accelerated } }
    }

    override fun latestQuote(loanId: LoanId): Uni<SettlementQuoteEntity?> = quotes.findLatestUnsettled(loanId.value)

    private fun unwindJournal(
        loan: Loan,
        outstanding: com.openbank.libs.domain.money.Money,
        dayInterest: com.openbank.libs.domain.money.Money,
    ): Uni<Unit> = ledger.post(posting(loan, outstanding, PostingKind.WITHDRAWAL_UNWIND, "withdraw-${loan.id.value}"))
        .flatMap {
            if (dayInterest.isPositive()) {
                ledger.post(
                    posting(loan, dayInterest, PostingKind.INTEREST_ACCRUAL, "withdraw-interest-${loan.id.value}"),
                )
            } else {
                Uni.createFrom().item(Unit)
            }
        }

    // --- helpers -----------------------------------------------------------------------------------

    private fun withLoanQuote(loanId: LoanId, block: (Loan) -> Uni<SettlementQuote>): Uni<SettlementQuote> =
        loans.findById(loanId).flatMap { loan ->
            loan?.let(block) ?: Uni.createFrom().failure(IllegalArgumentException("Loan not found: $loanId"))
        }

    private fun withLoan(loanId: LoanId, block: (Loan) -> Uni<Loan>): Uni<Loan> =
        loans.findById(loanId).flatMap { loan ->
            loan?.let(block) ?: Uni.createFrom().failure(IllegalArgumentException("Loan not found: $loanId"))
        }

    private fun packFor(loan: Loan) = applications.findById(loan.applicationId).map { application ->
        application?.let {
            complianceGuard.resolveOriginationPack(it.jurisdiction, it.productType)
        }
    }

    private fun daysPastDue(loanId: LoanId): Int {
        val rows = installments.findByLoan(loanId).await().indefinitely()
        val oldestUnpaid = rows.filter { !it.paid }.minOfOrNull { it.dueDate }
        return Delinquency.daysPastDue(oldestUnpaid, LocalDate.now(clock))
    }

    private fun transitionLoan(loan: Loan, to: LoanStatus, actor: String, reason: String): Uni<Loan> {
        LoanTerminationPolicy.requireAllowed(loan.status, to)
        return loans.update(loan.copy(status = to))
            .call { updated -> events.emit(loanEvidence(loan, loan.status, to, actor, reason)) }
    }

    private fun loanEvidence(
        loan: Loan,
        from: LoanStatus,
        to: LoanStatus,
        actor: String,
        reason: String,
    ): LendingOutboxMessage {
        val id = loan.id.value
        val payload = buildString {
            append("""{"eventType":"credit.loan.transition",""")
            append(""""aggregateType":"LOAN",""")
            append(""""aggregateId":"$id",""")
            append(""""loanId":"$id",""")
            append(""""partyId":"${loan.partyId}",""")
            append(""""fromState":"${from.name}",""")
            append(""""toState":"${to.name}",""")
            append(""""actorId":"$actor",""")
            append(""""reason":"${reason.replace("\"", "'")}",""")
            append(""""occurredAt":"${clock.instant()}",""")
            append(""""correlationId":"$id",""")
            append(""""sourceService":"lending"}""")
        }
        return LendingOutboxMessage(aggregateId = id, eventType = "credit.loan.transition", payload = payload)
    }

    /**
     * `loan.withdrawn` and `loan.accelerated`, the two event types this helper is parameterised
     * over. Both land on `openbank.lending.events`, which audit-service consumes.
     *
     * Issue #3994/#5256: the payload carries `sourceService` so `AuditConsumer` attributes the row
     * from the producer's own claim ([AttributionSource.EVENT]) rather than falling through to its
     * topic-derived table — a silent, successful default that is only visible by grouping
     * `audit_entries` on a live database. PR #5399 swept this module's other nine event types and
     * missed these two, because they are the only ones built by a shared, parameterised helper
     * rather than by a per-event payload builder.
     *
     * The value is `"lending"`, not `"lending-service"`: every other event type in this module
     * already self-reports `"lending"`, and one producer reporting two different names for itself
     * splits its own attribution. `eventType` is the caller's [type] verbatim — this module's
     * discriminators are dotted lowercase (`loan.withdrawn`), matching the `ce-type` header the
     * outbox already publishes, so it deliberately does NOT take the fleet's SCREAMING_SNAKE form.
     */
    private fun emitDomainEvent(loan: Loan, type: String): Uni<Unit> = events.emit(
        LendingOutboxMessage(
            aggregateId = loan.id.value,
            eventType = type,
            payload = """{"eventType":"$type","aggregateType":"LOAN","aggregateId":"${loan.id.value}",""" +
                """"loanId":"${loan.id.value}","partyId":"${loan.partyId}",""" +
                """"occurredAt":"${clock.instant()}","sourceService":"lending"}""",
        ),
    )

    private fun posting(
        loan: Loan,
        amount: com.openbank.libs.domain.money.Money,
        kind: PostingKind,
        reference: String,
    ) = LedgerPosting(
        partyId = loan.partyId,
        amount = amount,
        kind = kind,
        reference = reference,
    )

    private fun releaseCollateral(loan: Loan): Uni<Unit> = collateral.findByLoan(loan.id).flatMap { items ->
        val now = OffsetDateTime.now(clock)
        val releases = items
            .filter { it.releasedAt == null }
            .map { item -> collateral.update(item.copy(releasedAt = now)).replaceWith(Unit) }
        if (releases.isEmpty()) {
            Uni.createFrom().item(Unit)
        } else {
            Uni.join().all(releases).andFailFast().replaceWith(Unit)
        }
    }

    private fun persistQuote(entity: SettlementQuoteEntity): Uni<SettlementQuoteEntity> = quotes.save(entity)

    private fun markQuoteSettled(quote: SettlementQuoteEntity): Uni<Void> =
        quotes.markSettled(quote.id, OffsetDateTime.now(clock)).replaceWithVoid()
}
