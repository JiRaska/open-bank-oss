// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.contract

import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.domain.payment.InstructionType
import com.openbank.libs.domain.payment.PaymentRail
import com.openbank.transaction.domain.event.TransactionInitiatedEvent
import com.openbank.transaction.domain.model.TransactionType
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID

/**
 * Provider-side verification for BOTH contract kinds published against transaction-service:
 *  - HTTP: `POST /api/v1/transactions` (account-service consumer, ADR-0063 P2 Batch A)
 *  - MESSAGE: the `transaction.initiated` event for fraud screening (fraud-service consumer,
 *    ADR-0092 / ADR-0084 §2)
 *
 * A single `@Provider` test must verify *every* pact the broker returns for this provider, so the
 * target is chosen per interaction in [configureTarget]: a [MessageTestTarget] for async-message
 * interactions, an [HttpTestTarget] for request/response ones. Splitting these into two classes —
 * each with `@PactBroker` and no interaction-type filter — made the HTTP class also pull the message
 * pact and fail its state-change callback with `UnsupportedOperationException`; one class with a
 * dynamic target is the idiomatic pact-jvm fix.
 *
 * Boots Quarkus so the HTTP target hits the live `POST /api/v1/transactions`; `@TestSecurity`
 * matches the endpoint's `@RolesAllowed(Roles.OPERATOR)`. Message interactions need no endpoint —
 * the [PactVerifyProvider] method returns the wire JSON the provider would emit.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.transaction.it.PostgresRedpandaTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_OPERATOR"])
@Provider("openbank-transaction-service")
@PactBroker(enablePendingPacts = "true")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class TransactionPactProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        if (context == null) return
        // Async-message pacts (fraud-service) verify against the @PactVerifyProvider producer; HTTP
        // pacts (account-service) verify against the live endpoint. The package-scoped
        // MessageTestTarget avoids the classpath-wide ClassGraph scan that throws on JDK 25.
        context.target = if (context.interaction.isAsynchronousMessage()) {
            MessageTestTarget(listOf("com.openbank.transaction.contract"))
        } else {
            HttpTestTarget("localhost", testPort.toInt())
        }
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("the transaction service is available")
    fun stateServiceAvailable() {
        // No DB seeding needed: CREDIT initiation with a new idempotency key always succeeds
        // — the saga validates nothing synchronously before accepting the command.
    }

    @State("transaction-service has initiated a payment transaction")
    fun stateTransactionInitiated() {
        // No setup needed: the message producer below returns a deterministic payload.
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
            occurredAt = java.time.Instant.parse("2026-01-01T00:00:00Z"),
        )
        return objectMapper.writeValueAsString(event)
    }
}
