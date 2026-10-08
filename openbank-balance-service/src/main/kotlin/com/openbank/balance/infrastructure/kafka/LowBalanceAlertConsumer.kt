// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.application.usecase.LowBalanceAlertService
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import java.util.UUID

/** A balance fact wakes an evaluation against the current locked pocket, not the event amount. */
@ApplicationScoped
class LowBalanceAlertConsumer(private val service: LowBalanceAlertService, private val mapper: ObjectMapper) {
    private companion object {
        const val CURRENCY_CODE_LENGTH = 3
    }

    @Incoming("balance-alerts-in")
    suspend fun consume(payload: String) {
        val node = mapper.readTree(payload)
        if (node.path("eventType").asText() != "BALANCE_UPDATED") return
        val accountId = UUID.fromString(node.path("accountId").asText())
        val currency = node.path("currency").asText()
        require(currency.length == CURRENCY_CODE_LENGTH) { "balance event currency is invalid" }
        service.evaluate(accountId, currency)
    }
}
