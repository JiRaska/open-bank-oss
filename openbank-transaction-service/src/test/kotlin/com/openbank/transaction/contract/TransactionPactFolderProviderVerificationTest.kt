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
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
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
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID

/**
 * Git-pact provider verification for transaction-service — the half that actually runs before a
 * merge (issue #2327, gated by `check-pact-provider-replay.py` per #2338).
 *
 * ## Why this class exists at all
 *
 * A consumer pact CANNOT catch a wrong request path: the Pact mock server answers whatever path the
 * client asks for, so a client pointed at a route that does not exist leaves the consumer test
 * green. Only the provider replay goes red — that is how finrep-service shipped a call to a ledger
 * route which has never existed (#2269). Transaction-service is the provider for six committed
 * pacts, and its only verification class was [TransactionPactProviderVerificationTest], which is
 * `@PactBroker`-sourced and `@EnabledIfSystemProperty(pactbroker.url)`-gated. On a pull request that
 * property is empty — `_service-ci.yml` puts the PR lane on `ubuntu-latest` and blanks
 * `PACT_BROKER_URL` off main-push, because the broker has no public ingress (ADR-0056) — so the
 * class skipped and all six contracts were replayed only AFTER the merge.
 *
 * This class reads the same contracts from the committed `pacts/` directory (ADR-0063 git-pact), so
 * it needs no broker, no CI secret and no network, and it runs on every PR.
 *
 * ## Two `@Provider` classes is deliberate here, not the footgun
 *
 * `CLAUDE.md` warns against two verification classes for one provider. That collision is two classes
 * both pulling from the BROKER: each fetches every pact the broker holds, so an HTTP-targeted class
 * also drags in the message pact and dies in its state-change callback. The sanctioned pair — the
 * one openbank-ledger-service already carries — is one `@PactFolder` class plus one `@PactBroker`
 * class, because each loads from its own source. This is that pair. Keep the two in sync: a `@State`
 * or [PactVerifyProvider] added to one belongs in the other, or the same contract passes from git
 * and fails from the broker (or the reverse).
 *
 * ## Both interaction kinds, one class
 *
 * The committed pacts are a mix of HTTP and asynchronous-message interactions — today one message
 * pact (fraud-service's `transaction.initiated`) and HTTP for the rest — so the target is chosen
 * per interaction rather than once for the class, in
 * [configureTarget] exactly as in the broker twin: a [MessageTestTarget] for the fraud-service
 * `transaction.initiated` contract, an [HttpTestTarget] for the rest. The [MessageTestTarget] is
 * package-scoped on purpose — a classpath-wide ClassGraph scan throws on the JDK 25 toolchain.
 *
 * ## When this goes red
 *
 * Either a consumer changed its pact and did not regenerate it (`pact-drift-check.yml` catches that
 * first), or transaction-service genuinely broke a contract — a renamed field, a moved route, a
 * changed status code. Do not "fix" it by relaxing the pact: regenerate from the consumer test and
 * read what changed.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.transaction.it.PostgresRedpandaTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_OPERATOR"])
@Provider("openbank-transaction-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class TransactionPactFolderProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        if (context == null) return
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
        // Shared by domestic-payment, sepa-payment and sepa-instant. No setup needed:
        // initiateTransaction does not require the account to pre-exist in this test's Postgres,
        // only that sourceAccountId parses as a UUID.
    }

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
        // mcp-service's TransactionListPactConsumerTest (#2255, ADR-0195). Intentionally empty — a
        // fresh Testcontainer DB satisfies it by construction. Declared so the state is an explicit
        // part of the contract: pact-jvm passes silently over an UNHANDLED state name, which is how
        // #468's missing states stayed invisible.
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
            // ADR-0084 §3 v4: non-null so the fraud-service consumer pact (which asserts
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
