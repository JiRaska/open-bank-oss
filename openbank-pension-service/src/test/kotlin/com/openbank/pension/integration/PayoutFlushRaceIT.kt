// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.pension.application.exit.ChangePayoutAccountCommand
import com.openbank.pension.application.exit.ExitConcurrentUpdateException
import com.openbank.pension.application.exit.PayoutRequestRepository
import com.openbank.pension.application.exit.PayoutService
import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.domain.exit.PayoutQuote
import com.openbank.pension.domain.exit.PayoutRequest
import com.openbank.pension.domain.exit.TaxBase
import com.openbank.pension.domain.model.PayoutForm
import com.openbank.pension.it.PostgresTestResource
import com.openbank.pension.testsupport.ContractFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * The FLUSH-time lost race on real Postgres (#12383), deterministically: a second transaction
 * (plain JDBC) bumps the payout row's `row_version` and HOLDS the row lock; the application then
 * loads the row (it still sees the committed version, so the in-memory version check passes) and
 * flushes — the UPDATE blocks on the lock; the JDBC transaction commits; Postgres re-evaluates
 * `WHERE row_version = <old>` and updates nothing, so Hibernate's `@Version` refuses the write.
 *
 * That refusal must reach the services as [ExitConcurrentUpdateException] (retryable) — not as a
 * raw Hibernate exception mapped to a 409 nobody retries — and `PayoutService`'s retry must then
 * apply the account change on the fresh row.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(PayoutFlushRaceIT.OwnApp::class)
class PayoutFlushRaceIT {

    /** Its own app and Temporal environment, so no other class's workflow time reaches it. */
    class OwnApp : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.pension.onboarding.task-queue" to "it-flush-pension-onboarding",
            "openbank.pension.exit.task-queue" to "it-flush-pension-exit",
        )
    }

    @Inject
    lateinit var contractUseCase: PensionContractUseCase

    @Inject
    lateinit var contractRepository: PensionContractRepository

    @Inject
    lateinit var payouts: PayoutRequestRepository

    @Inject
    lateinit var payoutService: PayoutService

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    @ConfigProperty(name = "quarkus.datasource.username")
    lateinit var dbUser: String

    @ConfigProperty(name = "quarkus.datasource.password")
    lateinit var dbPassword: String

    private val party: UUID = UUID.randomUUID()

    @Test
    fun `a write that loses at flush surfaces as the retryable ExitConcurrentUpdateException`() {
        val payout = confirmedPayout()
        val failure = withRowLockedAndBumped(payout.id) {
            CompletableFuture.supplyAsync {
                runCatching {
                    onVertx { payouts.save(payout.copy(scaChallengeId = "touched", updatedAt = Instant.now())) }
                }
            }
        }.exceptionOrNull()
        assertThat(rootOf(failure)).isInstanceOf(ExitConcurrentUpdateException::class.java)
    }

    @Test
    fun `an account change that loses at flush is re-read and applied by the retry`() {
        val payout = confirmedPayout()
        val result = withRowLockedAndBumped(payout.id) {
            CompletableFuture.supplyAsync {
                runCatching {
                    onVertx {
                        payoutService.changePayoutAccount(
                            ChangePayoutAccountCommand(
                                Caller.customer(party),
                                payout.contractId,
                                payout.id,
                                "sca-${UUID.randomUUID()}",
                                OTHER_IBAN,
                            ),
                        )
                    }
                }
            }
        }
        val changed = result.getOrThrow()
        assertThat(changed.pendingPayoutIban).isEqualTo(OTHER_IBAN)
        assertThat(changed.pendingPayoutIbanNotified).isTrue()
        assertThat(changed.payoutIban).isEqualTo(IBAN)
        // JDBC bump (+1), the retried change (+1) and the notified mark (+1): nothing was lost.
        assertThat(rowVersion(payout.id)).isEqualTo(payout.version + 3)
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Holds the payout row (bumped version, uncommitted) while [write] runs, waits until the
     * application's UPDATE is blocked on that row lock, then commits so the write loses at flush.
     */
    private fun <T> withRowLockedAndBumped(payoutId: UUID, write: () -> CompletableFuture<T>): T = connect().use { tx ->
        tx.autoCommit = false
        tx.prepareStatement(
            "UPDATE pension_payout_requests SET row_version = row_version + 1 WHERE aggregate_id = ?",
        )
            .use {
                it.setObject(1, payoutId)
                check(it.executeUpdate() == 1)
            }
        val pending = write()
        awaitBlockedOnLock()
        check(!pending.isDone) { "the application write did not wait for the row lock" }
        tx.commit()
        try {
            pending.get(WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun awaitBlockedOnLock() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        connect().use { probe ->
            while (System.nanoTime() < deadline) {
                if (lockWaiters(probe) > 0) return
                Thread.sleep(POLL_MS)
            }
        }
        error("the application UPDATE never blocked on the held row lock")
    }

    private fun lockWaiters(probe: Connection): Int = probe.createStatement().use { st ->
        st.executeQuery(
            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                "AND query ILIKE 'update pension_payout_requests%'",
        ).use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }

    private fun rowVersion(id: UUID): Int = connect().use { c ->
        c.prepareStatement("SELECT row_version FROM pension_payout_requests WHERE aggregate_id = ?").use { st ->
            st.setObject(1, id)
            st.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun connect(): Connection = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)

    private fun confirmedPayout(): PayoutRequest = onVertx {
        val contract = ContractFixtures.activeContract(
            contractUseCase,
            contractRepository,
            party,
            birthDate = LocalDate.parse("1960-02-02"),
            startDate = LocalDate.parse("2010-01-01"),
        )
        val quote = PayoutQuote(
            PayoutForm.PHASED_WITHDRAWAL, BigDecimal("12000.00"), BigDecimal("12000.00"), TaxBase.NONE,
            BigDecimal("0.00"), BigDecimal("0.00"), BigDecimal("12000.00"), "CZK", 1, months = 12,
        )
        val now = Instant.now()
        payouts.save(
            PayoutRequest.quote(contract, party, quote, 30, now)
                .confirm(IBAN, "sca-confirm", UUID.randomUUID().toString(), LocalDate.now(), now),
        )
    }

    private fun rootOf(t: Throwable?): Throwable? =
        generateSequence(t) { it.cause }.firstOrNull { it is ExitConcurrentUpdateException } ?: t

    private fun <T> onVertx(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

    private companion object {
        const val IBAN = "CZ6508000000192000145399"
        const val OTHER_IBAN = "CZ5508000000001234567899"
        const val WAIT_SECONDS = 20L
        const val POLL_MS = 50L
    }
}
