// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.sanctions.application.port.`in`.ReviewCommand
import com.openbank.sanctions.application.port.`in`.SanctionsUseCase
import com.openbank.sanctions.application.port.`in`.ScreenEntityCommand
import com.openbank.sanctions.application.usecase.SanctionsMonitoringService
import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.MonitoringRunOutcome
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsEntryRepositoryImpl
import com.openbank.sanctions.it.MonitoringFixture.Companion.COUNTRY
import com.openbank.sanctions.it.MonitoringFixture.Companion.ENTRY_PREFIX
import com.openbank.sanctions.it.MonitoringFixture.Companion.LISTED
import com.openbank.sanctions.it.MonitoringFixture.Companion.OTHER_LISTED
import com.openbank.sanctions.it.MonitoringFixture.Companion.OWNER
import com.openbank.sanctions.it.MonitoringFixture.Companion.SOURCE_LIST
import com.openbank.sanctions.it.MonitoringFixture.Companion.SUBJECT_PREFIX
import com.openbank.sanctions.it.MonitoringFixture.Companion.UNLISTED
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Ongoing monitoring against real Postgres, with the feature switched on (ADR-0376).
 *
 * Entries are ingested through the importer's own upsert, so the change journal and the
 * monitoring queue are filled by the migration-installed triggers and not by the test. Subjects
 * are registered over real HTTP. The two scheduled paths are invoked directly on an event loop:
 * the scheduler is off under `%test`, so what these tests establish is the behaviour of a run,
 * not that the cron fires.
 *
 * The outbox dispatcher is off for this class because the same-transaction assertion compares
 * `xmin`, which a dispatcher's status update would replace.
 */
