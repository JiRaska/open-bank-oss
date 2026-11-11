// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.integration

import com.openbank.lending.application.port.out.AllowancePostings
import com.openbank.lending.application.port.out.AllowancePostingsPort
import com.openbank.lending.application.port.out.CloseJobRunPort
import com.openbank.lending.infrastructure.persistence.repository.LendingOutboxRepositoryImpl
import com.openbank.libs.close.CloseJobRun
import com.openbank.libs.close.CloseOutcome
import com.openbank.libs.close.CloseParticipationAssembler
import com.openbank.libs.close.CloseParticipationReport
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/** This isolated database owns the V29-to-V30 upgrade and the production retention path. */
@QuarkusTest
@TestProfile(AllowanceDeliveryRetentionIT.NoDispatch::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "allowance_delivery_retention_it")],
    restrictToAnnotatedClass = true,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AllowanceDeliveryRetentionIT {
    class NoDispatch : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "false",
            "quarkus.oidc-client.enabled" to "false",
            "quarkus.kafka.devservices.enabled" to "false",
            "quarkus.redis.devservices.enabled" to "false",
            "openbank.outbox.dispatch-enabled" to "false",
            "mp.messaging.outgoing.lending-events-out.connector" to "smallrye-in-memory",
        )
    }

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var outbox: LendingOutboxRepositoryImpl

    @Inject lateinit var postings: AllowancePostingsPort

    @Inject lateinit var runs: CloseJobRunPort

    private val day = LocalDate.of(2026, 1, 10)
    private val sentAt = Instant.parse("2026-01-10T08:00:00Z")
    private var firstTest = true

    @BeforeEach
    fun resetOwnedFixtures() {
        if (firstTest) {
            // Check the actual fresh migration result before any fixture changes its coverage.
            assertThat(scalar("SELECT starts_on FROM lending_allowance_receipt_coverage")).isEqualTo("0001-01-01")
            firstTest = false
        }
        sql("TRUNCATE lending_outbox, lending_allowance_delivery_receipts, lending_close_job_run")
        sql("UPDATE lending_allowance_receipt_coverage SET starts_on = DATE '0001-01-01' WHERE singleton")
    }

    @Test
    @Order(1)
    fun `sent retries and real outbox retention preserve the report and durable delivery count`() {
        val eventId = seedPending()
        onContext { outbox.markSent(eventId, sentAt) }
        onContext { outbox.markSent(eventId, sentAt.plusSeconds(30)) }
        assertThat(scalar("SELECT count(*) FROM lending_allowance_delivery_receipts")).isEqualTo("1")
        assertThat(scalar("SELECT count(*) FROM lending_allowance_delivery_receipts WHERE NOT synthetic"))
            .isEqualTo("1")
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT sent_at FROM lending_allowance_delivery_receipts").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getTimestamp(1).toInstant()).isEqualTo(sentAt)
                }
            }
        }
        onContext {
            runs.record(CloseJobRun(day, "provisioning", 1, 0, 0, null, sentAt)).awaitSuspending()
        }
        val beforePostings = postingState()
        val beforeReport = report(beforePostings)
        assertThat(beforePostings).isEqualTo(AllowancePostings(0, 1, 0, covered = true))
        assertThat(beforeReport.outcome).isEqualTo(CloseOutcome.RAN)
        assertThat(beforeReport.sourceRunId).matches("[a-f0-9]{64}")

        val purged = onContext { outbox.purgeSent(Duration.ofDays(7), 100, sentAt.plus(Duration.ofDays(10))) }

        assertThat(purged).isEqualTo(1)
        assertThat(scalar("SELECT count(*) FROM lending_outbox")).isEqualTo("0")
        assertThat(scalar("SELECT count(*) FROM lending_allowance_delivery_receipts")).isEqualTo("1")
        val afterPostings = postingState()
        assertThat(afterPostings).isEqualTo(beforePostings)
        assertThat(report(afterPostings)).isEqualTo(beforeReport)
    }

    @Test
    @Order(2)
    fun `dates before the durable coverage boundary remain explicitly unverified`() {
        sql("UPDATE lending_allowance_receipt_coverage SET starts_on = DATE '${day.plusDays(1)}' WHERE singleton")

        assertThat(postingState().covered).isFalse()
        assertThat(onContext { postings.postingsFor(day.plusDays(1)).awaitSuspending() }.covered).isTrue()
    }

    @Test
    @Order(3)
    fun `unrelated sent events never become allowance delivery receipts`() {
        val unrelated = seedPending("loan.provisioned")

        onContext { outbox.markSent(unrelated, sentAt) }

        assertThat(scalar("SELECT status FROM lending_outbox")).isEqualTo("SENT")
        assertThat(scalar("SELECT count(*) FROM lending_allowance_delivery_receipts")).isEqualTo("0")
        assertThat(postingState().posted).isZero()
    }

    @Test
    @Order(4)
    fun `actual V30 upgrades a populated V29 database without fabricating purged deliveries`() {
        sql(
            // Reconstruct the actual V29 fixture, including removal of later additive observations.
            "DROP TABLE cz_credit_policy_command_receipt; " +
                "DROP TABLE cz_credit_policy_revision; " +
                "DROP FUNCTION check_cz_credit_policy_future_approval(); " +
                "DROP FUNCTION cz_credit_policy_prague_day(); " +
                "DROP FUNCTION protect_cz_credit_policy_revision(); " +
                "DROP TABLE lending_close_required_job; " +
                "DELETE FROM flyway_schema_history WHERE version IN ('31', '32'); " +
                "DROP TRIGGER record_lending_allowance_delivery ON lending_outbox; " +
                "DROP FUNCTION record_lending_allowance_delivery(); " +
                "DROP INDEX idx_lending_allowance_outbox_date_status; " +
                "DROP TABLE lending_allowance_delivery_receipts; " +
                "DROP TABLE lending_allowance_receipt_coverage; " +
                "DELETE FROM flyway_schema_history WHERE version = '30'",
        )
        sql(
            "INSERT INTO lending_close_job_run (business_date, job, items_processed, completed_at) " +
                "VALUES ('$day', 'provisioning', 2, '$sentAt')",
        )
        val surviving = seedPending()
        val alreadyPurged = seedPending()
        sql("UPDATE lending_outbox SET status = 'SENT', sent_at = '$sentAt'")
        sql("DELETE FROM lending_outbox WHERE event_id = '$alreadyPurged'")
        val nextDayBefore = nextPragueDay()

        val migrated = Flyway.configure().dataSource(dataSource)
            .locations("filesystem:${Path.of("src/main/resources/db/migration").toAbsolutePath()}")
            .target("30").validateOnMigrate(false).cleanDisabled(true).load().migrate()

        assertThat(migrated.migrationsExecuted).isEqualTo(1)
        assertThat(scalar("SELECT starts_on FROM lending_allowance_receipt_coverage"))
            .isIn(nextDayBefore, nextPragueDay())
        assertThat(scalar("SELECT count(*) FROM lending_allowance_delivery_receipts")).isEqualTo("1")
        assertThat(scalar("SELECT event_id FROM lending_allowance_delivery_receipts")).isEqualTo(surviving.toString())
        assertThat(scalar("SELECT count(*) FROM lending_allowance_delivery_receipts WHERE event_id = '$alreadyPurged'"))
            .isEqualTo("0")
        assertThat(scalar("SELECT count(*) FROM lending_close_job_run")).isEqualTo("1")
        assertThat(postingState()).isEqualTo(AllowancePostings(0, 1, 0, covered = false))
    }

    private fun report(state: AllowancePostings): CloseParticipationReport = onContext {
        val persisted = runs.findBetween(day, day).awaitSuspending().single()
        requireNotNull(
            CloseParticipationAssembler.assemble(
                day,
                setOf("provisioning"),
                listOf(persisted.copy(journalsPosted = state.posted)),
            ),
        )
    }

    private fun postingState(): AllowancePostings = onContext { postings.postingsFor(day).awaitSuspending() }

    private fun seedPending(type: String = "lending.allowance.posting"): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO lending_outbox " +
                    "(event_id, aggregate_id, event_type, payload, status, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, 'PENDING', ?, ?)",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, UUID.randomUUID())
                statement.setString(3, type)
                statement.setString(4, """{"accountingDate":"$day"}""")
                statement.setObject(5, java.sql.Timestamp.from(sentAt))
                statement.setObject(6, java.sql.Timestamp.from(sentAt))
                statement.executeUpdate()
            }
        }
        return id
    }

    private fun nextPragueDay(): String = scalar("SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Prague')::date + 1")

    private fun scalar(query: String): String = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { rows ->
                assertThat(rows.next()).isTrue()
                rows.getString(1)
            }
        }
    }

    private fun sql(query: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute(query) } }
    }

    private fun <T> onContext(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }
}
