// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.close.CloseParticipationReport
import com.openbank.libs.close.CloseParticipationSink
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.TreasuryCloseEvidencePort
import com.openbank.treasury.domain.DealFixtures
import com.openbank.treasury.infrastructure.close.TreasuryCloseParticipationScheduler
import com.openbank.treasury.infrastructure.scheduler.InterestAccrualScheduler
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Real HTTP and PostgreSQL: acknowledgements survive a real stale lifecycle transaction, never a mock rollback. */
@QuarkusTest
@TestProfile(TreasuryCloseLifecycleEvidenceIT.Profile::class)
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
class TreasuryCloseLifecycleEvidenceIT {
    class Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "false",
            "openbank.treasury.confirmation.required" to "false",
            "openbank.treasury.accrual.enabled" to "true",
            "openbank.treasury.close.lookback-days" to "3",
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(RecordingSink::class.java)
    }

    @Alternative
    @ApplicationScoped
    class RecordingSink : CloseParticipationSink {
        val delivered = CopyOnWriteArrayList<CloseParticipationReport>()

        override fun deliver(participant: String, report: CloseParticipationReport): Uni<Unit> {
            delivered += report
            return Uni.createFrom().item(Unit)
        }
    }

    @Inject
    lateinit var deals: DealRepository

    @Inject
    lateinit var ledger: FakeLedger

    @Inject
    lateinit var evidence: TreasuryCloseEvidencePort

    @Inject
    lateinit var reporter: TreasuryCloseParticipationScheduler

    @Inject
    lateinit var accrual: InterestAccrualScheduler

    @Inject
    lateinit var sink: RecordingSink

    @Inject
    lateinit var clock: Clock

    private val owned = mutableListOf<UUID>()

    @AfterEach
    fun releaseOwnOutbox() {
        ledger.afterCommit = null
        jdbc { c ->
            c.prepareStatement("UPDATE treasury_outbox SET status = 'SENT' WHERE aggregate_id = ?").use { s ->
                owned.forEach { id ->
                    s.setObject(1, id)
                    s.executeUpdate()
                }
            }
        }
    }

    private fun <T> onContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        Uni.createFrom().completionStage(CoroutineScope(Dispatchers.Unconfined).future { block() })
    }

    private fun booked(valueDay: LocalDate, maturityDay: LocalDate): UUID {
        val draft = DealFixtures.placement(valueDate = valueDay, maturity = maturityDay).copy(id = UUID.randomUUID())
        val check = DealFixtures.withinLimit(draft)
        val submitted = draft.submit(DealFixtures.dealer, check, clock.instant(), DealFixtures.withinProduct)
        val booked = submitted.approve(DealFixtures.approver, check, clock.instant(), DealFixtures.withinProduct)
        return onContext { deals.save(booked).id }.also { owned += it }
    }

    private fun action(id: UUID, verb: String) = given().contentType("application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .post("/api/v1/treasury/deals/$id/$verb")

    private fun reportsFor(day: LocalDate): List<CloseParticipationReport> {
        sink.delivered.clear()
        VertxContextSupport.subscribeAndAwait { reporter.reportDays(AccountingClock.bank(clock).today()) }
        return sink.delivered.filter { it.businessDate == day }
    }

    private fun eventState(id: UUID, kind: String, state: String) = jdbc { c ->
        c.prepareStatement("UPDATE treasury_outbox SET status = ? WHERE aggregate_id = ? AND event_type = ?").use { s ->
            s.setString(1, state)
            s.setObject(2, id)
            s.setString(3, "treasury.deal.$kind.v1")
            assertThat(s.executeUpdate()).isEqualTo(1)
        }
    }

    @Test
    fun `a real stale settlement save holds its acknowledged journal until retry and delivery`() {
        ledger.reset()
        val today = AccountingClock.bank(clock).today()
        val day = today.minusDays(1)
        val id = booked(day, today.plusDays(30))
        val original = onContext { requireNotNull(deals.findById(id)) }
        // Another legal transition commits while the ledger acknowledgement is in flight. The
        // HTTP settlement still has the old row version, so its state/outbox transaction must fail.
        ledger.afterCommit = { deals.save(original.confirm(DealFixtures.approver, clock.instant())) }
        action(id, "settle").then().statusCode(409).body("error", equalTo("DEAL_CHANGED"))
        assertThat(onContext { requireNotNull(deals.findById(id)).state.name }).isEqualTo("CONFIRMED")
        assertThat(onContext { deals.journals(id) }.count { it.event.name == "SETTLED" }).isEqualTo(1)
        onContext { accrual.run() } // Completes, but CONFIRMED is not in its SETTLED-deal query.
        val unresolved = VertxContextSupport.subscribeAndAwait { evidence.postingsFor(day) }
        assertThat(unresolved.unapplied).isEqualTo(1)
        assertThat(reportsFor(day)).isEmpty()

        action(id, "settle").then().statusCode(200).body("state", equalTo("SETTLED"))
        onContext { accrual.run() }
        val pending = VertxContextSupport.subscribeAndAwait { evidence.postingsFor(day) }
        assertThat(pending.unapplied).isZero()
        assertThat(pending.pending).isEqualTo(1) // Event created today still belongs to yesterday's journal.
        assertThat(reportsFor(day)).isEmpty()
        eventState(id, "settled", "SENT")
        assertThat(reportsFor(day).map { it.outcome.name }).containsExactly("RAN")
        assertThat(onContext { deals.journals(id) }.count { it.event.name == "SETTLED" }).isEqualTo(1)
        assertThat(ledger.journals.values.count { it.second.event.name == "SETTLED" }).isEqualTo(1)
    }

    @Test
    fun `a maturity event holds only its exact journal value day and preserves completed settlement history`() {
        ledger.reset()
        val today = AccountingClock.bank(clock).today()
        val settlementDay = today.minusDays(2)
        val maturityDay = today.minusDays(1)
        val id = booked(settlementDay, maturityDay)
        action(id, "settle").then().statusCode(200)
        action(id, "mature").then().statusCode(200).body("state", equalTo("MATURED"))
        onContext { accrual.run() }
        eventState(id, "settled", "SENT")
        val settled = VertxContextSupport.subscribeAndAwait { evidence.postingsFor(settlementDay) }
        val matured = VertxContextSupport.subscribeAndAwait { evidence.postingsFor(maturityDay) }
        assertThat(settled.unapplied).isZero() // Immutable SETTLED transition, despite current state MATURED.
        assertThat(settled.pending).isZero()
        assertThat(matured.pending).isEqualTo(1)
        assertThat(reportsFor(settlementDay).map { it.outcome.name }).containsExactly("RAN")
        assertThat(reportsFor(maturityDay)).isEmpty()
        eventState(id, "matured", "DEAD")
        assertThat(VertxContextSupport.subscribeAndAwait { evidence.postingsFor(settlementDay) }.dead).isZero()
        assertThat(reportsFor(maturityDay).map { it.outcome.name }).containsExactly("REFUSED")
    }

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T {
        val c = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            c.getValue("quarkus.datasource.jdbc.url", String::class.java),
            c.getValue("quarkus.datasource.username", String::class.java),
            c.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }
}
