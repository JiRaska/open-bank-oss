// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
import com.openbank.treasury.application.port.out.CounterpartyRepository
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.application.usecase.TreasuryDealService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/** The use case is a plain class (no CDI annotation in the application layer); wired here. */
@ApplicationScoped
class TreasuryServiceProducer {
    @Produces
    @ApplicationScoped
    fun treasuryDealUseCase(
        deals: DealRepository,
        counterparties: CounterpartyRepository,
        ledger: LedgerPostingPort,
        objectMapper: ObjectMapper,
        clock: Clock,
    ): TreasuryDealUseCase = TreasuryDealService(deals, counterparties, ledger, objectMapper, clock)
}
