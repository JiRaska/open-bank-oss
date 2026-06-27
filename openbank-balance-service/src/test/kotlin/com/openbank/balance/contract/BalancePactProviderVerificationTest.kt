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
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.balance.application.port.out.BalanceRepository
import com.openbank.balance.application.port.out.HoldRepository
import com.openbank.balance.domain.model.Balance
import com.openbank.balance.domain.model.BalanceHold
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Provider-side verification for all balance-service consumer contracts (ADR-0063 P2).
 * Fetches pacts from the broker (`@PactBroker`) and replays each interaction against the live
 * Quarkus test instance. Verification results are published back so `can-i-deploy` can gate deploys.
 *
 * Gated on `pactbroker.url`: skipped locally (no broker configured); git-pact remains the
 * offline fallback until retirement. `@IgnoreNoPactsToVerify(ignoreIoErrors)` treats a broker
 * outage as a skip, not a failure.
 *
 * State handlers seed the Testcontainer DB with the exact IDs each consumer pact captures.
 * All state IDs are distinct to avoid unique-constraint collisions on (accountId, currency):
 * - P2 pilot (transaction-service): a1a1 (placeHold), c3c3 (releaseHold)
 * - Batch A (account-service): d4d4 (getBalances), d5d5 (getBalance single), e5e5 (initialize)
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.balance.it.PostgresRedpandaTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_SERVICE", "ROLE_OPERATOR"])
@Provider("openbank-balance-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class BalancePactProviderVerificationTest {

    companion object {
        // Fixed UUIDs must match each consumer pact exactly — grouped by consuming service.
        // P2 pilot: transaction-service placeHold + releaseHold
        private val HOLDS_ACCOUNT_ID = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1")
        private val RELEASE_ACCOUNT_ID = UUID.fromString("c3c3c3c3-c3c3-c3c3-c3c3-c3c3c3c3c3c3")
        private val RELEASE_HOLD_ID = UUID.fromString("b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2")

        // Batch A: account-service getBalances / getBalance / initialize
        private val LIST_ACCOUNT_ID = UUID.fromString("d4d4d4d4-d4d4-d4d4-d4d4-d4d4d4d4d4d4")
        private val SINGLE_ACCOUNT_ID = UUID.fromString("d5d5d5d5-d5d5-d5d5-d5d5-d5d5d5d5d5d5")
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var balanceRepo: BalanceRepository

    @Inject
    lateinit var holdRepo: HoldRepository

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("a CZK balance exists for the holds account with sufficient funds")
    fun stateBalanceExists(): Unit = runBlocking {
        balanceRepo.save(
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
    fun stateHoldExists(): Unit = runBlocking {
        // Balance must exist before releaseHold — it looks up (accountId, currency) to update.
        // Uses a distinct RELEASE_ACCOUNT_ID to avoid (accountId, currency) collision with the
        // stateBalanceExists handler when both run in the same Testcontainer DB.
        balanceRepo.save(
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
        holdRepo.save(
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
    fun stateBalancesExist(): Unit = runBlocking {
        balanceRepo.save(
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
    fun stateSingleCzkBalanceExists(): Unit = runBlocking {
        balanceRepo.save(
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
}