@QuarkusTest
@QuarkusTestResource(value = SanctionsOngoingMonitoringIT.MonitoringEnabled::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
class SanctionsOngoingMonitoringIT {

    class MonitoringEnabled : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = mapOf(
            "openbank.sanctions.monitoring.enabled" to "true",
            "openbank.outbox.dispatch-enabled" to "false",
            "quarkus.kafka.devservices.enabled" to "false",
        )

        override fun stop() = Unit
    }

    @Inject
    lateinit var monitoring: SanctionsMonitoringService

    @Inject
    lateinit var screening: SanctionsUseCase

    @Inject
    lateinit var entries: SanctionsEntryRepositoryImpl

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var mapper: ObjectMapper

    private lateinit var db: MonitoringFixture

    @BeforeEach
    fun setUp() {
        db = MonitoringFixture(dataSource, entries)
        db.cleanUp()
    }

    @AfterEach
    fun tearDown() = db.cleanUp()

    private fun ref() = SUBJECT_PREFIX + UUID.randomUUID()

    private fun register(subjectRef: String, name: String, country: String? = null, aliases: String = "[]"): Response =
        RestAssured.given().contentType("application/json")
            .body(
                """{"entityType":"INDIVIDUAL","name":"$name","aliases":$aliases""" +
                    (country?.let { ""","country":"$it"""" } ?: "") + "}",
            )
            .put("/api/v3/sanctions/monitored-subjects/$OWNER/$subjectRef")

    private fun deregister(subjectRef: String): Response =
        RestAssured.given().delete("/api/v3/sanctions/monitored-subjects/$OWNER/$subjectRef")

    private fun delta(): MonitoringRunOutcome = db.onEventLoop { monitoring.runDelta() }

    private fun sweep(): MonitoringRunOutcome = db.onEventLoop { monitoring.runSweep() }

    private class Finding(val id: String, val kind: String, val entryRef: String, val detection: String, val at: Instant)

    private fun findings(subjectRef: String): List<Finding> = db.query(
        "SELECT f.id::text, f.match_kind, f.list_entry_ref, f.detection, f.detected_at " +
            "FROM sanctions_monitoring_findings f JOIN sanctions_monitored_subjects s ON s.id = f.subject_id " +
            "WHERE s.subject_ref = '$subjectRef' ORDER BY f.detected_at, f.id",
    ) { Finding(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getTimestamp(5).toInstant()) }

    private fun events(): List<JsonNode> = db.query(
        "SELECT payload FROM sanctions_outbox WHERE event_type = 'MONITORED_SUBJECT_MATCHED' ORDER BY id",
    ) { mapper.readTree(it.getString(1)) }

    private fun pending(): Long = db.count("sanctions_monitoring_pending")

    @Test
    fun `a clean subject matched by a newly ingested entry yields one finding and one outbox row in one transaction`() {
        val subject = ref()
        assertThat(register(subject, LISTED).statusCode).isEqualTo(201)
        assertThat(sweep()).isEqualTo(MonitoringRunOutcome.COMPLETED)
        assertThat(findings(subject)).describedAs("the subject is clean before the entry exists").isEmpty()

        val before = Instant.now().minusSeconds(1)
        db.ingest("${ENTRY_PREFIX}one", LISTED)
        assertThat(pending()).describedAs("the ingested entry reached the delta queue").isEqualTo(1)
        assertThat(delta()).isEqualTo(MonitoringRunOutcome.COMPLETED)
        val after = Instant.now().plusSeconds(1)

        val finding = findings(subject).single()
        assertThat(finding.kind).isEqualTo("SANCTIONS")
        assertThat(finding.entryRef).isEqualTo("id:${ENTRY_PREFIX}one")
        assertThat(finding.detection).isEqualTo("LIST_DELTA")
        assertThat(finding.at).isBetween(before, after)
        assertThat(pending()).describedAs("a matched change leaves the queue").isZero()

        // One transaction wrote both rows: they carry the same xmin. Two transactions cannot.
        val writers = db.query(
            "SELECT f.xmin::text, o.xmin::text FROM sanctions_monitoring_findings f " +
                "JOIN sanctions_outbox o ON o.aggregate_id = f.id WHERE f.id = '${finding.id}'",
        ) { it.getString(1) to it.getString(2) }
        assertThat(writers).hasSize(1)
        assertThat(writers.single().first).isEqualTo(writers.single().second)

        val event = events().single()
        assertThat(event["findingId"].asText()).isEqualTo(finding.id)
        assertThat(event["schemaVersion"].asInt()).isEqualTo(1)
        assertThat(event["ownerService"].asText()).isEqualTo(OWNER)
        assertThat(event["subjectRef"].asText()).isEqualTo(subject)
        assertThat(event["matchKind"].asText()).isEqualTo("SANCTIONS")
        assertThat(event["matchStatus"].asText()).isEqualTo("HIT")
        assertThat(event["listType"].asText()).isEqualTo("OFAC_SDN")
        assertThat(event["listEntryRef"].asText()).isEqualTo("id:${ENTRY_PREFIX}one")
        assertThat(event["listEntryVersion"].asText()).isNotBlank()
        assertThat(event["matchScore"].asDouble()).isGreaterThanOrEqualTo(0.85)
        assertThat(Instant.parse(event["occurredAt"].asText())).isBetween(before, after)
        assertThat(event.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(*EVENT_FIELDS)
    }

    @Test
    fun `re-ingesting the same entry and re-running either path adds nothing`() {
        val subject = ref()
        register(subject, LISTED)
        db.ingest("${ENTRY_PREFIX}same", LISTED)
        delta()
        assertThat(findings(subject)).hasSize(1)

        // The same content again: the upsert writes nothing, so nothing is queued.
        db.ingest("${ENTRY_PREFIX}same", LISTED)
        assertThat(db.lastIngestWrote).isZero()
        assertThat(pending()).isZero()
        // The same change delivered twice, and the sweep reaching the same entry: still one finding.
        db.sql(
            "INSERT INTO sanctions_monitoring_pending (entry_id, list_type, external_id, active) " +
                "SELECT id, list_type, external_id, TRUE FROM sanctions_entries WHERE external_id = '${ENTRY_PREFIX}same'",
        )
        assertThat(delta()).isEqualTo(MonitoringRunOutcome.COMPLETED)
        assertThat(sweep()).isEqualTo(MonitoringRunOutcome.COMPLETED)
        db.sql("UPDATE sanctions_monitored_subjects SET last_swept_at = last_swept_at - INTERVAL '31 days'")
        assertThat(sweep()).isEqualTo(MonitoringRunOutcome.COMPLETED)

        assertThat(findings(subject)).hasSize(1)
        assertThat(events()).hasSize(1)
    }

    @Test
    fun `an entry whose names change is a new version and raises a second finding`() {
        val subject = ref()
        register(subject, LISTED)
        db.ingest("${ENTRY_PREFIX}versioned", LISTED)
        delta()
        db.ingest("${ENTRY_PREFIX}versioned", LISTED, "Zorvath Q. Brook")
        delta()

        val versions = events().map { it["listEntryVersion"].asText() }
        assertThat(versions).hasSize(2).doesNotHaveDuplicates()
        assertThat(findings(subject).map { it.entryRef }.distinct()).containsExactly("id:${ENTRY_PREFIX}versioned")
    }

    @Test
    fun `a cleared name match stays silent while a jurisdiction match still fires`() {
        db.sql(
            "INSERT INTO sanctions_jurisdictions " +
                "(list_type, source_list, country_code, measure, version, source_ref, published_on, verified_on) " +
                "VALUES ('FATF_HIGH_RISK', '$SOURCE_LIST', '$COUNTRY', 'HIGH_RISK', 1, " +
                "'synthetic monitoring fixture', DATE '2026-01-01', DATE '2026-01-01')",
        )
        db.ingest("${ENTRY_PREFIX}cleared", LISTED)
        val cleared = ref()
        val control = ref()
        // The analyst reviewed this subject against this entry and cleared it, as kyc-service would.
        val check = db.onEventLoop {
            screening.screen(
                ScreenEntityCommand(
                    idempotencyKey = UUID.randomUUID().toString(),
                    entityType = EntityType.INDIVIDUAL,
                    name = LISTED,
                    subjectId = cleared,
                    profile = "CUSTOMER",
                    country = COUNTRY,
                ),
            )
        }
        assertThat(check.status).isEqualTo(SanctionsCheckStatus.HIT)
        db.onEventLoop {
            screening.review(ReviewCommand(check.id, "analyst-1", "not the listed person", SanctionsCheckStatus.WHITELISTED))
        }
        register(cleared, LISTED, country = COUNTRY)
        register(control, LISTED)

        // The entry changes; both subjects' names match the new version.
        db.ingest("${ENTRY_PREFIX}cleared", LISTED, "Z. Quellinbrook")
        assertThat(delta()).isEqualTo(MonitoringRunOutcome.COMPLETED)

        assertThat(findings(control).map { it.kind }).describedAs("control: the delta did match").containsExactly("SANCTIONS")
        assertThat(findings(cleared)).describedAs("the clearance covers the name match").isEmpty()

        assertThat(sweep()).isEqualTo(MonitoringRunOutcome.COMPLETED)

        val afterSweep = findings(cleared)
        assertThat(afterSweep.map { it.kind }).containsExactly("JURISDICTION")
        assertThat(afterSweep.single().entryRef).isEqualTo("id:country:$COUNTRY:$SOURCE_LIST:HIGH_RISK:v1")
        assertThat(afterSweep.single().detection).isEqualTo("BASELINE_SWEEP")
        assertThat(findings(control)).describedAs("the sweep re-found the same version and raised nothing").hasSize(1)
    }

    @Test
    fun `a PEP list entry raises a finding of its own kind`() {
        val subject = ref()
        register(subject, OTHER_LISTED)
        db.ingest("${ENTRY_PREFIX}pep", OTHER_LISTED, list = SanctionsListType.PEP_GLOBAL)
        delta()

        assertThat(findings(subject).map { it.kind }).containsExactly("PEP")
    }

    @Test
    fun `deregistration deletes the subject and its findings and stops further findings`() {
        val subject = ref()
        val other = ref()
        register(subject, LISTED)
        register(other, UNLISTED)
        db.ingest("${ENTRY_PREFIX}gone", LISTED)
        delta()
        assertThat(findings(subject)).hasSize(1)

        assertThat(deregister(subject).statusCode).isEqualTo(204)

        assertThat(db.count("sanctions_monitored_subjects", "subject_ref = '$subject'")).isZero()
        assertThat(db.count("sanctions_monitoring_findings")).isZero()
        assertThat(db.count("sanctions_monitored_subjects", "subject_ref = '$other'")).isEqualTo(1)

        // The entry changes again. The other subject keeps the queue alive; nobody matches now.
        db.ingest("${ENTRY_PREFIX}gone", LISTED, "Zorvath Quellinbrooke")
        assertThat(pending()).isEqualTo(1)
        assertThat(delta()).isEqualTo(MonitoringRunOutcome.COMPLETED)
        assertThat(db.count("sanctions_monitoring_findings")).isZero()
        assertThat(events()).describedAs("only the event raised before deregistration exists").hasSize(1)
        assertThat(deregister(subject).statusCode).describedAs("deregistration is idempotent").isEqualTo(204)
    }

    @Test
    fun `an entry that is delisted and listed again raises a new finding`() {
        val subject = ref()
        register(subject, LISTED)
        db.ingest("${ENTRY_PREFIX}relisted", LISTED)
        delta()
        db.sql("UPDATE sanctions_entries SET active = FALSE WHERE external_id = '${ENTRY_PREFIX}relisted'")
        delta()
        assertThat(db.count("sanctions_monitoring_findings", "delisted_at IS NOT NULL")).isEqualTo(1)

        db.ingest("${ENTRY_PREFIX}relisted", LISTED)
        delta()

        assertThat(findings(subject)).hasSize(2)
        assertThat(db.count("sanctions_monitoring_findings", "delisted_at IS NULL")).isEqualTo(1)
    }

    @Test
    fun `a backlog over its bound is handed to the sweep, which still finds the match`() {
        val subject = ref()
        register(subject, LISTED)
        assertThat(sweep()).isEqualTo(MonitoringRunOutcome.COMPLETED)
        db.ingest("${ENTRY_PREFIX}overflow", LISTED)
        db.sql(
            "INSERT INTO sanctions_monitoring_pending (entry_id, list_type, external_id, active) " +
                "SELECT gen_random_uuid(), 'OFAC_SDN', '${ENTRY_PREFIX}filler-' || n, TRUE FROM generate_series(1, 20001) n",
        )

        assertThat(delta()).isEqualTo(MonitoringRunOutcome.RESWEEP_REQUESTED)
        assertThat(pending()).isZero()
        assertThat(findings(subject)).describedAs("the delta did not match entry by entry").isEmpty()

        assertThat(sweep()).isEqualTo(MonitoringRunOutcome.COMPLETED)
        assertThat(findings(subject).map { it.detection }).containsExactly("PERIODIC_SWEEP")
    }

    @Test
    fun `registration is idempotent and an update replaces the attributes`() {
        val subject = ref()
        val before = Instant.now().minusSeconds(1)
        val created = register(subject, UNLISTED)
        assertThat(created.statusCode).isEqualTo(201)
        assertThat(created.jsonPath().getMap<String, Any>("").keys)
            .containsExactlyInAnyOrder("ownerService", "subjectRef", "registeredAt", "updatedAt")
        assertThat(Instant.parse(created.jsonPath().getString("registeredAt"))).isBetween(before, Instant.now().plusSeconds(1))
        sweep()

        val again = register(subject, UNLISTED)
        assertThat(again.statusCode).isEqualTo(200)
        assertThat(again.jsonPath().getString("updatedAt")).isEqualTo(created.jsonPath().getString("updatedAt"))
        assertThat(db.count("sanctions_monitored_subjects", "subject_ref = '$subject' AND last_swept_at IS NOT NULL"))
            .describedAs("an unchanged registration does not force a new baseline").isEqualTo(1)

        val updated = register(subject, LISTED)
        assertThat(updated.statusCode).isEqualTo(200)
        assertThat(db.count("sanctions_monitored_subjects", "subject_ref = '$subject'")).isEqualTo(1)
        assertThat(db.count("sanctions_monitored_subjects", "subject_ref = '$subject' AND name = '$LISTED' AND last_swept_at IS NULL"))
            .describedAs("new attributes replace the old and are due for a baseline").isEqualTo(1)
    }

    @Test
    fun `malformed registrations are client errors and store nothing`() {
        val subject = ref()
        assertThat(register(subject, UNLISTED, aliases = "[null]").statusCode).isEqualTo(400)
        assertThat(register(subject, " ").statusCode).isEqualTo(400)
        assertThat(register(subject, UNLISTED, country = "XYZ").statusCode).isEqualTo(400)
        // A NUL arrives escaped inside JSON; only the decoded value shows it.
        assertThat(register(subject, "Nul\\u0000Byte").statusCode).isEqualTo(400)
        val badOwner = RestAssured.given().contentType("application/json")
            .body("""{"entityType":"INDIVIDUAL","name":"$UNLISTED"}""")
            .put("/api/v3/sanctions/monitored-subjects/Not_A_Service/$subject")
        assertThat(badOwner.statusCode).isEqualTo(400)

        assertThat(db.count("sanctions_monitored_subjects")).isZero()
    }

    private companion object {
        val EVENT_FIELDS = arrayOf(
            "schemaVersion", "aggregateId", "aggregateType", "findingId", "ownerService", "subjectRef",
            "entityType", "matchKind", "listType", "listEntryRef", "listEntryVersion", "matchScore", "matchStatus",
            "detection", "occurredAt", "sourceService",
        )
    }
}
