// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.adapter

import com.openbank.lending.application.port.out.CollateralValuationPort
import com.openbank.lending.application.port.out.CreditAssessment
import com.openbank.lending.application.port.out.CreditBureauPort
import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.application.port.out.OriginationWorkflowPort
import com.openbank.lending.application.port.out.RiskParameterSource
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

@ApplicationScoped
@Default
class NoOpLedgerPostingPort : LedgerPostingPort {
    private val log = Logger.getLogger(NoOpLedgerPostingPort::class.java)
    override fun post(posting: LedgerPosting): Uni<Unit> {
        log.debugf("no-op ledger posting: %s ref=%s", posting.kind, posting.reference)
        return Uni.createFrom().item(Unit)
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

@ApplicationScoped
@Default
class NoOpOriginationWorkflowPort : OriginationWorkflowPort {
    private val log = Logger.getLogger(NoOpOriginationWorkflowPort::class.java)

    override fun stateEntered(
        applicationId: LoanApplicationId,
        state: OriginationState,
        reflectionPeriodDays: Int?,
    ): Uni<Unit> {
        log.debugf("no-op origination workflow: %s entered %s", applicationId.value, state)
        return Uni.createFrom().item(Unit)
    }
}
