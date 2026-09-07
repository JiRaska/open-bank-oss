// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.contract

import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.DelegationProjectionRepository
import com.openbank.account.domain.event.AccountCreatedEvent
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.account.domain.model.DelegatedAccessGrant
import com.openbank.account.it.PostgresRedpandaRedisTestResource
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.money.CurrencyCode
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Provider-side verification for async message contracts published by account-service
 * (ADR-0063 P1+P2 → ADR-0092). Covers:
 * - AccountCreated event (P1) consumed by balance-service
 * - TRANSACTION_COMPLETED notification request (P2 Batch C) consumed by notification-service
 *
 * Unlike the HTTP provider verification, this needs no running Quarkus instance: a
 * [MessageTestTarget] asks the [PactVerifyProvider] method for the message the provider would
 * emit, and Pact checks it against the consumer contract. Messages are built from real domain
 * types and serialized with the same Jackson modules so the contract verifies the real wire shape.
 *
 * `@PactBroker` (not `@PactFolder`) — the same fix #1166 applied to party-service, for the same
 * reason. `_service-ci.yml` publishes every consumer's pacts to the broker on a main push, but
 * #372 (2026-07-07) had switched this class to `@PactFolder("../pacts")`, so nothing ever pulled
 * those pacts BACK OUT of the broker to verify them and publish a result. `can-i-deploy` reads the
 * broker and nothing else, so it permanently saw "no verified pact" for every consumer of
 * account-service and blocked their deploys — confirmed live: notification-service #1180 and #1303
 * both merged clean, built green, then failed the gate with "There is no verified pact between
 * openbank-notification-service and openbank-account-service".
 *
 * `@EnabledIfSystemProperty` keeps it a no-op locally and on the PR lane, where no broker is
 * configured — matching every other broker-based provider test in the fleet.
 *
 * Per-interaction target dispatch, as `PartyEventPactProviderVerificationTest` does: two of the
 * three pacts naming account-service as provider are message-only (balance-service's
 * AccountCreated, notification-service's TRANSACTION_COMPLETED), but delegation-service's
 * ownership gate (issue #2991, ADR-0232 D7) reads `GET /api/v1/accounts/{id}`, which needs a
 * running endpoint — hence `@QuarkusTest` + Testcontainers. This mirrors the git-pact twin
 * exactly; the two must stay identical or the same contract passes from git and fails from the
 * broker.
 *
 * IMPORTANT: if `AccountCreatedMessagePactConsumerTest` (openbank-balance-service) changes the
 * contract, regenerate the pact JSON (`./gradlew :openbank-balance-service:test --tests
 * "*AccountCreatedMessagePactConsumerTest*"`) and commit the updated `pacts/openbank-balance-
 * service-openbank-account-service.json` in the same PR, or this test will fail against a stale
 * contract.
 *
 * `@IgnoreNoPactsToVerify(ignoreIoErrors)` makes a missing/unreadable pact a skip, not a failure.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_VIEWER", "ROLE_OPERATOR"])
@Provider("openbank-account-service")
@PactBroker(enablePendingPacts = "true")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class AccountEventPactProviderVerificationTest {

    companion object {
        // Must match DelegationAccountOwnershipPactConsumerTest's ACCOUNT_ID / OWNER_PARTY_ID.
        private val ACCOUNT_ID = UUID.fromString("11111111-2222-4333-8444-555555555555")
        private val OWNER_PARTY_ID = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa")

        // Must match CustomerEdgeDelegatedPaymentPactConsumerTest's fixed UUIDs.
        private val DELEGATED_ACCOUNT_ID = UUID.fromString("22222222-3333-4444-8555-666666666666")
        private val DELEGATION_GRANTOR_PARTY_ID = UUID.fromString("33333333-4444-4555-8666-777777777777")
        private val DELEGATE_PARTY_ID = UUID.fromString("44444444-5555-4666-8777-888888888888")
        private val DELEGATION_GRANT_ID = UUID.fromString("55555555-6666-4777-8888-999999999999")
    }

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var accountRepository: AccountRepository

    @Inject
    lateinit var delegationProjectionRepository: DelegationProjectionRepository

    @Inject
    lateinit var vertx: Vertx

    /** See the git-pact twin: Pact-JVM calls `@State` on a thread with no Vert.x context. */
    private fun runOnVertxContext(block: suspend () -> Unit) {
        val future = CompletableFuture<Unit>()
        val duplicated = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(duplicated, true)
        val dispatcher = Executor { command -> duplicated.runOnContext { command.run() } }.asCoroutineDispatcher()
        CoroutineScope(dispatcher).launch {
            try {
                block()
                future.complete(Unit)
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        future.get(10, TimeUnit.SECONDS)
    }

    @BeforeEach
    fun setTarget(context: PactVerificationContext?) {
        if (context == null) return
        // Limit the @PactVerifyProvider scan to this package — the default classpath-wide scan
        // (ClassGraph) throws on the JDK 25+ toolchain.
        context.target = if (context.interaction.isAsynchronousMessage()) {
            MessageTestTarget(listOf("com.openbank.account.contract"))
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

    @State("an account has been created")
    fun accountHasBeenCreated() {
        // No setup: the message is produced deterministically by the @PactVerifyProvider method below.
    }

    @PactVerifyProvider("an AccountCreated event")
    fun produceAccountCreatedEvent(): String {
        val event = AccountCreatedEvent(
            aggregateId = UUID.randomUUID(),
            version = 1L,
            accountNumber = "CZ6508000000192000145399",
            accountType = AccountType.CURRENT,
            partyId = UUID.randomUUID(),
            productId = UUID.randomUUID(),
            currency = "CZK",
            occurredAt = java.time.Instant.parse("2026-01-01T00:00:00Z"),
        )
        return objectMapper.writeValueAsString(event)
    }

    @State("account-service has posted an incoming credit")
    fun accountHasPostedIncomingCredit() {
        // No setup: notification request is produced deterministically below.
    }

    @PactVerifyProvider("a TRANSACTION_COMPLETED notification request")
    fun produceTransactionCompletedNotification(): String {
        val partyId = UUID.randomUUID()
        val request = linkedMapOf(
            "partyId" to partyId.toString(),
            "channel" to "PUSH",
            "template" to "TRANSACTION_COMPLETED",
            "recipient" to partyId.toString(),
            "variables" to mapOf("amount" to "50.00", "currency" to "CZK"),
        )
        return objectMapper.writeValueAsString(request)
    }

    /** Mirror of the git-pact twin's handler — see there for why the findById guard is needed. */
    @State("an account owned by a known party exists")
    fun accountOwnedByKnownParty() = runOnVertxContext {
        if (accountRepository.findById(ACCOUNT_ID) != null) return@runOnVertxContext
        accountRepository.save(
            Account(
                id = ACCOUNT_ID,
                accountNumber = Iban("CZ6508000000192000145399"),
                accountType = AccountType.CURRENT,
                partyId = OWNER_PARTY_ID,
                productId = UUID.fromString("99999999-8888-4777-8666-555555555555"),
                currency = CurrencyCode("CZK"),
                status = AccountStatus.ACTIVE,
                openedAt = Instant.parse("2026-01-01T00:00:00Z"),
                closedAt = null,
                version = 0,
            ),
        )
        Unit
    }

    /**
     * Seeds the account + grant behind customer-edge's debit-authorization contract (ADR-0232
     * D3/D5). Two rows, because the endpoint answers from BOTH: the account establishes who the
     * owner is, and the projection row is the grant `issuedBy(owner)` is checked against — a grant
     * whose grantor is not the account's owner is refused, which is the hole that gate exists to
     * close, so seeding only one of the two would silently produce NO_GRANT.
     *
     * The 5000.00 CZK ceiling is above the contract's 1500.00 amount ON PURPOSE: the consumer pact
     * carries the amount precisely so a provider that ignores per-transaction limits cannot pass,
     * and a ceiling equal to the amount would not distinguish "compared" from "not compared".
     *
     * `upsertActive` is idempotent on the grant id; the account `save` is not (`persist` on an
     * application-assigned id), hence the same findById guard as the state above.
     */
    @State("an account with an ACTIVE payment delegation to a known party exists")
    fun accountWithActivePaymentDelegation() = runOnVertxContext {
        if (accountRepository.findById(DELEGATED_ACCOUNT_ID) == null) {
            accountRepository.save(
                Account(
                    id = DELEGATED_ACCOUNT_ID,
                    accountNumber = Iban("CZ3808000000192000145400"),
                    accountType = AccountType.CURRENT,
                    partyId = DELEGATION_GRANTOR_PARTY_ID,
                    productId = UUID.fromString("99999999-8888-4777-8666-555555555555"),
                    currency = CurrencyCode("CZK"),
                    status = AccountStatus.ACTIVE,
                    openedAt = Instant.parse("2026-01-01T00:00:00Z"),
                    closedAt = null,
                    version = 0,
                ),
            )
        }
        delegationProjectionRepository.upsertActive(
            DelegatedAccessGrant(
                id = DELEGATION_GRANT_ID,
                accountId = DELEGATED_ACCOUNT_ID,
                grantorPartyId = DELEGATION_GRANTOR_PARTY_ID,
                granteePartyId = DELEGATE_PARTY_ID,
                capabilities = setOf(DelegatedAccessGrant.CAP_INITIATE_PAYMENT),
                resourceType = DelegatedAccessGrant.RESOURCE_TYPE_ACCOUNT,
                perTransactionLimitAmount = java.math.BigDecimal("5000.00"),
                perTransactionLimitCurrency = "CZK",
                validFrom = OffsetDateTime.now().minusDays(1),
                validTo = OffsetDateTime.now().plusYears(10),
                active = true,
            ),
        )
        Unit
    }

    /**
     * The negative state: deliberately seeds NOTHING.
     *
     * The vop pact asks for an IBAN this provider does not know, and the point of that interaction
     * is that the route answers 404 rather than a 200 carrying nulls — a distinction VoP's caller
     * cannot make afterwards, because "we hold no name for this IBAN" is itself a valid answer. A
     * handler that seeded anything would destroy the case it exists to pin.
     */
    @State("no account exists for the unknown IBAN")
    fun noAccountForUnknownIban() {
        // Intentionally empty.
    }
}
