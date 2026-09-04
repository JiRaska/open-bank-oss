// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.balance.application.port.out.BalanceRepository
import com.openbank.balance.application.port.out.HoldRepository
import com.openbank.balance.domain.model.Balance
import com.openbank.balance.domain.model.BalanceHold
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
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Git-pact provider verification for balance-service — the half that actually runs before a merge
 * (issue #2327, gated by `check-pact-provider-replay.py` per #2338).
 *
 * balance-service is the provider for three committed pacts (six interactions, all HTTP) and its
 * only verification class was [BalancePactProviderVerificationTest] — `@PactBroker`-sourced and
 * `@EnabledIfSystemProperty(pactbroker.url)`-gated. On a pull request that property is empty
 * (`_service-ci.yml` puts the PR lane on `ubuntu-latest` and blanks `PACT_BROKER_URL` off
 * main-push, because the broker has no public ingress, ADR-0056), so it skipped and all three
 * contracts were replayed only AFTER the merge. A consumer pact cannot catch a wrong request path;
 * only the provider replay can (#2269), and balance-service is on the money path: two of these
 * interactions are transaction-service reserving and releasing payment cover.
 *
 * ## Additive, not a replacement
 *
 * The broker twin stays exactly as it is. Its published verification result is the only thing
 * ADR-0092's `can-i-deploy` reads, and dropping it is not hypothetical harm — the same swap on
 * party-service (#371) left that service's auto-deploy hard-blocked for four days (#1166). Git
 * source for the PR lane, broker source for the published result: the pair
 * openbank-ledger-service carries. Two `@Provider` classes collide only when BOTH pull from the
 * broker, since each then fetches every pact it holds.
 *
 * ## Upkeep
 *
 * A deliberate duplicate of the broker twin's body: same `@State` handlers, same seeded rows, same
 * fixed UUIDs. A change to one belongs in the other, or the same contract passes from git and
 * fails from the broker (or the reverse). Every interaction here is HTTP, so unlike the party and
 * transaction twins there is no `MessageTestTarget` and no `@PactVerifyProvider` producer.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.balance.it.PostgresRedpandaTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-balance-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class BalancePactFolderProviderVerificationTest {

    companion object {
        // Fixed UUIDs must match each consumer pact exactly — grouped by consuming service.
        // P2 pilot: transaction-service placeHold + releaseHold
        private val HOLDS_ACCOUNT_ID = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1")
        private val RELEASE_ACCOUNT_ID = UUID.fromString("c3c3c3c3-c3c3-c3c3-c3c3-c3c3c3c3c3c3")
        private val RELEASE_HOLD_ID = UUID.fromString("b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2")

        // Batch A: account-service getBalances / getBalance / initialize
        private val LIST_ACCOUNT_ID = UUID.fromString("d4d4d4d4-d4d4-d4d4-d4d4-d4d4d4d4d4d4")
        private val SINGLE_ACCOUNT_ID = UUID.fromString("d5d5d5d5-d5d5-d5d5-d5d5-d5d5d5d5d5d5")

        // #8345: settlement-service debit (payer leg) + credit (payee leg). Distinct account ids
        // from every state above — the seeds share one Testcontainer database, so a reused
        // (accountId, currency) would collide on `balances_account_id_currency_key`.
        private val SETTLEMENT_PAYER_ACCOUNT_ID = UUID.fromString("5e771e33-0000-4000-8000-00000000d1b1")
        private val SETTLEMENT_PAYEE_ACCOUNT_ID = UUID.fromString("5e771e33-0000-4000-8000-00000000c1e1")
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var balanceRepo: BalanceRepository

    @Inject
    lateinit var holdRepo: HoldRepository

    @Inject
    lateinit var vertx: Vertx

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    /**
     * Bridges a reactive-Panache block into Pact-JVM's synchronous `@State` callback. Pact-JVM
     * invokes `@State` methods directly via reflection on the JUnit test thread, which has no
     * Vert.x context — `Panache.withTransaction`/`withSession` (used by [balanceRepo]/[holdRepo])
     * require one, so a bare `runBlocking { balanceRepo.save(...) }` throws
     * `IllegalStateException: No current Vertx context found`. Confirmed live: this broke every
     * account-service<->balance-service pact verification since 2026-06-21 (verification result
     * #389) without ever being noticed, because the failure only actually blocks a deploy once
     * `can-i-deploy` is reached — `fx-service`'s own Pact provider test has the identical
     * `runBlocking { reactiveRepo.save(...) }` pattern and is presumably equally broken.
     *
     * A plain `vertx.runOnContext { runBlocking { ... } }` is NOT sufficient: it throws a
     * *different* `IllegalStateException` ("current context is not a duplicated context") because
     * Quarkus's `VertxContextSafetyToggle` requires the reactive Panache call to run on a
     * duplicated context (the kind Quarkus creates per-request), not a plain event-loop context.
     * Nesting `runBlocking` inside that context is also independently wrong even once the
     * duplicated+safe context is set up: it parks the very event-loop thread the reactive chain
     * needs to resume on, deadlocking instead of throwing (verified empirically — it hung the
     * test JVM for 15+ minutes). The fix duplicates the context, marks it safe via the same
     * toggle Quarkus uses internally, and dispatches the coroutine onto it with a plain
     * `CoroutineDispatcher` that posts via `runOnContext` — never blocking that thread — so
     * suspension/resumption on the reactive chain completes normally.
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

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    /**
     * Idempotent seed. The same `@State` can run more than once in one JVM (Pact-JVM runs one
     * verification per pact *version*, and enablePending/WIP pulls several), while the port's
     * `save` is Panache `persist` — INSERT-only. The second run used to die on the
     * `balances_account_id_currency_key` unique constraint (23505) inside the state-change
     * callback, failing every interaction before it was even compared (issue #1771, verification
     * results 2485/2486 and 9121/9122). Find-then-update keeps the seed re-runnable; `update`
     * rewrites the amounts by (accountId, currency), which is exactly the reset the next
     * verification pass needs.
     */
    private suspend fun seedBalance(balance: Balance) {
        if (balanceRepo.findByAccountIdAndCurrency(balance.accountId, balance.currency) == null) {
            balanceRepo.save(balance)
        } else {
            balanceRepo.update(balance)
        }
    }

    /**
     * Same idempotency for holds: the hold id is fixed, and a verified releaseHold sets
     * `releasedAt` on the previous run's row — update resets it to active for the next pass.
     */
    private suspend fun seedHold(hold: BalanceHold) {
        if (holdRepo.findById(hold.id) == null) {
            holdRepo.save(hold)
        } else {
            holdRepo.update(hold)
        }
    }

    @State("a CZK balance exists for the holds account with sufficient funds")
    fun stateBalanceExists() = runOnVertxContext {
        seedBalance(
            Balance(
                id = UUID.randomUUID(),
                accountId = HOLDS_ACCOUNT_ID,
                currency = "CZK",
                bookedAmount = BigDecimal("10000.00"),
                availableAmount = BigDecimal("10000.00"),
                reservedAmount = BigDecimal.ZERO,
                pendingAmount = BigDecimal.ZERO,
                updatedAt = OffsetDateTime.now(),
                version = 0L,
            ),
        )
        Unit
    }

    @State("a CZK hold exists for the holds account")
    fun stateHoldExists() = runOnVertxContext {
        // Balance must exist before releaseHold — it looks up (accountId, currency) to update.
        // Uses a distinct RELEASE_ACCOUNT_ID to avoid (accountId, currency) collision with the
        // stateBalanceExists handler when both run in the same Testcontainer DB.
        seedBalance(
            Balance(
                id = UUID.randomUUID(),
                accountId = RELEASE_ACCOUNT_ID,
                currency = "CZK",
                bookedAmount = BigDecimal("10000.00"),
                availableAmount = BigDecimal("9900.00"),
                reservedAmount = BigDecimal("100.00"),
                pendingAmount = BigDecimal.ZERO,
                updatedAt = OffsetDateTime.now(),
                version = 0L,
            ),
        )
        seedHold(
            BalanceHold(
                id = RELEASE_HOLD_ID,
                accountId = RELEASE_ACCOUNT_ID,
                amount = BigDecimal("100.00"),
                currency = "CZK",
                reason = "payment-cover",
                referenceId = "pact-tx-00000001",
                expiresAt = null,
                createdAt = OffsetDateTime.now(),
                releasedAt = null,
            ),
        )
        Unit
    }

    // --- Batch A: account-service states ---

    @State("balances exist for the balance account")
    fun stateBalancesExist() = runOnVertxContext {
        seedBalance(
            Balance(
                id = UUID.randomUUID(),
                accountId = LIST_ACCOUNT_ID,
                currency = "CZK",
                bookedAmount = BigDecimal("5000.00"),
                availableAmount = BigDecimal("4900.00"),
                reservedAmount = BigDecimal("100.00"),
                pendingAmount = BigDecimal.ZERO,
                updatedAt = OffsetDateTime.now(),
                version = 0L,
            ),
        )
        Unit
    }

    @State("a CZK balance exists for the balance account")
    fun stateSingleCzkBalanceExists() = runOnVertxContext {
        seedBalance(
            Balance(
                id = UUID.randomUUID(),
                accountId = SINGLE_ACCOUNT_ID,
                currency = "CZK",
                bookedAmount = BigDecimal("5000.00"),
                availableAmount = BigDecimal("4900.00"),
                reservedAmount = BigDecimal("100.00"),
                pendingAmount = BigDecimal.ZERO,
                updatedAt = OffsetDateTime.now(),
                version = 0L,
            ),
        )
        Unit
    }

    @State("no CZK balance exists for the initialize account")
    fun stateInitializeAccountHasNoBalance() {
        // No-op: the Testcontainer DB is clean for e5e5 on every run.
    }

    /**
     * State for mcp-service's `BalanceReadPactConsumerTest` (issue #2255, ADR-0195). No setup:
     * `getBalances` skips its owner check when no `X-Customer-Party-Id` header is present (mcp is a
     * service-to-service reader and sends none) and answers 200 with an empty `balances` array for
     * an account it holds nothing for. That 200 is the point of the interaction — it proves the
     * route exists; a 404 would prove nothing, since Quarkus answers 404 for an absent route too.
     */
    @State("balance-service is reachable and holds no balances for the pact account")
    fun stateNoBalancesForPactAccount() {
        // Intentionally empty — a fresh Testcontainer DB satisfies it by construction. Declared so
        // the state is an explicit part of the contract; pact-jvm passes silently over an unhandled
        // state name, which is how the missing states in #468 stayed invisible.
    }

    // --- #8345: settlement-service money movements ---

    /**
     * Funds the payer leg of a settlement. 10 000.00 CZK against a 750.00 movement, deliberately
     * far above it: `Balance.applyDebit` refuses a debit past the overdraft floor with 422, and a
     * balance equal to the amount would leave the interaction one rounding decision away from
     * failing for a reason that has nothing to do with the contract.
     *
     * The seed is find-then-update (see [seedBalance]) because the movement is APPLIED by the
     * verification — a second pass over the same pact would otherwise start from an already
     * debited balance. balance-service is separately idempotent on `referenceId`, so a replay of
     * the same interaction is not double-applied either; the reset is what keeps the *first* pass
     * of each run identical.
     */
    @State("a CZK balance exists for the settlement payer account")
    fun stateSettlementPayerBalanceExists() = runOnVertxContext {
        seedBalance(
            Balance(
                id = UUID.randomUUID(),
                accountId = SETTLEMENT_PAYER_ACCOUNT_ID,
                currency = "CZK",
                bookedAmount = BigDecimal("10000.00"),
                availableAmount = BigDecimal("10000.00"),
                reservedAmount = BigDecimal.ZERO,
                pendingAmount = BigDecimal.ZERO,
                updatedAt = OffsetDateTime.now(),
                version = 0L,
            ),
        )
        Unit
    }

    /** Receives the payee leg of the same settlement. Same reset rationale as the payer state. */
    @State("a CZK balance exists for the settlement payee account")
    fun stateSettlementPayeeBalanceExists() = runOnVertxContext {
        seedBalance(
            Balance(
                id = UUID.randomUUID(),
                accountId = SETTLEMENT_PAYEE_ACCOUNT_ID,
                currency = "CZK",
                bookedAmount = BigDecimal("8500.00"),
                availableAmount = BigDecimal("8500.00"),
                reservedAmount = BigDecimal.ZERO,
                pendingAmount = BigDecimal.ZERO,
                updatedAt = OffsetDateTime.now(),
                version = 0L,
            ),
        )
        Unit
    }
}
