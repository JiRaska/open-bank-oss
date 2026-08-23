// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.integration

import com.openbank.settlement.application.workflow.SettlementActivities
import com.openbank.settlement.it.BalanceServiceWireMockResource
import com.openbank.settlement.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.temporal.failure.ApplicationFailure
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * The proof for issue #6037: settlement compensation actually returns money.
 *
 * ## Why this test and not a unit test
 *
 * The defect was three activities that wrote a status row, logged
 * `"stub: wire reversal to balance-service"`, and reported success. Every existing unit test agreed
 * with them — `SettlementActivitiesImplTest` literally asserted
 * `reverseDebit does not call debit port` — because a mocked port cannot distinguish "called the
 * counterparty" from "did nothing". So this test mocks **nothing** on the path it is checking:
 *
 *  - the real CDI [com.openbank.settlement.application.workflow.SettlementActivitiesImpl] bean,
 *    with its real `VertxContextSupport` bridge (a Temporal activity thread carries no Vert.x
 *    context, which is why the reactive repository cannot simply be called from a bare
 *    `@QuarkusTest` thread — the production bridge is what makes it work here too);
 *  - the real reversal adapters and the real `BalanceRestClient`, so an actual HTTP request leaves
 *    the process and is asserted against by URL, verb and body;
 *  - a real Postgres (Testcontainers) with the real Flyway migrations;
 *  - **plain JDBC** for both the seed and the assertion, so the reactive persistence layer is never
 *    asked to confirm its own work.
 *
 * ## What it does and does not establish
 *
 * It establishes that a counter-movement of the right amount, currency, account and reference id is
 * **issued to balance-service**, and that the settlement row records the outcome that actually
 * occurred. It does not establish that balance-service applied it — settlement-service cannot
 * observe another service's ledger, and claiming otherwise would be the `accepted`-vs-`delivered`
 * mistake. The applying half is balance-service's own dedup/overdraft tests over
 * `balance_movement`.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(BalanceServiceWireMockResource::class)
class SettlementReversalIT {

    @Inject
    lateinit var activities: SettlementActivities

    private val amount = BigDecimal("125.50")

    @BeforeEach
    fun resetStubs() {
        BalanceServiceWireMockResource.reset()
    }

