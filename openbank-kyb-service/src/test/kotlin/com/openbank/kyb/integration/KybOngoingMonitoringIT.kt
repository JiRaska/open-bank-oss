// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.integration

import com.openbank.kyb.application.usecase.MonitoringRegistrationService
import com.openbank.kyb.application.usecase.MonitoringRunOutcome
import com.openbank.kyb.application.usecase.MonitoringSettings
import com.openbank.kyb.infrastructure.MicrometerKybMonitoringMetrics
import com.openbank.kyb.infrastructure.kafka.MonitoredSubjectMatchedConsumer
import com.openbank.kyb.infrastructure.kafka.MonitoredSubjectMatchedWire
import com.openbank.kyb.it.KybMonitoringJourney
import com.openbank.kyb.it.StubDocumentGateway
import com.openbank.kyb.it.StubMonitoredSubjectRegistry
import com.openbank.kyb.it.StubPartyGateway
import com.openbank.kyb.it.StubRelatedPartyScreening
import com.openbank.libs.testing.containers.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * Ongoing-monitoring registration and findings against a real Postgres, driven over real HTTP
 * (ADR-0376, kyb side). A mocked repository proves nothing here: every claim below is about which
 * rows commit together and what a later run reads back.
 *
 * What is real: the REST surface, the aggregate, both repositories, the migration, the relay, the
 * consumer bean and the meters. What is a double: party-service, document-service, the screen and
 * the monitored-subject registry — the last one records what it was sent and can refuse.
 *
 * The scheduled tick is off under `%test`, so a run happens only when a test calls it, on an event
 * loop (reactive Panache needs a Vert.x context). That the real schedule fires and that a record
 * arriving from a real broker is recognised is `KybMonitoringWireShapeIT`.
 *
 * The switch is turned on by replacing the settings bean per test: a test resource would restart
 * the application, and this class shares one instance and one database with the module's other ITs.
 */
