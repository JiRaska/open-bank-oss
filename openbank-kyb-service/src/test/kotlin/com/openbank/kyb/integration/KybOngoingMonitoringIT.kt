// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.integration

import com.openbank.kyb.application.port.`in`.BusinessOnboardingUseCase
import com.openbank.kyb.application.port.out.MonitoredSubjectRegistryException
import com.openbank.kyb.application.port.out.MonitoringRegistrationStore
import com.openbank.kyb.application.port.out.RegistryFailure
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
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

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

    @Inject lateinit var store: MonitoringRegistrationStore

    @Inject lateinit var onboarding: BusinessOnboardingUseCase

    @Inject lateinit var meters: MeterRegistry

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    private lateinit var journey: KybMonitoringJourney

    @BeforeEach
    fun setUp() {
        journey = KybMonitoringJourney(parties, documents, jdbcUrl)
        registry.failWith = null
        switch(enabled = true)
        // Rows other tests of the module left owed or refused would be sent by this test's runs.
        journey.execute(
            "update kyb_monitored_subjects set sync_state = 'SYNCED', pending_since = null, updated_at = now() " +
                "where sync_state <> 'SYNCED'",
        )
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

    private fun counted(name: String, vararg tags: String): Double =
        meters.find(name).tags(*tags).counter()?.count() ?: 0.0

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

    private fun finding(subjectRef: String, findingId: UUID = UUID.randomUUID(), status: String = "POTENTIAL_HIT") =
        onEventLoop {
            consumer.consume(
                MonitoredSubjectMatchedWire.record(
                    MonitoredSubjectMatchedWire.EVENT_TYPE,
                    MonitoredSubjectMatchedWire.json(
                        MonitoredSubjectMatchedWire.body(subjectRef, findingId, matchStatus = status),
                    ),
                ),
            )
        }

    private fun personRef(caseId: String): String =
        journey.rows(caseId).single { it.subjectRef != journey.stored(caseId).entityPartyId }.subjectRef

    private fun representativeKey(caseId: String): String = journey.operator().get("/api/v1/kyb/cases/$caseId")
        .then().statusCode(200).extract().path("screening.subjects.find { it.kind == 'REPRESENTATIVE' }.key")

    private fun dispose(caseId: String, disposition: String) {
        journey.operator().body("""{"disposition":"$disposition","reason":"decided in the monitoring IT"}""")
            .post("/api/v1/kyb/cases/$caseId/screening/findings/${representativeKey(caseId)}/disposition")
            .then().statusCode(200)
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
        val screened = screening.requests.filter { it.subjectId in rows.map { r -> r.subjectRef } }
        assertThat(screened.map { it.name }.toSet()).containsExactlyInAnyOrder("Příklad s.r.o.", "Marek Sledovaný")
        assertThat(screened).allMatch { it.idempotencyKey == "kyb-screen:${it.subjectId}" }
        // The store itself holds no name at all.
        assertThat(
            journey.query(
                "select row_to_json(m)::text from kyb_monitored_subjects m where case_id = ?",
                UUID.fromString(caseId),
            ) { it.getString(1) },
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
    fun `a writer that finds the row already being written waits and yields, it does not fail`() {
        val (caseId, ceremonyId) = journey.readyToSign("71000186")
        val id = UUID.fromString(caseId)
        val entityRef = journey.stored(caseId).entityPartyId
        // Another writer of the same case (the backfill, a second request) has inserted the
        // entity's row and not committed yet.
        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { other ->
            other.autoCommit = false
            other.prepareStatement(
                "insert into kyb_monitored_subjects (id, case_id, subject_ref, desired, sync_state, " +
                    "next_attempt_at, pending_since, created_at, updated_at) " +
                    "values (nextval('kyb_monitored_subjects_seq'), ?, ?, 'REGISTERED', 'PENDING', now(), now(), now(), now())",
            ).use {
                it.setObject(1, id)
                it.setString(2, entityRef)
                it.executeUpdate()
            }
            val signing = CompletableFuture.supplyAsync { journey.sign(caseId, ceremonyId) }
            // It has to wait for the other transaction: answering now would be answering blind.
            Thread.sleep(BLOCKED_FOR_MS)
            assertThat(signing).isNotDone()
            other.commit()
            assertThat(
                signing.get(WAIT_SECONDS, TimeUnit.SECONDS),
            ).describedAs("not a 500 on a unique violation").isEqualTo(200)
        }
        assertThat(journey.stored(caseId).status).isEqualTo("SIGNED")
        assertThat(journey.rows(caseId)).hasSize(2)
        assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" }
    }

    @Test
    fun `the relay retries an outage with backoff, registers what the case carries, and re-asserts`() {
        val caseId = journey.approved("71000071")
        val id = UUID.fromString(caseId)
        val failedBefore =
            counted(MicrometerKybMonitoringMetrics.REGISTRATIONS, "outcome", "failed", "reason", "http_503")
        val registeredBefore = counted(MicrometerKybMonitoringMetrics.REGISTRATIONS, "outcome", "registered")

        // The registry is not there (what it answers while its own switch is off).
        registry.failWith = MonitoredSubjectRegistryException("http_503", RegistryFailure.TRANSIENT, null)
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
        journey.execute(
            "update kyb_monitored_subjects set next_attempt_at = now() - interval '1 second' where case_id = ?",
            id,
        )
        run()
        val case = journey.stored(caseId)
        assertThat(registeredFor(caseId)).containsExactlyInAnyOrder(
            StubMonitoredSubjectRegistry.Registration(case.entityPartyId!!, true, "Příklad s.r.o."),
            StubMonitoredSubjectRegistry.Registration(personRef(caseId), false, "Oskar Opakovaný"),
        )
        assertThat(journey.rows(caseId)).allMatch { it.syncState == "SYNCED" && it.lastError == null }
        assertThat(counted(MicrometerKybMonitoringMetrics.REGISTRATIONS, "outcome", "registered"))
            .isEqualTo(registeredBefore + 2)

        // Confirmed and recent: nothing is sent again.
        run()
        assertThat(registeredFor(caseId)).hasSize(2)
        // Confirmed long ago: sent again, which is how a registry that lost its state is repaired.
        journey.execute(
            "update kyb_monitored_subjects set updated_at = now() - interval '8 days' where case_id = ?",
            id,
        )
        run()
        assertThat(registeredFor(caseId)).hasSize(4)
        assertThat(journey.rows(caseId)).allMatch { it.syncState == "SYNCED" }
    }

    @Test
    fun `a subject the registry refuses leaves the backlog, is shown to the operator, and is tried again later`() {
        val caseId = journey.approved("71000194")
        val id = UUID.fromString(caseId)
        val refusedBefore =
            counted(MicrometerKybMonitoringMetrics.REGISTRATIONS, "outcome", "refused", "reason", "http_400")

        // The registry will not take the subject as it is: a retry cannot change that.
        registry.failWith = MonitoredSubjectRegistryException("http_400", RegistryFailure.REFUSED, null)
        run()
        assertThat(journey.rows(caseId)).allMatch { it.syncState == "REFUSED" && it.lastError == "http_400" }
        assertThat(counted(MicrometerKybMonitoringMetrics.REGISTRATIONS, "outcome", "refused", "reason", "http_400"))
            .isEqualTo(refusedBefore + 2)
        // Out of the retry loop and out of the backlog the critical alert reads ...
        journey.execute(
            "update kyb_monitored_subjects set next_attempt_at = now() - interval '1 hour' where case_id = ?",
            id,
        )
        run()
        assertThat(journey.rows(caseId)).allMatch { it.attemptCount == 1 }
        val stats = onEventLoop { store.stats(Instant.now()) }
        assertThat(stats.pending).isZero()
        assertThat(stats.oldestPendingAgeSeconds).isZero()
        assertThat(stats.refused).isEqualTo(2)
        // ... and in front of an operator, on the case; never in front of the customer.
        journey.operator().get("/api/v1/kyb/cases/$caseId").then().statusCode(200)
            .body("screening.subjects.find { it.kind == 'REPRESENTATIVE' }.monitoringState", equalTo("REFUSED"))
        journey.customer().get("/api/v1/kyb/cases/$caseId").then().statusCode(200).body("screening", nullValue())

        // A wrong caller is a different thing: it stays owed, because fixing the caller fixes it.
        journey.execute(
            "update kyb_monitored_subjects set updated_at = now() - interval '8 days' where case_id = ?",
            id,
        )
        registry.failWith = MonitoredSubjectRegistryException("http_403", RegistryFailure.UNAUTHORISED, null)
        run()
        assertThat(journey.rows(caseId)).allMatch { it.syncState == "PENDING" && it.lastError == "http_403" }

        // Control: once the registry accepts, the same rows are delivered.
        registry.failWith = null
        journey.execute(
            "update kyb_monitored_subjects set next_attempt_at = now() - interval '1 second' where case_id = ?",
            id,
        )
        run()
        assertThat(journey.rows(caseId)).allMatch { it.syncState == "SYNCED" }
        journey.operator().get("/api/v1/kyb/cases/$caseId").then().statusCode(200)
            .body("screening.subjects.find { it.kind == 'REPRESENTATIVE' }.monitoringState", equalTo("SYNCED"))
    }

    @Test
    fun `rejecting a signed case that has no monitoring review withdraws everyone, in the same transaction`() {
        val caseId = journey.approved("71000089")
        run()
        val refs = journey.rows(caseId).map { it.subjectRef }
        assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" && it.syncState == "SYNCED" }

        journey.operator().body("""{"reason":"withdrawn before activation"}""")
            .post("/api/v1/kyb/cases/$caseId/reject").then().statusCode(200).body("status", equalTo("REJECTED"))

        val case = journey.stored(caseId)
        assertThat(journey.rows(caseId)).allMatch { it.desired == "WITHDRAWN" && it.syncState == "PENDING" }
        assertThat(journey.rows(caseId).map { it.xmin }.toSet()).containsExactly(case.xmin)
        assertThat(registry.withdrawn).doesNotContainAnyElementsOf(refs)

        run()
        assertThat(registry.withdrawn).containsAll(refs)
        assertThat(journey.rows(caseId)).allMatch { it.desired == "WITHDRAWN" && it.syncState == "SYNCED" }
    }

    @Test
    fun `a subject screened and decided by an earlier release keeps its id, its decision and its registration`() {
        // What the release before this one leaves behind once screening is on: an approved case
        // whose subjects were screened under the old subjectId and carry no reference.
        screening.answers["Viktor Doplněný"] =
            com.openbank.kyb.application.port.out.RelatedPartyScreeningResult(
                com.openbank.kyb.domain.model.ScreeningVerdict.CLEAR,
                true,
                "DOPLNENY Viktor",
            )
        val (caseId, ceremonyId) = journey.readyToSign("71000097")
        assertThat(journey.sign(caseId, ceremonyId)).isEqualTo(200)
        dispose(caseId, "CONFIRMED")
        journey.operator().body("""{"requiredSignatures":1}""").post("/api/v1/kyb/cases/$caseId/review/resolve")
            .then().statusCode(200).body("status", equalTo("SIGNED"))
        val id = UUID.fromString(caseId)
        val key = representativeKey(caseId)
        journey.execute("delete from kyb_monitored_subjects where case_id = ?", id)
        journey.execute(
            "update kyb_cases set screening_json = " +
                "regexp_replace(screening_json, '\"ref\":\"[^\"]*\"', '\"ref\":null', 'g') where case_id = ?",
            id,
        )
        val before = journey.stored(caseId)
        assertThat(before.screeningJson).doesNotContain("\"ref\":\"")
        val screensBefore = screening.requests.size

        run()

        // Registered under exactly the two ids the old screen used, so the check and any
        // clearance recorded on it in sanctions-service still bind.
        val case = journey.stored(caseId)
        assertThat(journey.rows(caseId).map { it.subjectRef })
            .containsExactlyInAnyOrder(case.entityPartyId, "kyb-case:$caseId:$key")
        assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" && it.syncState == "SYNCED" }
        assertThat(registeredFor(caseId).map { it.name }).containsExactlyInAnyOrder("Příklad s.r.o.", "Viktor Doplněný")
        // Nobody is screened again, nothing the operator decided is lost, nothing is re-flagged,
        // and the applicant's case has not moved.
        assertThat(screening.requests).hasSize(screensBefore)
        assertThat(case.screeningJson).contains("\"disposition\":\"CONFIRMED\"", "decided in the monitoring IT")
        assertThat(case.reviewOpened).isFalse()
        assertThat(case.status).isEqualTo("SIGNED")
        assertThat(case.updatedAt).isEqualTo(before.updatedAt)
    }

    @Test
    @Suppress("LongMethod") // one continuous journey over one stored case, as in KybCaseApiIT
    fun `a finding opens a review beside the status and an operator closes it with the existing routes`() {
        val caseId = journey.approved("71000101")
        run()
        val before = journey.stored(caseId)
        val person = personRef(caseId)
        val eventsBefore = journey.events(caseId)
        val mandatesBefore = parties.mandates.count { it.evidenceRef.contains(caseId) }
        val findingId = UUID.randomUUID()
        val opened = counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "review_opened")
        val duplicates = counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "duplicate")

        finding(person, findingId)

        val reviewed = journey.stored(caseId)
        // The status is what it was: nothing downstream, and no applicant, can read the review off it.
        assertThat(reviewed.status).isEqualTo("SIGNED")
        assertThat(reviewed.updatedAt).isEqualTo(before.updatedAt)
        assertThat(reviewed.reviewOpened).isTrue()
        assertThat(reviewed.screeningJson).contains(findingId.toString(), "id:synthetic-entry-1", "POTENTIAL_HIT")
        assertThat(counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "review_opened")).isEqualTo(opened + 1)
        // Nobody is withdrawn because a finding arrived, and no event tells anyone outside.
        assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" && it.syncState == "SYNCED" }
        assertThat(journey.events(caseId)).isEqualTo(eventsBefore)

        // The same finding again: counted as a duplicate, and the row is not written.
        finding(person, findingId)
        assertThat(journey.stored(caseId).xmin).isEqualTo(reviewed.xmin)
        assertThat(counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "duplicate")).isEqualTo(duplicates + 1)

        // The operator finds it in the existing review queue although its status is SIGNED.
        journey.operator().get("/api/v1/kyb/cases?status=MANUAL_REVIEW&size=100").then().statusCode(200)
            .body("find { it.id == '$caseId' }.status", equalTo("SIGNED"))
            .body("find { it.id == '$caseId' }.monitoringReview.openedAt", notNullValue())
        journey.operator().get("/api/v1/kyb/cases/$caseId").then().statusCode(200)
            .body("screening.openFindings", equalTo(1))
            .body("screening.subjects.find { it.kind == 'REPRESENTATIVE' }.monitoringFindings", hasSize<Any>(1))
            .body(
                "screening.subjects.find { it.kind == 'REPRESENTATIVE' }.monitoringFindings[0].listEntryRef",
                equalTo("id:synthetic-entry-1"),
            )

        // Must-refuse: resolving the review does not decide the finding.
        journey.operator().body("""{"requiredSignatures":1}""").post("/api/v1/kyb/cases/$caseId/review/resolve")
            .then().statusCode(422).body("error", equalTo("SCREENING_FINDING_OPEN"))
        assertThat(journey.stored(caseId).reviewOpened).isTrue()

        dispose(caseId, "CLEARED_FALSE_POSITIVE")
        journey.operator().body("""{"requiredSignatures":1}""").post("/api/v1/kyb/cases/$caseId/review/resolve")
            .then().statusCode(200).body("status", equalTo("SIGNED")).body("monitoringReview", nullValue())

        // Closed, and nothing of the approval was repeated or moved.
        val resolved = journey.stored(caseId)
        assertThat(resolved.reviewOpened).isFalse()
        assertThat(resolved.updatedAt).isEqualTo(before.updatedAt)
        assertThat(journey.events(caseId)).isEqualTo(eventsBefore)
        assertThat(parties.mandates.count { it.evidenceRef.contains(caseId) }).isEqualTo(mandatesBefore)
        assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" }
        assertThat(
            journey.operator().get("/api/v1/kyb/cases?status=MANUAL_REVIEW&size=100").then().extract().asString(),
        )
            .doesNotContain(caseId)
    }

    /**
     * Every route an applicant can reach, asked the same thing before a monitoring finding, while
     * its review is open, after the operator decided it, and after an exit was required. The
     * answers must be byte-equal: status code and body, `updatedAt` included (only the per-request
     * trace id and timestamp of the fleet's generic error body are blanked).
     *
     * Not in the list, and why: `agreement/accept` and `abandon` succeed on an approved case and
     * write it, so two calls differ whatever monitoring did; `abandon` has its own test below.
     */
    @ParameterizedTest(name = "an applicant cannot tell a monitoring review from none on a {0} case")
    @ValueSource(strings = ["SIGNED", "ACTIVE"])
    @Suppress("LongMethod") // the route list IS the test: every applicant route in one place
    fun `applicant-facing answers are byte-equal with and without a monitoring review`(status: String) {
        val ico = if (status == "SIGNED") "71000135" else "71000143"
        val caseId = journey.approved(ico)
        if (status == "ACTIVE") {
            onEventLoop { onboarding.entityPartyActivated(UUID.fromString(journey.stored(caseId).entityPartyId)) }
        }
        assertThat(journey.stored(caseId).status).isEqualTo(status)
        run()
        val stranger = UUID.randomUUID()
        val start = """{"scheme":"CZ_ICO","identifier":"$ico","initiatorPartyId":"%s"}"""
        fun other() = journey.operator().header(KybMonitoringJourney.PARTY, stranger.toString())
        val routes: Map<String, () -> io.restassured.response.Response> = linkedMapOf(
            "GET case" to { journey.customer().get("/api/v1/kyb/cases/$caseId") },
            "GET case as a stranger" to { other().get("/api/v1/kyb/cases/$caseId") },
            "GET my cases" to { journey.customer().get("/api/v1/kyb/cases?partyId=${journey.initiator}") },
            "POST start again" to {
                journey.customer().body(start.format(journey.initiator)).post("/api/v1/kyb/cases")
            },
            "POST start as another party" to { other().body(start.format(stranger)).post("/api/v1/kyb/cases") },
            "POST initiator" to {
                journey.customer().body("""{"representativeIndex":0,"claimedName":"x"}""")
                    .post("/api/v1/kyb/cases/$caseId/initiator")
            },
            "POST cosigners" to {
                journey.customer().body("""{"representativeIndexes":[0]}""").post("/api/v1/kyb/cases/$caseId/cosigners")
            },
            "PUT questionnaire" to {
                journey.customer().body(
                    KybMonitoringJourney.QUESTIONNAIRE,
                ).put("/api/v1/kyb/cases/$caseId/questionnaire")
            },
            "GET questionnaire prefill" to {
                journey.customer().get("/api/v1/kyb/cases/$caseId/questionnaire/prefill")
            },
            "PUT declarations" to {
                journey.customer().body("""{"uboConfirmed":true,"peps":[],"truthful":true}""")
                    .put("/api/v1/kyb/cases/$caseId/declarations")
            },
            "POST agreement" to { journey.customer().post("/api/v1/kyb/cases/$caseId/agreement?lang=cs") },
            "POST sign" to {
                journey.customer().body(
                    """{"signatureRef":"${UUID.randomUUID()}"}""",
                ).post("/api/v1/kyb/cases/$caseId/sign")
            },
        )
        // The fleet's generic error body carries a per-REQUEST trace id and timestamp; those two
        // are blanked. Nothing of the case is: `updatedAt` and every other field must match.
        val perRequest = Regex(""""(traceId|timestamp)":"[^"]*"""")
        fun answers() = routes.map { (name, call) ->
            call().let { "$name -> ${it.statusCode} ${it.body.asString().replace(perRequest, "\"$1\":\"-\"")}" }
        }

        // Named first, so a failure says WHICH route moved before it prints two whole bodies.
        fun assertUnmoved(phase: String, reference: List<String>) {
            val now = answers()
            assertThat(routes.keys.filterIndexed { i, _ -> now[i] != reference[i] }).describedAs(phase).isEmpty()
            assertThat(now).describedAs(phase).containsExactlyElementsOf(reference)
        }
        // Once to settle the routes that create something on first use, then the reference reading.
        answers()
        val before = answers()
        assertUnmoved("control: the routes are stable when nothing happens", before)
        assertThat(before.first()).startsWith("GET case -> 200").contains("\"status\":\"$status\"")

        finding(personRef(caseId))
        assertThat(journey.stored(caseId).reviewOpened).isTrue()
        assertUnmoved("while the review is open", before)

        dispose(caseId, "CONFIRMED")
        assertUnmoved("after the operator decided the finding", before)

        journey.operator().body("""{"reason":"confirmed match"}""").post("/api/v1/kyb/cases/$caseId/reject")
            .then().statusCode(200)
        assertThat(journey.stored(caseId).exitRequired).isTrue()
        assertUnmoved("after an exit was required", before)
        // An applicant who then abandons a signed case sees it end as any other would — and stays
        // monitored: the exit decision, not the case status, is what holds the registration.
        if (status == "SIGNED") {
            journey.customer().post("/api/v1/kyb/cases/$caseId/abandon").then().statusCode(200)
                .body("status", equalTo("ABANDONED"))
            assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" }
            return
        }
        // And the timers see what they saw: an idle check for the approved status behaves as before.
        assertThat(journey.stored(caseId).status).isEqualTo(status)
    }

    @Test
    fun `abandoning a signed case answers the same with and without a monitoring review`() {
        val plain = journey.approved("71000160")
        val reviewed = journey.approved("71000151")
        run()
        finding(personRef(reviewed))
        assertThat(journey.stored(reviewed).reviewOpened).isTrue()
        fun abandon(caseId: String) = journey.customer().post("/api/v1/kyb/cases/$caseId/abandon").let {
            "${it.statusCode} ${it.body.jsonPath().getString("status")} ${it.body.jsonPath().getString("reviewReason")}"
        }

        assertThat(abandon(reviewed)).isEqualTo(abandon(plain)).isEqualTo("200 ABANDONED null")
        // The relationship never became active and is over: both are withdrawn alike.
        assertThat(journey.rows(reviewed)).allMatch { it.desired == "WITHDRAWN" }
        assertThat(journey.rows(plain)).allMatch { it.desired == "WITHDRAWN" }
    }

    @Test
    @Suppress("LongMethod") // one continuous journey over one stored case
    fun `a confirmed match on an approved customer is an exit that stays monitored, never a rejection`() {
        val caseId = journey.approved("71000178")
        onEventLoop { onboarding.entityPartyActivated(UUID.fromString(journey.stored(caseId).entityPartyId)) }
        run()
        val before = journey.stored(caseId)
        assertThat(before.status).isEqualTo("ACTIVE")
        val eventsBefore = journey.events(caseId)
        val refs = journey.rows(caseId).map { it.subjectRef }
        finding(personRef(caseId), status = "HIT")
        dispose(caseId, "CONFIRMED")

        // Must-refuse: a confirmed sanctions match cannot be resolved away.
        journey.operator().body("""{"requiredSignatures":1}""").post("/api/v1/kyb/cases/$caseId/review/resolve")
            .then().statusCode(422).body("error", equalTo("SCREENING_FINDING_CONFIRMED"))

        journey.operator().body("""{"reason":"confirmed sanctions match on a director"}""")
            .post("/api/v1/kyb/cases/$caseId/reject").then().statusCode(200)
            .body("status", equalTo("ACTIVE"))
            .body("monitoringReview.exitRequiredAt", notNullValue())
            .body("monitoringReview.exitReason", equalTo("confirmed sanctions match on a director"))

        val exiting = journey.stored(caseId)
        // Still an active customer, still monitored: nothing is withdrawn by the decision.
        assertThat(exiting.status).isEqualTo("ACTIVE")
        assertThat(exiting.updatedAt).isEqualTo(before.updatedAt)
        assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" && it.syncState == "SYNCED" }
        run()
        assertThat(registry.withdrawn).doesNotContainAnyElementsOf(refs)
        // Its own event, once, and not the onboarding rejection; it says nothing of why.
        assertThat(journey.events(caseId) - eventsBefore.toSet()).containsExactly("BUSINESS_RELATIONSHIP_EXIT_REQUIRED")
        val payload = journey.query(
            "select payload from kyb_outbox where aggregate_id = ? and event_type = 'BUSINESS_RELATIONSHIP_EXIT_REQUIRED'",
            UUID.fromString(caseId),
        ) { it.getString(1) }.single()
        assertThat(payload).contains("\"status\":\"ACTIVE\"")
            .doesNotContain("sanction", "confirmed", "director", "reviewReason", "HIT", "finding")
        assertThat(onEventLoop { store.stats(Instant.now()) }.exitsRequired).isGreaterThanOrEqualTo(1)

        // The decision is made once; a later finding is still recorded on the monitored case.
        journey.operator().body(
            """{"reason":"a second decision"}""",
        ).post("/api/v1/kyb/cases/$caseId/reject").then().statusCode(409)
        journey.operator().body("""{"requiredSignatures":1}""").post("/api/v1/kyb/cases/$caseId/review/resolve")
            .then().statusCode(409)
        val later = UUID.randomUUID()
        finding(exiting.entityPartyId!!, later)
        assertThat(journey.stored(caseId).screeningJson).contains(later.toString())
        assertThat(journey.stored(caseId).exitRequired).isTrue()
    }

    @Test
    fun `a finding owned by another service, or about nobody this service registered, changes nothing`() {
        val caseId = journey.approved("71000119")
        run()
        val case = journey.stored(caseId)
        val person = personRef(caseId)
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

        assertThat(journey.stored(caseId).reviewOpened).isFalse()
        assertThat(journey.stored(caseId).xmin).isEqualTo(case.xmin)
        assertThat(counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "foreign_owner")).isEqualTo(foreign + 1)
        assertThat(
            counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "unknown_subject"),
        ).isEqualTo(unknown + 1)

        // Control: the same reference under this service's own name does open the review.
        send(MonitoredSubjectMatchedWire.body(person))
        assertThat(journey.stored(caseId).reviewOpened).isTrue()
    }

    @Test
    fun `a re-assertion does not overwrite a withdrawal that commits while it runs`() {
        val caseId = journey.approved("71000216")
        run()
        val id = UUID.fromString(caseId)
        journey.execute(
            "update kyb_monitored_subjects set updated_at = now() - interval '8 days' where case_id = ?",
            id,
        )
        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { caseWrite ->
            caseWrite.autoCommit = false
            // A case write turns the rows to WITHDRAWN and has not committed yet.
            caseWrite.prepareStatement(
                "update kyb_monitored_subjects set desired = 'WITHDRAWN', sync_state = 'PENDING', " +
                    "generation = generation + 1 where case_id = ?",
            ).use {
                it.setObject(1, id)
                it.executeUpdate()
            }
            val requeue = CompletableFuture.supplyAsync {
                onEventLoop {
                    store.requeueSettledBefore(Instant.now().minus(Duration.ofDays(7)), Instant.now(), BATCH)
                }
            }
            Thread.sleep(BLOCKED_FOR_MS)
            caseWrite.commit()
            requeue.get(WAIT_SECONDS, TimeUnit.SECONDS)
        }
        // The withdrawal stands, at its new generation: the re-assertion re-checked and stepped aside.
        assertThat(journey.rows(caseId)).allMatch {
            it.desired == "WITHDRAWN" &&
                it.syncState == "PENDING" &&
                it.generation == 1L
        }
    }

    @Test
    fun `a reference two cases share is not deleted while one needs it, and the one that needs it is sent again`() {
        val keeper = journey.approved("71000208")
        run()
        val shared = journey.stored(keeper).entityPartyId!!
        val keeperRow = { journey.rows(keeper).single { it.subjectRef == shared } }
        assertThat(keeperRow().syncState).isEqualTo("SYNCED")
        // A registration of the keeper that is in flight right now, as the relay holds it.
        journey.execute(
            "update kyb_monitored_subjects set sync_state = 'PENDING' where case_id = ? and subject_ref = ?",
            UUID.fromString(keeper),
            shared,
        )
        val inFlight = onEventLoop { store.findBySubjectRef(shared) }.single()
        // Another case of the same entity (screened under the same party id) has ended.
        val leaver = UUID.randomUUID()
        journey.execute(
            "insert into kyb_monitored_subjects (id, case_id, subject_ref, desired, sync_state, " +
                "next_attempt_at, pending_since, created_at, updated_at) " +
                "values (nextval('kyb_monitored_subjects_seq'), ?, ?, 'WITHDRAWN', 'PENDING', " +
                "now() - interval '1 minute', now(), now(), now())",
            leaver,
            shared,
        )
        journey.execute(
            "update kyb_monitored_subjects set next_attempt_at = now() + interval '1 hour' where case_id = ?",
            UUID.fromString(keeper),
        )
        val registeredBefore = registry.registered.count { it.subjectRef == shared }

        run()

        // Not deleted: the keeper still needs it.
        assertThat(registry.withdrawn).doesNotContain(shared)
        assertThat(journey.rows(leaver.toString()).single().syncState).isEqualTo("SYNCED")
        // The keeper's in-flight delivery can no longer be recorded: had a delete landed after
        // its PUT, it would otherwise read as registered with nothing in the registry.
        assertThat(onEventLoop { store.markSynced(inFlight, Instant.now()) }).isFalse()
        assertThat(keeperRow().syncState).isEqualTo("PENDING")
        assertThat(keeperRow().generation).isEqualTo(inFlight.generation + 1)
        run()
        assertThat(registry.registered.count { it.subjectRef == shared }).isEqualTo(registeredBefore + 1)
        assertThat(keeperRow().syncState).isEqualTo("SYNCED")
    }

    private companion object {
        const val OWNER = "openbank-kyb"
        const val BATCH = 200
        const val BLOCKED_FOR_MS = 700L
        const val WAIT_SECONDS = 30L
    }
}
