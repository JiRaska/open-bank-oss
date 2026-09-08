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
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
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
import io.quarkus.test.security.TestIdentityAssociation
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
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Git-pact provider verification for account-service — the half that actually runs before a merge
 * (issue #2327, gated by `check-pact-provider-replay.py` per #2338).
 *
 * account-service is the provider for two committed pacts, both message-only: balance-service's
 * `AccountCreated` and notification-service's `TRANSACTION_COMPLETED`. Its only verification class
 * was [AccountEventPactProviderVerificationTest] — `@PactBroker`-sourced and
 * `@EnabledIfSystemProperty(pactbroker.url)`-gated. On a pull request that property is empty
 * (`_service-ci.yml` puts the PR lane on `ubuntu-latest` and blanks `PACT_BROKER_URL` off
 * main-push, because the broker has no public ingress, ADR-0056), so it skipped and both contracts
 * were replayed only AFTER the merge.
 *
 * ## Additive, not a replacement — this class does NOT undo #1166
 *
 * The broker twin stays exactly as it is. Flipping it to `@PactFolder` is precisely what #372 did
 * and #1166 reverted: nothing then pulled the consumers' pacts back out of the broker to verify
 * them and publish a result, and since `can-i-deploy` reads the broker and nothing else, it saw
 * "no verified pact" for every consumer of account-service and blocked their deploys —
 * notification-service #1180 and #1303 both merged clean, built green, then failed the gate. Git
 * source for the PR lane, broker source for the published result: the pair
 * openbank-ledger-service carries. Two `@Provider` classes collide only when BOTH pull from the
 * broker, since each then fetches every pact it holds.
 *
 * ## No longer cheap — it boots now, and that was not optional (issue #2991)
 *
 * This class used to be a plain JVM test: both pacts were message-only, so a [MessageTestTarget]
 * was the whole target and the KDoc said "if an HTTP consumer contract against account-service is
 * ever added, this needs party-service's per-interaction target dispatch". delegation-service's
 * ownership gate is that contract — it reads `GET /api/v1/accounts/{id}` to check the grantor owns
 * the account before offering a grant (ADR-0232 D7) — so the dispatch is now here, along with
 * `@QuarkusTest` + Testcontainers, which the HTTP interaction cannot do without.
 *
 * Splitting the HTTP interaction into a second `@PactFolder` class was not an option: both classes
 * would load every pact in `../pacts` naming this provider, so each would try to verify the other's
 * interactions against the wrong target. One `@Provider` class per provider (`CLAUDE.md`).
 *
 * ## Upkeep
 *
 * A deliberate duplicate of the broker twin's body: same `@State` handlers and same
 * [PactVerifyProvider] producers. A change to one belongs in the other, or the same contract
 * passes from git and fails from the broker (or the reverse).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_VIEWER", "ROLE_OPERATOR"])
@Provider("openbank-account-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class AccountPactFolderProviderVerificationTest {

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

    @Inject
    lateinit var testIdentityAssociation: TestIdentityAssociation

    /**
     * Bridges reactive Panache into Pact-JVM's synchronous `@State` callback — Pact-JVM invokes
     * `@State` by reflection on the JUnit thread, which has no Vert.x context, so a bare
     * `runBlocking { accountRepository.save(...) }` throws `No current Vertx context found`. Same
     * shape as `PartyPactFolderProviderVerificationTest.runOnVertxContext`.
     */
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
        // Per-interaction dispatch: message pacts answer from a @PactVerifyProvider producer, the
        // delegation ownership pact from the running HTTP endpoint. Limit the @PactVerifyProvider
        // scan to this package — the default classpath-wide scan (ClassGraph) throws on the JDK 25+
        // toolchain.
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
        // #8803: the vop pact's anonymous interaction expects 401, but the class-level
        // @TestSecurity makes Quarkus' test auth mechanism authenticate EVERY replay as
        // pact-verifier — an anonymous replay is impossible while a test identity is installed.
        // Clear it for just this interaction so the replay reaches the endpoint unauthenticated;
        // QuarkusSecurityTestExtension re-applies @TestSecurity before the next invocation.
        if (context != null && context.interaction.description.endsWith("no caller identity")) {
            testIdentityAssociation.setTestIdentity(null)
        }
        context?.verifyInteraction()
    }

    /**
     * Serves the NEGATIVE interaction of the VoP pact: an IBAN the bank does not hold must answer
     * 404, not an empty account. The absence IS the state — nothing is seeded, and the IBAN in the
     * pact is one no other state creates — but a handler still has to exist, because pact-jvm fails
     * the interaction on an unknown state string before it ever issues the request, which is what
     * "GET the account behind an IBAN the bank does not hold FAILED" meant the first time (#8889).
     */
    @State("no account exists for the IBAN")
    fun noAccountForIban() {
        // Deliberately empty. Asserting emptiness here would test the fixture, not the provider.
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

    /**
     * Seeds the account delegation-service's ownership gate asks about (ADR-0232 D7). Guarded by a
     * `findById`: `AccountRepositoryImpl.save` calls `persist` on an application-assigned id, so a
     * second call for the same account would fail on the primary key rather than upsert.
     */
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
}
