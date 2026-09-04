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
        // TemporalTestResource provides an in-process Temporal server with NoOpPaymentWorkflow
        // registered to return COMPLETED — no per-interaction setup needed here.
    }

    @State("a valid source account exists")
    fun stateValidSourceAccountExists() {
        // Shared by domestic-payment, sepa-payment, and sepa-instant's consumer pacts (issue
        // #468) — previously unhandled (pact-jvm silently skips a missing state, so this was a
        // live gap, not a failure). No setup needed: initiateTransaction doesn't require the
        // account to pre-exist in this test's Postgres, only that sourceAccountId parses as a UUID.
    }

    /**
     * State for mcp-service's `TransactionListPactConsumerTest` (issue #2255, ADR-0195). No setup:
     * `GET /api/v1/transactions?accountId=` answers 200 with an empty `data` array and a
     * `pagination` envelope for an account that has no rows, and that 200 is the point of the
     * interaction — it proves the listing route exists on the BASE path with `accountId` as a query
     * parameter. A 404 would prove nothing, since Quarkus answers 404 for an absent route too.
     */
    @State("a valid borrower account exists")
    fun stateValidBorrowerAccountExists() {
        // lending-service's BorrowerCreditPactConsumerTest (#8345): the loan disbursement CREDIT,
        // which carries targetAccountId and NO sourceAccountId — hence a state of its own rather
        // than reusing "a valid source account exists", which would be false about this payload.
        // Intentionally empty, for the same reason as the state above: initiateTransaction does not
        // require the account to pre-exist in this test's Postgres, only that the id parses as a
        // UUID. Declared rather than left implicit because pact-jvm passes SILENTLY over an
        // unhandled state name, which is how #468's missing states stayed invisible.
    }

    @State("transaction-service is reachable and holds no transactions for the pact account")
    fun stateNoTransactionsForPactAccount() {
        // Intentionally empty — a fresh Testcontainer DB satisfies it by construction. Declared so
        // the state is an explicit part of the contract; pact-jvm passes silently over an
        // unhandled state name, which is how #468's missing states stayed invisible.
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
            // ADR-0084 §3 v4: non-null so the fraud-service consumer pact (which now asserts
            // targetAccountId is a present UUID) verifies against a realistic payload.
            targetAccountId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
            amount = BigDecimal("250.00"),
            currencyCode = "CZK",
            rail = PaymentRail.INTERNAL,
            instructionType = InstructionType.ONE_OFF,
            occurredAt = java.time.Instant.parse("2026-01-01T00:00:00Z"),
        )
        return objectMapper.writeValueAsString(event)
    }
}
