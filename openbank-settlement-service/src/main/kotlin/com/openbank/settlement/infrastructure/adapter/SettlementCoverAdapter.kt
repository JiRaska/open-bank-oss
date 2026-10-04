// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.settlement.infrastructure.adapter

import com.openbank.settlement.application.port.out.SettlementCoverPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.SettlementProtocol
import com.openbank.settlement.infrastructure.client.BalanceRestClient
import com.openbank.settlement.infrastructure.client.SettlementCoverRequest
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

@ApplicationScoped
class SettlementCoverAdapter(
    @RestClient private val client: BalanceRestClient,
    private val repository: SettlementRepository,
) : SettlementCoverPort {
    override suspend fun reservePayer(id: UUID) {
        val row = requireNotNull(repository.findById(id)) { "Settlement not found" }
        check(row.protocol == SettlementProtocol.LEDGER_PROJECTION) { "Legacy settlement requires reconciliation" }
        require(row.amount.signum() > 0 && row.payerAccountId != row.payeeAccountId) { "Invalid settlement movement" }
        val held = client.reserve(
            row.payerAccountId,
            SettlementCoverRequest(
                row.amount,
                row.currency,
                "Settlement cover",
                id.toString(),
            ),
        ).subscribeAsCompletionStage().await()
        check(
            held.accountId == row.payerAccountId && held.currency == row.currency && held.referenceId == id.toString(),
        ) {
            "Cover does not identify the settlement payer"
        }
        check(held.amount.compareTo(row.amount) == 0 && held.releasedAt == null && held.expiresAt == null) {
            "Cover must reserve the complete amount until ledger projection consumes it"
        }
    }
}
