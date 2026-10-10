// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.payments

import com.openbank.pension.application.port.out.PaymentMandatePort
import com.openbank.pension.application.usecase.ContributionService
import com.openbank.pension.application.usecase.ParticipantAccountPort
import com.openbank.pension.application.usecase.PaymentMandateRepository
import com.openbank.pension.application.usecase.PaymentMandateService
import com.openbank.pension.application.usecase.PayoutSettlementRepository
import com.openbank.pension.application.usecase.PayoutSettlementService
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/** CDI wiring for the payment adapters slice (#12378); kept apart from the S3/S5 bean classes. */
@ApplicationScoped
class PaymentBeans {

    @Produces
    @ApplicationScoped
    fun paymentMandateService(
        contributions: ContributionService,
        port: PaymentMandatePort,
        mandates: PaymentMandateRepository,
        accounts: ParticipantAccountPort,
        sca: com.openbank.pension.application.exit.ScaVerificationPort,
        clock: Clock,
    ): PaymentMandateService = PaymentMandateService(contributions, port, mandates, accounts, sca, clock)

    @Produces
    @ApplicationScoped
    fun payoutSettlementService(
        instructions: PayoutSettlementRepository,
        meterRegistry: MeterRegistry,
    ): PayoutSettlementService = PayoutSettlementService(instructions) { outcome ->
        meterRegistry.counter("openbank_pension_payout_payments_settlement_total", "outcome", outcome.name).increment()
    }
}
