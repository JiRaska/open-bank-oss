// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.adapter

import com.openbank.lending.application.port.out.BorrowerAccountLookupPort
import com.openbank.lending.application.port.out.BorrowerCreditPort
import com.openbank.lending.application.port.out.CollateralValuationPort
import com.openbank.lending.application.port.out.CreditAssessment
import com.openbank.lending.application.port.out.CreditBureauPort
import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.application.port.out.OriginationWorkflowPort
import com.openbank.lending.application.port.out.RiskParameterSource
import com.openbank.lending.application.port.out.TimerArmingOutcome
import com.openbank.lending.domain.model.Loan
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.EclInputs
import com.openbank.libs.lending.origination.OriginationState
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Default
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Offline-buildable `@Default` no-op bindings for the lending outbound ports (ADR-0028 D3/D4).
 *
 * They let `openbank-lending-service` build and boot with **zero external dependency**. Real
 * integrations land later as build-time-gated `@Alternative @Priority @IfBuildProperty` adapters
 * (the platform realization pattern, ADR-0045), without touching the application service.
 */

/**
 * Offline `@Default` [LedgerPostingPort]: **refuses** the posting rather than reporting a success
 * it did not achieve (#6057).
 *
 * It used to return `Uni<Unit>` — byte-for-byte the same signal [RestLedgerPostingAdapter] returns
 * after ledger-service accepts the journal — and log the discard at `debug`, below the shipped
 * level. That is the `PushResult.skipped()` shape: a disabled adapter sharing a success signal with
 * a working one, with no metric and no error to disagree. Measured consequence: 44 active loans,
 * 6.6M CZK principal, and zero journal lines on every lending GL account.
 *
 * Same reasoning as [NoOpBorrowerAccountLookupPort] two blocks down, which this file already
 * applied to the customer-facing leg and not to the ledger leg: an offline build genuinely cannot
 * write to a general ledger, so it must say so rather than pretend to.
 */
@ApplicationScoped
@Default
class NoOpLedgerPostingPort : LedgerPostingPort {
    private val log = Logger.getLogger(NoOpLedgerPostingPort::class.java)
    override fun post(posting: LedgerPosting): Uni<Unit> {
        log.warnf(
            "ledger backend not configured: REFUSING %s posting ref=%s (no journal written)",
            posting.kind,
            posting.reference,
        )
        return Uni.createFrom().failure(LedgerBackendNotConfiguredException(posting.kind, posting.reference))
    }
}

@ApplicationScoped
@Default
class NoOpCreditBureauPort : CreditBureauPort {
    override fun assess(partyId: UUID, requestedAmount: Money): Uni<CreditAssessment> =
        Uni.createFrom().item(CreditAssessment(score = null, hasAdverseData = false, source = "no-op"))
}

@ApplicationScoped
@Default
class NoOpCollateralValuationPort : CollateralValuationPort {
    /** No external valuer wired: accept the declared market value unchanged. */
    override fun revalue(type: String, declaredValue: Money): Uni<Money> = Uni.createFrom().item(declaredValue)
}

@ApplicationScoped
@Default
class ConservativeRiskParameterSource : RiskParameterSource {
    /** Deliberately conservative flat PD/LGD until a real risk model is bound (ADR-0028 D4). */
    override fun parametersFor(loan: Loan, exposureAtDefault: Money): Uni<EclInputs> = Uni.createFrom().item(
        EclInputs(
            pd12Month = RiskParameterSource.DEFAULT_PD_12M,
            pdLifetime = RiskParameterSource.DEFAULT_PD_LIFETIME,
            lgd = RiskParameterSource.DEFAULT_LGD,
            exposureAtDefault = exposureAtDefault,
        ),
    )
}

@ApplicationScoped
@Default
class LoggingLoanEventEmitter : LoanEventEmitter {
    private val log = Logger.getLogger(LoggingLoanEventEmitter::class.java)