    private fun jdbc() = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    )

    /** Seeds a settlement directly with JDBC — no reactive repository involved. */
    private fun seedSettlement(status: String): Triple<UUID, UUID, UUID> {
        val id = UUID.randomUUID()
        val payer = UUID.randomUUID()
        val payee = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        jdbc().use { c ->
            c.prepareStatement(
                """
                INSERT INTO settlements
                    (id, payer_account_id, payee_account_id, amount, currency, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, payer)
                ps.setObject(3, payee)
                ps.setBigDecimal(4, amount)
                ps.setString(5, "CZK")
                ps.setString(6, status)
                ps.setTimestamp(7, now)
                ps.setTimestamp(8, now)
                ps.executeUpdate()
            }
        }
        return Triple(id, payer, payee)
    }

    private fun readStatus(id: UUID): String = jdbc().use { c ->
        c.prepareStatement("SELECT status FROM settlements WHERE id = ?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                assertThat(rs.next()).`as`("settlement row $id exists").isTrue()
                rs.getString(1)
            }
        }
    }

    private fun readUpdatedAt(id: UUID): Instant = jdbc().use { c ->
        c.prepareStatement("SELECT updated_at FROM settlements WHERE id = ?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getTimestamp(1).toInstant()
            }
        }
    }

    @Test
    fun `reverseDebit issues a real counter-credit to the payer and records REVERSED`() {
        val (id, payer, _) = seedSettlement("DEBITED")
        val before = Instant.now()

        activities.reverseDebit(id)

        // The money movement left the process, addressed to the PAYER, as a CREDIT.
        BalanceServiceWireMockResource.server.verify(
            BalanceServiceWireMockResource.creditRequestsTo(payer)
                .withRequestBody(
                    com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                        "$[?(@.referenceId == 'settlement-debit-reversal-$id')]",
                    ),
                )
                .withRequestBody(
                    com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                        "$[?(@.amount == 125.50)]",
                    ),
                )
                .withRequestBody(
                    com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                        "$[?(@.currency == 'CZK')]",
                    ),
                ),
        )
        assertThat(readStatus(id)).isEqualTo("REVERSED")
        // Recency, not non-nullity: an Instant.EPOCH or an untouched row would pass `isNotNull`.
        assertThat(readUpdatedAt(id)).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1))
    }

    @Test
    fun `reverseCredit issues a real counter-debit to the payee and records CREDITED_REVERSED`() {
        val (id, _, payee) = seedSettlement("CREDITED")

        activities.reverseCredit(id)

        BalanceServiceWireMockResource.server.verify(
            BalanceServiceWireMockResource.debitRequestsTo(payee)
                .withRequestBody(
                    com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                        "$[?(@.referenceId == 'settlement-credit-reversal-$id')]",
                    ),
                ),
        )
        assertThat(readStatus(id)).isEqualTo("CREDITED_REVERSED")
    }

    /**
     * A retried Temporal activity must not reverse twice. settlement-service's half of that
     * guarantee is that the reference id is a pure function of the settlement id, so every attempt
     * carries the same key and balance-service's `(account_id, currency, reference_id, operation)`
     * primary key collapses them. This asserts the stable key across attempts — the collapsing
     * itself is balance-service's `balance_movement` dedup, tested there.
     */
    @Test
    fun `a retried reversal re-sends an identical reference id rather than a fresh one`() {
        val (id, payer, _) = seedSettlement("DEBITED")

        activities.reverseDebit(id)
        activities.reverseDebit(id)

        val requests = BalanceServiceWireMockResource.server.findAll(
            BalanceServiceWireMockResource.creditRequestsTo(payer),
        )
        assertThat(requests).hasSize(2)
        assertThat(requests.map { it.bodyAsString })
            .allMatch { it.contains("settlement-debit-reversal-$id") }
        assertThat(requests[0].bodyAsString).isEqualTo(requests[1].bodyAsString)
    }

    @Test
    fun `a refused counter-debit records REVERSAL_FAILED and never claims the money came back`() {
        val (id, _, payee) = seedSettlement("CREDITED")
        BalanceServiceWireMockResource.stubDebitRefusedInsufficientFunds()

        assertThatThrownBy { activities.reverseCredit(id) }
            .`as`("a refused reversal must surface, not be swallowed into a success")
            .isInstanceOf(Throwable::class.java)

        // The request WAS made — this is not a skipped call.
        BalanceServiceWireMockResource.server.verify(
            BalanceServiceWireMockResource.debitRequestsTo(payee),
        )
        assertThat(readStatus(id))
            .`as`("money is still with the payee, so the row must not say CREDITED_REVERSED")
            .isEqualTo("REVERSAL_FAILED")
    }

    /**
     * The proof for issue #6410, against a real Postgres and a real HTTP ledger lookup.
     *
     * Case 1 of 3: the ledger CONFIRMS a journal for this settlement, so the GL carries the
     * posting and owes a manual correcting entry.
     */
    @Test
    fun `a confirmed GL journal fails loudly and marks the entry as needing manual correction`() {
        val (id, _, _) = seedSettlement("BOOKED")
        BalanceServiceWireMockResource.stubLedgerHasJournal(id)

        // The failure reaches the caller unwrapped through the Vert.x-context bridge, so Temporal
        // sees the ApplicationFailure itself and honours its non-retryable flag.
        assertThatThrownBy { activities.reverseBookToLedger(id) }
            .isInstanceOfSatisfying(ApplicationFailure::class.java) { failure ->
                assertThat(failure.isNonRetryable).isTrue()
                assertThat(failure.type).isEqualTo("LedgerReversalUnsupported")
            }

        assertThat(readStatus(id)).isEqualTo("LEDGER_REVERSAL_UNSUPPORTED")
        // It must not silently pretend to have reversed anything, and it must not call balance.
        assertThat(balanceRequests()).isEmpty()
    }

    /**
     * Case 2 of 3, and the one the old unconditional behaviour got wrong on every ordinary ledger
     * failure: the ledger holds NOTHING for this settlement, so the booking never posted, there is
     * no GL obligation, and the compensation must not manufacture one.
     *
     * A real HTTP `200 []` is what establishes it — not a mocked port, which cannot distinguish
     * "asked the ledger and it said no" from "assumed".
     */
    @Test
    fun `an empty ledger records LEDGER_NOT_POSTED and does not fail the compensation`() {
        val (id, _, _) = seedSettlement("CREDITED")
        BalanceServiceWireMockResource.stubLedgerHasNoJournal()

        activities.reverseBookToLedger(id)

        assertThat(readStatus(id))
            .`as`("nothing was posted, so summoning an accountant would be a false alarm")
            .isEqualTo("LEDGER_NOT_POSTED")
        assertThat(balanceRequests()).isEmpty()
    }

    /**
     * Case 3 of 3: the ledger could not answer. "We could not check" is a third fact, and rounding
     * it to either neighbour is a claim nobody established — a clean GL nobody verified, or a
     * standing posting that may not exist. Retryable, because an unreachable ledger is the one
     * failure here that a retry can genuinely resolve.
     */
    @Test
    fun `an unavailable ledger records LEDGER_STATE_UNKNOWN and stays retryable`() {
        val (id, _, _) = seedSettlement("CREDITED")
        BalanceServiceWireMockResource.stubLedgerLookupUnavailable()

        assertThatThrownBy { activities.reverseBookToLedger(id) }
            .isInstanceOfSatisfying(ApplicationFailure::class.java) { failure ->
                assertThat(failure.isNonRetryable).isFalse()
                assertThat(failure.type).isEqualTo("LedgerStateUnknown")
            }

        assertThat(readStatus(id))
            .`as`("a 5xx from the ledger is not a clean general ledger")
            .isEqualTo("LEDGER_STATE_UNKNOWN")
    }

    private fun balanceRequests() = BalanceServiceWireMockResource.server.findAll(
        com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
            com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching("/api/v1/balances/.*"),
        ),
    )
}
