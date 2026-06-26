// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.contract

import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.libs.domain.payment.InstructionType
import com.openbank.libs.domain.payment.PaymentRail
import com.openbank.transaction.domain.event.TransactionInitiatedEvent
import com.openbank.transaction.domain.model.TransactionType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID

/**
 * Provider-side MESSAGE contract verification for `openbank.transactions.transaction.initiated`
 * events produced by transaction-service and consumed by fraud-service (ADR-0092, ADR-0084 §2).
 *
 * Does NOT boot Quarkus — message providers return raw JSON from [PactVerifyProvider] methods,
 * so this is a pure unit-level verification (no DB, no Kafka).
 */
@Provider("openbank-transaction-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class TransactionInitiatedMessagePactProviderTest {

    private val objectMapper = ObjectMapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        findAndRegisterModules()
    }

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = MessageTestTarget()
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPact(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("transaction-service has initiated a payment transaction")
    fun stateTransactionInitiated() {
        // No state setup needed: message producer methods return deterministic payloads.
    }

    @PactVerifyProvider("a transaction.initiated event for fraud screening")
    fun produceTransactionInitiated(): String {
        val event = TransactionInitiatedEvent(
            aggregateId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            version = 1L,
            referenceNumber = "TXN-PACT-001",
            type = TransactionType.DEBIT,
            sourceAccountId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            targetAccountId = null,
            amount = BigDecimal("250.00"),
            currencyCode = "CZK",
            rail = PaymentRail.INTERNAL,
            instructionType = InstructionType.ONE_OFF,
        )
        return objectMapper.writeValueAsString(event)
    }
}
