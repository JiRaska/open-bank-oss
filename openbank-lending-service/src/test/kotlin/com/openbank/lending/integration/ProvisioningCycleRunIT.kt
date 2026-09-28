// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.integration

import com.openbank.lending.application.port.`in`.RunProvisioningCycleUseCase
import com.openbank.lending.application.port.out.ProvisioningCoverageRepository
import com.openbank.lending.application.port.out.ProvisioningCycleRunRepository
import com.openbank.lending.domain.model.ProvisioningRunOutcome
import com.openbank.lending.infrastructure.servicing.ProvisioningCycleScheduler
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class ProvisioningCycleRunIT {
    @Inject lateinit var runs: ProvisioningCycleRunRepository

    @Inject lateinit var dataSource: DataSource

    private val at = OffsetDateTime.of(2098, 1, 1, 4, 0, 0, 0, ZoneOffset.UTC)
    private val interrupted = LocalDate.of(2098, 1, 1)
    private val nextDay = interrupted.plusDays(1)

    private fun sql(query: String) = dataSource.connection.use { connection ->
        connection.prepareStatement(query).use { it.executeUpdate() }
    }

    private fun state(period: LocalDate): Pair<String, Long?> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT status, missing_loans FROM provisioning_cycle_run WHERE period = ?",
        ).use { statement ->
            statement.setObject(1, period)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getString(1) to result.getObject(2, java.lang.Long::class.java)?.toLong()
            }
        }
    }

    @Test
    fun `whole days without a scheduler run remain visible after restart`() {
        val first = LocalDate.of(2097, 2, 27)
        val resumed = LocalDate.of(2097, 3, 2)
        sql("DELETE FROM provisioning_cycle_run WHERE period BETWEEN '2097-02-27' AND '2097-03-02'")
        try {
            VertxContextSupport.subscribeAndAwait { runs.markStarted(first, at.minusYears(1)) }
            VertxContextSupport.subscribeAndAwait { runs.markResult(first, 0, at.minusYears(1)) }
            // No scheduler invocation on February 28 or March 1, including a date rollover.
            VertxContextSupport.subscribeAndAwait { runs.markStarted(resumed, at.minusYears(1).plusDays(3)) }
            VertxContextSupport.subscribeAndAwait { runs.markResult(resumed, 0, at.minusYears(1).plusDays(3)) }

            assertThat(state(first)).isEqualTo("COMPLETE" to 0L)
            assertThat(state(first.plusDays(1))).isEqualTo("MISSED" to null)
            assertThat(state(first.plusDays(2))).isEqualTo("MISSED" to null)
            assertThat(state(resumed)).isEqualTo("COMPLETE" to 0L)
            assertThat(VertxContextSupport.subscribeAndAwait { runs.countUnresolvedBefore(resumed.plusDays(1)) })
                .isEqualTo(2)
        } finally {
            sql("DELETE FROM provisioning_cycle_run WHERE period BETWEEN '2097-02-27' AND '2097-03-02'")
        }
    }

    @Test
    fun `interrupted or incomplete prior day survives a successful later day`() {
        sql("DELETE FROM provisioning_cycle_run WHERE period IN ('2098-01-01', '2098-01-02')")
        try {
            VertxContextSupport.subscribeAndAwait { runs.markStarted(interrupted, at) }
            // Simulate process death after individual loan transactions committed: no result write.
            VertxContextSupport.subscribeAndAwait { runs.markStarted(nextDay, at.plusDays(1)) }
            VertxContextSupport.subscribeAndAwait { runs.markResult(nextDay, 0, at.plusDays(1)) }

            assertThat(state(interrupted)).isEqualTo("RUNNING" to null)
            assertThat(state(nextDay)).isEqualTo("COMPLETE" to 0L)
            assertThat(VertxContextSupport.subscribeAndAwait { runs.countUnresolvedBefore(nextDay.plusDays(1)) })
                .isEqualTo(1)

            // A measured shortfall is likewise retained, with its count for reconciliation.
            VertxContextSupport.subscribeAndAwait { runs.markStarted(interrupted, at.plusHours(1)) }
            VertxContextSupport.subscribeAndAwait { runs.markResult(interrupted, 2, at.plusHours(1)) }
            assertThat(state(interrupted)).isEqualTo("INCOMPLETE" to 2L)
            assertThat(VertxContextSupport.subscribeAndAwait { runs.countUnresolvedBefore(nextDay.plusDays(1)) })
                .isEqualTo(1)
        } finally {
            sql("DELETE FROM provisioning_cycle_run WHERE period IN ('2098-01-01', '2098-01-02')")
        }
    }

    @Test
    fun `scheduler commits run evidence through real reactive transactions`() {
        val date = LocalDate.of(2098, 1, 3)
        sql("DELETE FROM provisioning_cycle_run WHERE period = '2098-01-03'")
        try {
            val cycle = mockk<RunProvisioningCycleUseCase> {
                every { runProvisioningCycle(any(), any(), any()) } returns
                    Uni.createFrom().item(ProvisioningRunOutcome(date.toString(), 0, 0))
            }
            val coverage = mockk<ProvisioningCoverageRepository> {
                every { countEligibleForProvisioning() } returns Uni.createFrom().item(0L)
                every { countForPeriod(any()) } returns Uni.createFrom().item(0L)
                every { countUnprovisioned(any()) } returns Uni.createFrom().item(0L)
            }
            val scheduler = ProvisioningCycleScheduler(
                cycle,
                10,
                Clock.fixed(Instant.parse("2098-01-03T04:00:00Z"), ZoneOffset.UTC),
                mockk<DomainMetrics>(relaxed = true),
                coverage,
                runs,
                null,
            )

            VertxContextSupport.subscribeAndAwait { scheduler.runProvisioningPass() }

            assertThat(state(date)).isEqualTo("COMPLETE" to 0L)
        } finally {
            sql("DELETE FROM provisioning_cycle_run WHERE period = '2098-01-03'")
        }
    }

    @Test
    fun `unreadable coverage is stored as unresolved with an unknown missing count`() {
        val date = LocalDate.of(2098, 1, 4)
        sql("DELETE FROM provisioning_cycle_run WHERE period = '2098-01-04'")
        try {
            VertxContextSupport.subscribeAndAwait { runs.markStarted(date, at.plusDays(3)) }
            VertxContextSupport.subscribeAndAwait { runs.markResult(date, null, at.plusDays(3)) }

            assertThat(state(date)).isEqualTo("INCOMPLETE" to null)
            assertThat(VertxContextSupport.subscribeAndAwait { runs.countUnresolvedBefore(date.plusDays(1)) })
                .isEqualTo(1)
        } finally {
            sql("DELETE FROM provisioning_cycle_run WHERE period = '2098-01-04'")
        }
    }
}
