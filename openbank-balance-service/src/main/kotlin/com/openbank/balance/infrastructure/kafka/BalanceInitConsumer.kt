// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.application.port.`in`.BalanceUseCase
import com.openbank.balance.application.port.`in`.InitializeBalanceCommand
import com.openbank.libs.domain.money.CurrencyCode
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Event-driven balance initialization (ADR-0267 §3).
 *
 * Creates a zero balance row when an account is created, by consuming
 * openbank.accounts.account.created. This replaces the synchronous REST init for the
 * onboarding path: account-service opens onboarding accounts from a Kafka consumer, which
 * has no request-scoped JWT to propagate (the balance REST client fails closed) and runs on
 * the Vert.x event loop (a blocking REST call throws). Consuming the AccountCreated event
 * here side-steps both — no auth needed for an internal event, and the work runs in the
 * consumer's own reactive context.
 *
 * Idempotent: initializeBalance no-ops if a balance already exists, so this co-exists safely
 * with the operator REST path and tolerates at-least-once delivery / replay. Unparseable or
 * incomplete events are skipped; an unsupported currency is dead-lettered (#11604).
 */
@ApplicationScoped
class BalanceInitConsumer(private val balanceUseCase: BalanceUseCase, private val objectMapper: ObjectMapper) {
    private val log = Logger.getLogger(BalanceInitConsumer::class.java)

    @Incoming("balance-init-in")
    suspend fun consume(payload: String) {
        val node: JsonNode = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "Unparseable account.created event, skipping: %s", payload.take(200))
            return
        }
        if (node["eventType"]?.asText() != EVENT_TYPE) return
        val accountId = runCatching { UUID.fromString(node["aggregateId"].asText()) }.getOrNull()
        val currency = node["currency"]?.asText()
        if (accountId == null || currency.isNullOrBlank()) {
            log.warnf("AccountCreated without accountId/currency, skipping: %s", payload.take(200))
            return
        }
        // Kernel currency (#11604): a code that is not ISO 4217 with a minor unit throws
        // InvalidMoneyException, which this method rethrows — the failure-strategy
        // (dead-letter-queue -> openbank.dlq.balance.balance-init-in) parks the record for replay
        // and no balance row is created. Before, any string became a balance pocket. `eur` is EUR.
        val code = CurrencyCode.of(currency.trim())
        balanceUseCase.initializeBalance(InitializeBalanceCommand.zero(accountId, code))
        log.infof("Initialized zero balance for account %s (%s)", accountId, code.code)
    }

    companion object {
        private const val EVENT_TYPE = "AccountCreated"
    }
}