    /** Real adapter writes to `lending_outbox` in the state-change transaction (ADR-0003). */
    override fun emit(message: LendingOutboxMessage): Uni<Unit> {
        log.debugf("no-op outbox emit: %s for %s", message.eventType, message.aggregateId)
        return Uni.createFrom().item(Unit)
    }
}

/**
 * The offline defaults for the disbursement's customer-facing leg (#3931) — gated together under
 * `lending.borrower-credit.backend`, same as the rest of this file. A no-op lookup that always
 * returns null makes [com.openbank.lending.application.usecase.LendingService] take the fail-loud
 * "no account" branch rather than the credit silently claiming success for a payment that never
 * happened: an offline/local build cannot pay a customer, so it must say so, not pretend to.
 */
@ApplicationScoped
@Default
class NoOpBorrowerAccountLookupPort : BorrowerAccountLookupPort {
    override fun findCurrentAccount(partyId: UUID, currency: String): Uni<UUID?> = Uni.createFrom().nullItem()
}

@ApplicationScoped
@Default
class NoOpBorrowerCreditPort : BorrowerCreditPort {
    private val log = Logger.getLogger(NoOpBorrowerCreditPort::class.java)

    // Refuses rather than returning the real client's success value (#6057). Reachable now that
    // the account lookup is not the only fail-loud step: a test or future caller supplying an
    // account id must not get a "paid" answer from an adapter that pays nobody.
    override fun credit(reference: String, borrowerAccountId: UUID, amount: Money): Uni<Unit> {
        log.warnf("borrower-credit backend not configured: REFUSING credit %s ref=%s", amount, reference)
        return Uni.createFrom().failure(BorrowerCreditBackendNotConfiguredException("credit", reference))
    }

    override fun debit(reference: String, borrowerAccountId: UUID, amount: Money): Uni<Unit> {
        log.warnf("borrower-credit backend not configured: REFUSING debit %s ref=%s", amount, reference)
        return Uni.createFrom().failure(BorrowerCreditBackendNotConfiguredException("debit", reference))
    }
}

/**
 * Offline `@Default` [OriginationWorkflowPort]: reports [TimerArmingOutcome.NOT_ARMED_NO_WORKFLOW_BACKEND]
 * rather than the arming success it did not achieve (#6085).
 *
 * It used to return `Uni<Unit>` — the exact value [com.openbank.lending.infrastructure.temporal
 * .TemporalOriginationWorkflowAdapter] returns after the timers workflow is started and signalled —
 * and log the discard at `debug`, below the shipped level. Measured on the deployed image by
 * grepping ArC's generated bytecode inside the running pod: `TemporalOriginationWorkflowAdapter`
 * **0** occurrences, `NoOpOriginationWorkflowPort` **4**, while that pod's environment held
 * `OPENBANK_TEMPORAL_ENABLED=true`. Consequence: no document-SLA, offer-expiry or
 * reflection/cooling-off timer had ever been armed, and nothing disagreed.
 *
 * Unlike [NoOpLedgerPostingPort] this does not *refuse* — see [TimerArmingOutcome] for why a
 * notification must not fail the transition it merely accompanies.
 */
@ApplicationScoped
@Default
class NoOpOriginationWorkflowPort : OriginationWorkflowPort {
    private val log = Logger.getLogger(NoOpOriginationWorkflowPort::class.java)

    override fun stateEntered(
        applicationId: LoanApplicationId,
        state: OriginationState,
        reflectionPeriodDays: Int?,
    ): Uni<TimerArmingOutcome> {
        // Debug is right *here*: an offline build legitimately has no Temporal, and this adapter
        // cannot know whether that is intended. What must not be quiet is the caller, which now
        // receives a distinct outcome instead of the real adapter's success value and warns on it —
        // see LendingService.armTimers. The fix is the SIGNAL, not the log level.
        log.debugf("no-op origination workflow: %s entered %s, no timer armed", applicationId.value, state)
        return Uni.createFrom().item(TimerArmingOutcome.NOT_ARMED_NO_WORKFLOW_BACKEND)
    }
}