@QuarkusTest
@QuarkusTestResource(KybBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API", "ROLE_KYC"])
class KybOngoingMonitoringIT {

    @Inject lateinit var parties: StubPartyGateway

    @Inject lateinit var documents: StubDocumentGateway

    @Inject lateinit var screening: StubRelatedPartyScreening

    @Inject lateinit var registry: StubMonitoredSubjectRegistry

    @Inject lateinit var registration: MonitoringRegistrationService

    @Inject lateinit var consumer: MonitoredSubjectMatchedConsumer

    @Inject lateinit var meters: MeterRegistry

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    private lateinit var journey: KybMonitoringJourney

    @BeforeEach
    fun setUp() {
        journey = KybMonitoringJourney(parties, documents, jdbcUrl)
        registry.failWith = null
        switch(enabled = true)
        // Rows other tests of the module left pending would be sent by this test's runs.
        journey.execute("update kyb_monitored_subjects set sync_state = 'SYNCED', pending_since = null")
    }

    @AfterEach
    fun tearDown() {
        registry.failWith = null
        journey.execute("drop trigger if exists monitoring_it_refuse on kyb_monitored_subjects")
        journey.execute("drop trigger if exists monitoring_it_refuse on kyb_outbox")
    }

    private fun switch(enabled: Boolean) = QuarkusMock.installMockForType(
        MonitoringSettings(enabled, BATCH, Duration.ofDays(7), OWNER),
        MonitoringSettings::class.java,
    )

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun run(): MonitoringRunOutcome = onEventLoop { registration.run() }

    private fun counted(name: String, vararg tags: String): Double = meters.find(name).tags(*tags).counter()?.count() ?: 0.0

    private fun registeredFor(caseId: String) =
        journey.rows(caseId).map { it.subjectRef }.let { refs -> registry.registered.filter { it.subjectRef in refs } }

    /** Refuses every insert into [table] for this case, from inside whichever transaction attempts it. */
    private fun refuseInserts(table: String, column: String, caseId: String) {
        journey.execute(
            "create or replace function monitoring_it_refuse_$table() returns trigger language plpgsql as " +
                "\$f\$ begin if new.$column = '$caseId'::uuid then raise exception 'refused by the test'; end if; " +
                "return new; end \$f\$",
        )
        journey.execute(
            "create trigger monitoring_it_refuse before insert on $table " +
                "for each row execute function monitoring_it_refuse_$table()",
        )
    }

    @Test
    fun `approval and what it owes the registry commit in one transaction, under opaque references`() {
        switch(enabled = false)
        val (caseId, ceremonyId) = journey.readyToSign("71000054")
        assertThat(journey.rows(caseId)).describedAs("nobody is owed to the registry before approval").isEmpty()

        assertThat(journey.sign(caseId, ceremonyId)).isEqualTo(200)

        val case = journey.stored(caseId)
        val rows = journey.rows(caseId)
        assertThat(case.status).isEqualTo("SIGNED")
        assertThat(rows).hasSize(2)
        assertThat(rows).allMatch { it.desired == "REGISTERED" && it.syncState == "PENDING" }
        // One transaction id on the case row and on both rows: they committed together or not at all.
        assertThat(rows.map { it.xmin }.toSet()).containsExactly(case.xmin)

        // The entity is registered under its party id, the person under a random UUID, and both
        // are what the onboarding screen sent as subjectId — so a clearance binds in the registry.
        val person = rows.single { it.subjectRef != case.entityPartyId }.subjectRef
        assertThat(rows.map { it.subjectRef }).contains(case.entityPartyId)
        assertThat(UUID.fromString(person).version()).isEqualTo(4)
        val screened = screening.requests.filter { it.idempotencyKey.startsWith("kyb-screen:$caseId:") }
        assertThat(screened.map { it.subjectId }.toSet()).isEqualTo(rows.map { it.subjectRef }.toSet())
        assertThat(screened.map { it.subjectId }).noneMatch { it.contains("kyb-case") || it.contains("Sledovan") }
        // The store itself holds no name at all.
        assertThat(
            journey.query("select row_to_json(m)::text from kyb_monitored_subjects m where case_id = ?", UUID.fromString(caseId)) {
                it.getString(1)
            },
        ).noneMatch { it.contains("Sledovan") || it.contains("Příklad") }

        // Switched off: a run sends nothing and the rows stay owed.
        assertThat(run()).isEqualTo(MonitoringRunOutcome.DISABLED)
        assertThat(registeredFor(caseId)).isEmpty()
        assertThat(journey.rows(caseId)).allMatch { it.syncState == "PENDING" }
    }

    @Test
    fun `neither half is written without the other`() {
        val (caseId, ceremonyId) = journey.readyToSign("71000062")

        // The approval event cannot be written: no registration may survive it.
        refuseInserts("kyb_outbox", "aggregate_id", caseId)
        assertThat(journey.sign(caseId, ceremonyId)).isEqualTo(500)
        assertThat(journey.stored(caseId).status).isEqualTo("READY_TO_SIGN")
        assertThat(journey.rows(caseId)).isEmpty()
        journey.execute("drop trigger monitoring_it_refuse on kyb_outbox")

        // The registration cannot be written: the case may not be approved without it.
        refuseInserts("kyb_monitored_subjects", "case_id", caseId)
        assertThat(journey.sign(caseId, ceremonyId)).isEqualTo(500)
        assertThat(journey.stored(caseId).status).isEqualTo("READY_TO_SIGN")
        assertThat(journey.events(caseId)).doesNotContain("BUSINESS_AGREEMENT_SIGNED")
        assertThat(journey.rows(caseId)).isEmpty()
        journey.execute("drop trigger monitoring_it_refuse on kyb_monitored_subjects")

        // Control: with nothing in the way the same request approves and registers.
        assertThat(journey.sign(caseId, ceremonyId)).isEqualTo(200)
        assertThat(journey.stored(caseId).status).isEqualTo("SIGNED")
        assertThat(journey.events(caseId)).contains("BUSINESS_AGREEMENT_SIGNED")
        assertThat(journey.rows(caseId)).hasSize(2)
    }

    @Test
    fun `the relay retries a refusal with backoff, registers what the case carries, and re-asserts`() {
        val caseId = journey.approved("71000071")
        val id = UUID.fromString(caseId)
        val failedBefore = counted(MicrometerKybMonitoringMetrics.REGISTRATIONS, "outcome", "failed", "reason", "http_503")
        val registeredBefore = counted(MicrometerKybMonitoringMetrics.REGISTRATIONS, "outcome", "registered")

        // The registry refuses (what it answers while its own switch is off).
        registry.failWith = "http_503"
        assertThat(run()).isEqualTo(MonitoringRunOutcome.COMPLETED)
        assertThat(journey.rows(caseId)).allMatch {
            it.syncState == "PENDING" && it.attemptCount == 1 && it.lastError == "http_503"
        }
        assertThat(counted(MicrometerKybMonitoringMetrics.REGISTRATIONS, "outcome", "failed", "reason", "http_503"))
            .isEqualTo(failedBefore + 2)
        // Not due yet: an immediate second run does not hammer the registry.
        run()
        assertThat(journey.rows(caseId)).allMatch { it.attemptCount == 1 }

        // The registry recovers and the backoff elapses.
        registry.failWith = null
        journey.execute("update kyb_monitored_subjects set next_attempt_at = now() - interval '1 second' where case_id = ?", id)
        run()
        val case = journey.stored(caseId)
        assertThat(registeredFor(caseId)).containsExactlyInAnyOrder(
            StubMonitoredSubjectRegistry.Registration(case.entityPartyId!!, true, "Příklad s.r.o."),
            StubMonitoredSubjectRegistry.Registration(
                journey.rows(caseId).single { it.subjectRef != case.entityPartyId }.subjectRef,
                false,
                "Oskar Opakovaný",
            ),
        )
        assertThat(journey.rows(caseId)).allMatch { it.syncState == "SYNCED" && it.lastError == null }
        assertThat(counted(MicrometerKybMonitoringMetrics.REGISTRATIONS, "outcome", "registered"))
            .isEqualTo(registeredBefore + 2)

        // Confirmed and recent: nothing is sent again.
        run()
        assertThat(registeredFor(caseId)).hasSize(2)
        // Confirmed long ago: sent again, which is how a registry that lost its state is repaired.
        journey.execute("update kyb_monitored_subjects set synced_at = now() - interval '8 days' where case_id = ?", id)
        run()
        assertThat(registeredFor(caseId)).hasSize(4)
        assertThat(journey.rows(caseId)).allMatch { it.syncState == "SYNCED" }
    }

    @Test
    fun `rejecting an approved case withdraws everyone, in the transaction of the rejection`() {
        val caseId = journey.approved("71000089")
        run()
        val refs = journey.rows(caseId).map { it.subjectRef }
        assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" && it.syncState == "SYNCED" }

        journey.operator().body("""{"reason":"relationship ended by the bank"}""")
            .post("/api/v1/kyb/cases/$caseId/reject").then().statusCode(200).body("status", equalTo("REJECTED"))

        val case = journey.stored(caseId)
        assertThat(journey.rows(caseId)).allMatch { it.desired == "WITHDRAWN" && it.syncState == "PENDING" }
        assertThat(journey.rows(caseId).map { it.xmin }.toSet()).containsExactly(case.xmin)
        assertThat(registry.withdrawn).doesNotContainAnyElementsOf(refs)

        run()
        assertThat(registry.withdrawn).containsAll(refs)
        assertThat(journey.rows(caseId)).allMatch { it.desired == "WITHDRAWN" && it.syncState == "SYNCED" }
        assertThat(counted(MicrometerKybMonitoringMetrics.REGISTRATIONS, "outcome", "withdrawn")).isGreaterThanOrEqualTo(2.0)
    }

    @Test
    fun `a case approved before registration existed is backfilled and given references`() {
        val caseId = journey.approved("71000097")
        val id = UUID.fromString(caseId)
        // What an approval made by an earlier release left behind: no rows, no references.
        journey.execute("delete from kyb_monitored_subjects where case_id = ?", id)
        journey.execute(
            "update kyb_cases set screening_json = regexp_replace(screening_json, '\"ref\":\"[^\"]*\"', '\"ref\":null', 'g') " +
                "where case_id = ?",
            id,
        )
        assertThat(journey.stored(caseId).screeningJson).doesNotContain("\"ref\":\"")

        run()

        val rows = journey.rows(caseId)
        assertThat(rows).hasSize(2)
        assertThat(rows).allMatch { it.desired == "REGISTERED" && it.syncState == "SYNCED" }
        assertThat(registeredFor(caseId).map { it.name }).containsExactlyInAnyOrder("Příklad s.r.o.", "Viktor Doplněný")
        rows.forEach { assertThat(journey.stored(caseId).screeningJson).contains("\"ref\":\"${it.subjectRef}\"") }
    }

    @Test
    @Suppress("LongMethod") // one continuous journey over one stored case, as in KybCaseApiIT
    fun `a finding sends the approved case to review, the applicant sees nothing, and an operator resolves it`() {
        val caseId = journey.approved("71000101")
        run()
        val case = journey.stored(caseId)
        val person = journey.rows(caseId).single { it.subjectRef != case.entityPartyId }.subjectRef
        val eventsBefore = journey.events(caseId)
        val mandatesBefore = parties.mandates.count { it.evidenceRef.contains(caseId) }
        val findingId = UUID.randomUUID()
        val record = {
            MonitoredSubjectMatchedWire.record(
                MonitoredSubjectMatchedWire.EVENT_TYPE,
                MonitoredSubjectMatchedWire.json(MonitoredSubjectMatchedWire.body(person, findingId)),
            )
        }
        val opened = counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "review_opened")
        val duplicates = counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "duplicate")

        onEventLoop { consumer.consume(record()) }

        val reviewed = journey.stored(caseId)
        assertThat(reviewed.status).isEqualTo("MANUAL_REVIEW")
        assertThat(reviewed.reviewFrom).isEqualTo("SIGNED")
        assertThat(reviewed.screeningJson).contains(findingId.toString(), "id:synthetic-entry-1", "POTENTIAL_HIT")
        assertThat(counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "review_opened")).isEqualTo(opened + 1)
        // Nobody is withdrawn because a finding arrived, and no event tells anyone outside.
        assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" && it.syncState == "SYNCED" }
        assertThat(journey.events(caseId)).isEqualTo(eventsBefore)

        // The same finding again: counted as a duplicate, and the row is not written.
        onEventLoop { consumer.consume(record()) }
        assertThat(journey.stored(caseId).xmin).isEqualTo(reviewed.xmin)
        assertThat(counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "duplicate")).isEqualTo(duplicates + 1)

        // The applicant reads the case as it was approved: no review, no reason, no screening.
        journey.customer().get("/api/v1/kyb/cases/$caseId").then().statusCode(200)
            .body("status", equalTo("SIGNED"))
            .body("reviewReason", nullValue())
            .body("screening", nullValue())
            .body("monitoringReview", nullValue())
        // ... and cannot end the relationship while it is under review.
        journey.customer().post("/api/v1/kyb/cases/$caseId/abandon").then().statusCode(400)
        assertThat(journey.stored(caseId).status).isEqualTo("MANUAL_REVIEW")

        // The operator finds it in the existing review queue, with the entry that matched.
        journey.operator().get("/api/v1/kyb/cases?status=MANUAL_REVIEW&size=200").then().statusCode(200)
            .body("find { it.id == '$caseId' }.monitoringReview.from", equalTo("SIGNED"))
        val key = journey.operator().get("/api/v1/kyb/cases/$caseId").then().statusCode(200)
            .body("status", equalTo("MANUAL_REVIEW"))
            .body("screening.openFindings", equalTo(1))
            .body("screening.subjects.find { it.kind == 'REPRESENTATIVE' }.monitoringFindings", hasSize<Any>(1))
            .body(
                "screening.subjects.find { it.kind == 'REPRESENTATIVE' }.monitoringFindings[0].listEntryRef",
                equalTo("id:synthetic-entry-1"),
            )
            .extract().path<String>("screening.subjects.find { it.kind == 'REPRESENTATIVE' }.key")

        // Must-refuse: resolving the review does not decide the finding.
        journey.operator().body("""{"requiredSignatures":1}""").post("/api/v1/kyb/cases/$caseId/review/resolve")
            .then().statusCode(422).body("error", equalTo("SCREENING_FINDING_OPEN"))
        assertThat(journey.stored(caseId).status).isEqualTo("MANUAL_REVIEW")

        journey.operator().body("""{"disposition":"CLEARED_FALSE_POSITIVE","reason":"different year of birth"}""")
            .post("/api/v1/kyb/cases/$caseId/screening/findings/$key/disposition").then().statusCode(200)
        journey.operator().body("""{"requiredSignatures":1}""").post("/api/v1/kyb/cases/$caseId/review/resolve")
            .then().statusCode(200).body("status", equalTo("SIGNED")).body("monitoringReview", nullValue())

        // Back where it stood: nothing of the approval was repeated.
        val resolved = journey.stored(caseId)
        assertThat(resolved.status).isEqualTo("SIGNED")
        assertThat(resolved.reviewFrom).isNull()
        assertThat(journey.events(caseId)).isEqualTo(eventsBefore)
        assertThat(parties.mandates.count { it.evidenceRef.contains(caseId) }).isEqualTo(mandatesBefore)
        assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" }
    }

    @Test
    fun `a finding owned by another service, or about nobody this service registered, changes nothing`() {
        val caseId = journey.approved("71000119")
        run()
        val case = journey.stored(caseId)
        val person = journey.rows(caseId).single { it.subjectRef != case.entityPartyId }.subjectRef
        val foreign = counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "foreign_owner")
        val unknown = counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "unknown_subject")
        val send = { body: Map<String, Any> ->
            onEventLoop {
                consumer.consume(
                    MonitoredSubjectMatchedWire.record(
                        MonitoredSubjectMatchedWire.EVENT_TYPE,
                        MonitoredSubjectMatchedWire.json(body),
                    ),
                )
            }
        }

        // Another owner's finding that happens to name a reference this service holds.
        send(MonitoredSubjectMatchedWire.body(person, ownerService = "openbank-kyc"))
        // This service's finding about a reference it never registered.
        send(MonitoredSubjectMatchedWire.body(UUID.randomUUID().toString()))

        assertThat(journey.stored(caseId).status).isEqualTo("SIGNED")
        assertThat(journey.stored(caseId).xmin).isEqualTo(case.xmin)
        assertThat(counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "foreign_owner")).isEqualTo(foreign + 1)
        assertThat(counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "unknown_subject")).isEqualTo(unknown + 1)

        // Control: the same reference under this service's own name does open the review.
        send(MonitoredSubjectMatchedWire.body(person))
        assertThat(journey.stored(caseId).status).isEqualTo("MANUAL_REVIEW")
    }

    private companion object {
        const val OWNER = "openbank-kyb"
        const val BATCH = 200
    }
}
