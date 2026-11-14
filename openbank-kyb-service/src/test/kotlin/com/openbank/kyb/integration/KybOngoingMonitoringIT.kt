// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.integration

import com.openbank.kyb.application.port.`in`.BusinessOnboardingUseCase
import com.openbank.kyb.application.port.`in`.ProposeExitCommand
import com.openbank.kyb.application.port.out.FindingDecisionConflictException
import com.openbank.kyb.application.port.out.MonitoredSubjectRegistryException
import com.openbank.kyb.application.port.out.MonitoringRegistrationStore
import com.openbank.kyb.application.port.out.RegistryFailure
import com.openbank.kyb.application.usecase.MonitoringRegistrationService
import com.openbank.kyb.application.usecase.MonitoringRunOutcome
import com.openbank.kyb.application.usecase.MonitoringSettings
import com.openbank.kyb.domain.model.KybEvents
import com.openbank.kyb.infrastructure.MicrometerKybMonitoringMetrics
import com.openbank.kyb.infrastructure.kafka.MonitoredSubjectMatchedConsumer
import com.openbank.kyb.infrastructure.kafka.MonitoredSubjectMatchedWire
import com.openbank.kyb.infrastructure.kafka.PartyEventConsumer
import com.openbank.kyb.it.KybMonitoringJourney
import com.openbank.kyb.it.StubDocumentGateway
import com.openbank.kyb.it.StubMonitoredSubjectRegistry
import com.openbank.kyb.it.StubPartyGateway
import com.openbank.kyb.it.StubRelatedPartyScreening
import com.openbank.libs.testing.containers.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
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
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API", "ROLE_KYC", "ROLE_KYC_REVIEWER"])
// One application, one database and one set of doubles: splitting the class would copy the fixture, not the tests.
@Suppress("LargeClass")
class KybOngoingMonitoringIT {

    @Inject lateinit var parties: StubPartyGateway

    @Inject lateinit var documents: StubDocumentGateway

    @Inject lateinit var screening: StubRelatedPartyScreening

    @Inject lateinit var registry: StubMonitoredSubjectRegistry

    @Inject lateinit var registration: MonitoringRegistrationService

    @Inject lateinit var consumer: MonitoredSubjectMatchedConsumer

    @Inject lateinit var store: MonitoringRegistrationStore

    @Inject lateinit var onboarding: BusinessOnboardingUseCase

    @Inject lateinit var partyEvents: PartyEventConsumer

    @Inject lateinit var meters: MeterRegistry

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    private lateinit var journey: KybMonitoringJourney

    @BeforeEach
    fun setUp() {
        journey = KybMonitoringJourney(parties, documents, jdbcUrl)
        registry.failWith = null
        registry.slowBy = 0
        registry.holdRegistration.clear()
        registry.entered.clear()
        switch(enabled = true)
        // What other tests of the module left owed, refused or unreconciled would be handled by this test's runs.
        journey.execute(
            "update kyb_monitored_subjects set sync_state = 'SYNCED', pending_since = null, claimed_until = null, " +
                "updated_at = now() where sync_state <> 'SYNCED'",
        )
        journey.execute("update kyb_cases set monitoring_synced_version = version")
    }

    @AfterEach
    fun tearDown() {
        registry.failWith = null
        registry.slowBy = 0
        registry.holdRegistration.values.forEach { it.complete(Unit) }
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

    private fun withdrawnFor(caseId: String) =
        journey.rows(caseId).map { it.subjectRef }.let { refs -> registry.withdrawn.filter { it in refs } }

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

    /** A finding as the producer puts it on the wire, through the real consumer. */
    private fun finding(subjectRef: String, findingId: UUID = UUID.randomUUID(), kind: String = "SANCTIONS") =
        onEventLoop {
            consumer.consume(
                MonitoredSubjectMatchedWire.record(
                    MonitoredSubjectMatchedWire.EVENT_TYPE,
                    MonitoredSubjectMatchedWire.json(
                        MonitoredSubjectMatchedWire.body(subjectRef, findingId, matchKind = kind),
                    ),
                ),
            )
        }

    private fun representativeKey(caseId: String): String = journey.operator().get("/api/v1/kyb/cases/$caseId")
        .then().statusCode(200).extract().path("screening.subjects.find { it.kind == 'REPRESENTATIVE' }.key")

    private fun personRef(caseId: String): String =
        journey.rows(caseId).single { it.subjectKey == representativeKey(caseId) }.subjectRef

    private fun entityRef(caseId: String): String =
        journey.rows(caseId).single { it.subjectKey != representativeKey(caseId) }.subjectRef

    private fun decide(caseId: String, findingId: UUID, disposition: String) =
        journey.operator().body("""{"disposition":"$disposition","reason":"decided in the monitoring IT"}""")
            .post("/api/v1/kyb/cases/$caseId/monitoring/findings/$findingId/disposition")

    private fun stats() = onEventLoop { store.stats(Instant.now()) }

    @Test
    fun `approval and what it owes the registry commit in one transaction, under references that say nothing`() {
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
        assertThat(case.syncedVersion).describedAs("the case says its store is in step").isEqualTo(case.version)

        // The reference is random and belongs to nothing: not the party, not the id the screen
        // used, not the subject key (which is derived from the name).
        assertThat(rows.map { it.subjectRef }).allMatch { UUID.fromString(it).version() == 4 }
        assertThat(rows.map { it.subjectRef }).doesNotContain(case.entityPartyId)
        val screened = screening.requests.filter { it.idempotencyKey.startsWith("kyb-screen:$caseId:") }
        assertThat(screened).hasSize(2)
        assertThat(rows.map { it.subjectRef }).doesNotContainAnyElementsOf(screened.map { it.subjectId })
        rows.forEach { assertThat(it.subjectRef).doesNotContain(it.subjectKey) }
        // The store holds no name.
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
        val key = representativeKey(caseId)
        // Another writer of the same case has inserted that subject's row and not committed yet.
        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { other ->
            other.autoCommit = false
            other.prepareStatement(
                "insert into kyb_monitored_subjects (id, case_id, subject_key, next_attempt_at, pending_since, " +
                    "created_at, updated_at) values (nextval('kyb_monitored_subjects_seq'), ?, ?, now(), now(), now(), now())",
            ).use {
                it.setObject(1, id)
                it.setString(2, key)
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
        assertThat(registeredFor(caseId)).containsExactlyInAnyOrder(
            StubMonitoredSubjectRegistry.Registration(entityRef(caseId), true, "Příklad s.r.o."),
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
    fun `a subject the registry refuses is parked, shown to the operator, and sent again when its case is written`() {
        val caseId = journey.approved("71000194")
        val id = UUID.fromString(caseId)

        // The registry's validation will not take the subject as it is: a retry cannot change that.
        registry.failWith = MonitoredSubjectRegistryException("http_400", RegistryFailure.REFUSED, null)
        run()
        assertThat(journey.rows(caseId)).allMatch { it.syncState == "REFUSED" && it.lastError == "http_400" }
        // Out of the retry loop and out of the backlog the critical alert reads ...
        journey.execute(
            "update kyb_monitored_subjects set next_attempt_at = now() - interval '1 hour' where case_id = ?",
            id,
        )
        run()
        assertThat(journey.rows(caseId)).allMatch { it.attemptCount == 1 }
        assertThat(stats().pending).isZero()
        assertThat(stats().oldestPendingAgeSeconds).isZero()
        assertThat(stats().refused).isEqualTo(2)
        // ... and in front of an operator, on the case; never in front of the customer.
        journey.operator().get("/api/v1/kyb/cases/$caseId").then().statusCode(200)
            .body("screening.subjects.find { it.kind == 'REPRESENTATIVE' }.monitoringState", equalTo("REFUSED"))
        journey.customer().get("/api/v1/kyb/cases/$caseId").then().statusCode(200).body("screening", nullValue())

        // What the alert promises: a write of the case makes its refused rows due again.
        registry.failWith = null
        val disclosures = StubDocumentGateway.DISCLOSURES.joinToString(",") {
            """{"code":"${it.code}","version":"${it.version}","sha256":"${it.sha256}"}"""
        }
        journey.customer().body("""{"disclosures":[$disclosures]}""")
            .post("/api/v1/kyb/cases/$caseId/agreement/accept").then().statusCode(200)
        assertThat(journey.rows(caseId)).allMatch { it.syncState == "PENDING" && it.desired == "REGISTERED" }
        run()
        assertThat(journey.rows(caseId)).allMatch { it.syncState == "SYNCED" }
        journey.operator().get("/api/v1/kyb/cases/$caseId").then().statusCode(200)
            .body("screening.subjects.find { it.kind == 'REPRESENTATIVE' }.monitoringState", equalTo("SYNCED"))
    }

    @Test
    fun `rejecting a signed case that has no finding withdraws everyone, in the same transaction`() {
        val caseId = journey.approved("71000089")
        run()
        assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" && it.syncState == "SYNCED" }

        journey.operator().body("""{"reason":"withdrawn before activation"}""")
            .post("/api/v1/kyb/cases/$caseId/reject").then().statusCode(200).body("status", equalTo("REJECTED"))

        val case = journey.stored(caseId)
        assertThat(journey.rows(caseId)).allMatch { it.desired == "WITHDRAWN" && it.syncState == "PENDING" }
        assertThat(journey.rows(caseId).map { it.xmin }.toSet()).containsExactly(case.xmin)
        assertThat(withdrawnFor(caseId)).isEmpty()

        run()
        assertThat(withdrawnFor(caseId)).hasSize(2)
        assertThat(journey.rows(caseId)).allMatch { it.desired == "WITHDRAWN" && it.syncState == "SYNCED" }
    }

    /**
     * What a pod of the previous release does to a case during a rolling deploy or after a
     * rollback: it reads the row, knows nothing of monitoring, and writes back every column IT
     * maps — the status, the version, and `screening_json` re-serialised through its own
     * document shape, which drops whatever it does not know.
     */
    private fun writtenByThePreviousRelease(caseId: String, status: String) {
        val json = journey.stored(caseId).screeningJson
        val asThePreviousReleaseWritesIt =
            com.openbank.kyb.infrastructure.persistence.repository.KybScreeningJson.write(
                com.openbank.kyb.infrastructure.persistence.repository.KybScreeningJson.read(json),
            )
        journey.execute(
            "update kyb_cases set status = ?, screening_json = ?, version = version + 1, updated_at = now() where case_id = ?",
            status,
            asThePreviousReleaseWritesIt,
            UUID.fromString(caseId),
        )
    }

    @Test
    fun `a write by the previous release loses no reference, no finding and no decision, and withdraws nobody`() {
        val caseId = journey.approved("71000224")
        run()
        val refsBefore = journey.rows(caseId).associate { it.subjectKey to it.subjectRef }
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        finding(personRef(caseId), first, kind = "PEP")
        finding(personRef(caseId), second)
        decide(caseId, first, "CONFIRMED").then().statusCode(200)
        val callsBefore = registry.calls.size

        // The entity party becomes active, and a pod of the previous release handles the event.
        writtenByThePreviousRelease(caseId, "ACTIVE")
        val after = journey.stored(caseId)
        assertThat(after.syncedVersion).describedAs("the stale writer is visible").isNotEqualTo(after.version)
        assertThat(stats().unreconciledCases).isGreaterThanOrEqualTo(1)

        run()

        // Nobody was withdrawn, nothing was deleted, every reference is the one it was.
        assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" && it.syncState == "SYNCED" }
        assertThat(journey.rows(caseId).associate { it.subjectKey to it.subjectRef }).isEqualTo(refsBefore)
        assertThat(registry.calls.drop(callsBefore)).noneMatch { it.startsWith("DELETE") }
        // The findings and the decision are where the previous release cannot reach them.
        assertThat(
            journey.findings(caseId),
        ).containsEntry(first.toString(), "CONFIRMED").containsEntry(second.toString(), "OPEN")
        val reconciled = journey.stored(caseId)
        assertThat(reconciled.findingCount).isEqualTo(2)
        assertThat(reconciled.reviewOpen).isTrue()
        assertThat(reconciled.syncedVersion).isEqualTo(reconciled.version)
        journey.operator().get("/api/v1/kyb/cases/$caseId").then().statusCode(200)
            .body("monitoring.findings", hasSize<Any>(2))
        // And a finding still reaches the case under the same reference.
        val third = UUID.randomUUID()
        finding(refsBefore.getValue(representativeKey(caseId)), third)
        assertThat(journey.findings(caseId)).containsKey(third.toString())
    }

    @Test
    fun `a case the previous release approved or ended is brought into step without anyone asking`() {
        // Approved by the previous release: SIGNED, and no row at all.
        val (approvedByOld, _) = journey.readyToSign("71000232")
        writtenByThePreviousRelease(approvedByOld, "SIGNED")
        assertThat(journey.rows(approvedByOld)).isEmpty()
        // Ended by the previous release: REJECTED, with its rows still wanting registration.
        val endedByOld = journey.approved("71000259")
        run()
        assertThat(journey.rows(endedByOld)).allMatch { it.desired == "REGISTERED" && it.syncState == "SYNCED" }
        writtenByThePreviousRelease(endedByOld, "REJECTED")

        run()

        assertThat(journey.rows(approvedByOld)).hasSize(2)
        assertThat(journey.rows(approvedByOld)).allMatch { it.desired == "REGISTERED" && it.syncState == "SYNCED" }
        assertThat(
            registeredFor(approvedByOld).map {
                it.name
            },
        ).containsExactlyInAnyOrder("Příklad s.r.o.", "Nina Dvojitá")
        assertThat(journey.rows(endedByOld)).allMatch { it.desired == "WITHDRAWN" }
        run()
        assertThat(withdrawnFor(endedByOld)).hasSize(2)
    }

    @Test
    @Suppress("LongMethod") // one continuous journey over one stored case, as in KybCaseApiIT
    fun `a finding waits beside an unchanged case, in its own queue, and is decided by its own route`() {
        val caseId = journey.approved("71000101")
        run()
        val before = journey.stored(caseId)
        val eventsBefore = journey.events(caseId)
        val findingId = UUID.randomUUID()
        val opened = counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "review_opened")
        val duplicates = counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "duplicate")

        finding(personRef(caseId), findingId)

        val found = journey.stored(caseId)
        // Nothing an applicant, an event or another service reads has moved.
        assertThat(found.status).isEqualTo("SIGNED")
        assertThat(found.updatedAt).isEqualTo(before.updatedAt)
        assertThat(
            found.screeningJson,
        ).describedAs("the onboarding screening is not rewritten").isEqualTo(before.screeningJson)
        assertThat(journey.events(caseId)).isEqualTo(eventsBefore)
        assertThat(found.reviewOpen).isTrue()
        assertThat(journey.findings(caseId)).containsEntry(findingId.toString(), "OPEN")
        assertThat(counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "review_opened")).isEqualTo(opened + 1)
        assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" && it.syncState == "SYNCED" }

        // The same finding again: counted as a duplicate, and the row is not written.
        finding(personRef(caseId), findingId)
        assertThat(journey.stored(caseId).xmin).isEqualTo(found.xmin)
        assertThat(counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "duplicate")).isEqualTo(duplicates + 1)

        // Its own queue; a status filter answers that status only.
        journey.operator().get("/api/v1/kyb/monitoring/holds?size=100").then().statusCode(200)
            .body("id", hasItem(caseId))
            .body("find { it.id == '$caseId' }.status", equalTo("SIGNED"))
            .body("find { it.id == '$caseId' }.monitoring.findings[0].listEntryRef", equalTo("id:synthetic-entry-1"))
        journey.operator().get("/api/v1/kyb/cases?status=MANUAL_REVIEW&size=100").then().statusCode(200)
            .body("id", not(hasItem(caseId)))
            .body("status", everyItem(equalTo("MANUAL_REVIEW")))
        // The applicant has no such queue and no such field.
        journey.customer().get("/api/v1/kyb/cases/$caseId").then().statusCode(200).body("monitoring", nullValue())

        decide(caseId, findingId, "CLEARED_FALSE_POSITIVE").then().statusCode(200)
            .body("status", equalTo("SIGNED"))
            .body("monitoring.reviewSince", nullValue())
            .body("monitoring.findings[0].disposition", equalTo("CLEARED_FALSE_POSITIVE"))
            .body("monitoring.findings[0].decidedBy", equalTo("service-account-openbank-edge"))
        // A decision is made once.
        decide(caseId, findingId, "CONFIRMED").then().statusCode(409)

        val decided = journey.stored(caseId)
        assertThat(decided.reviewOpen).isFalse()
        assertThat(decided.updatedAt).isEqualTo(before.updatedAt)
        assertThat(journey.events(caseId)).isEqualTo(eventsBefore)
        assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" }
        journey.operator().get("/api/v1/kyb/monitoring/holds?size=100").then().body("id", not(hasItem(caseId)))
    }

    @Test
    fun `a confirmed sanctions match stands whatever a later finding brings, and is an exit, published nowhere`() {
        val caseId = journey.approved("71000178")
        onEventLoop { onboarding.entityPartyActivated(UUID.fromString(journey.stored(caseId).entityPartyId)) }
        run()
        val before = journey.stored(caseId)
        assertThat(before.status).isEqualTo("ACTIVE")
        val eventsBefore = journey.events(caseId)
        val sanctions = UUID.randomUUID()
        val pep = UUID.randomUUID()
        val later = UUID.randomUUID()
        finding(personRef(caseId), sanctions)
        finding(personRef(caseId), pep, kind = "PEP")

        // A confirmed PEP match is a risk fact: no exit, no event.
        decide(caseId, pep, "CONFIRMED").then().statusCode(200).body("monitoring.exitOwedSince", nullValue())
        assertThat(journey.events(caseId)).isEqualTo(eventsBefore)

        decide(caseId, sanctions, "CONFIRMED").then().statusCode(200)
            .body("status", equalTo("ACTIVE"))
            .body("monitoring.exitOwedSince", notNullValue())
        // A second finding on the same person, cleared: the confirmed one is untouched.
        finding(personRef(caseId), later)
        decide(caseId, later, "CLEARED_FALSE_POSITIVE").then().statusCode(200)
            .body("monitoring.exitOwedSince", notNullValue())
            .body("monitoring.reviewSince", nullValue())
        assertThat(journey.findings(caseId)).containsEntry(sanctions.toString(), "CONFIRMED")
            .containsEntry(later.toString(), "CLEARED_FALSE_POSITIVE")

        val owed = journey.stored(caseId)
        // Still an active customer, still monitored, and nothing the applicant reads has moved.
        assertThat(owed.status).isEqualTo("ACTIVE")
        assertThat(owed.updatedAt).isEqualTo(before.updatedAt)
        assertThat(owed.exitOwed).isTrue()
        assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" && it.syncState == "SYNCED" }
        // Nothing on the shared lifecycle topic (#16028 item 4): it is read by a customer-facing
        // projection and landed in the analytics warehouse. The owed exit is a stored fact the
        // queue and the alert read.
        assertThat(journey.events(caseId)).isEqualTo(eventsBefore)
        assertThat(stats().exitsOwed).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `only a four-eyes exit over an ended relationship ends monitoring, and it withdraws the subjects`() {
        val caseId = journey.approved("71000216")
        run()
        val confirmed = UUID.randomUUID()
        val open = UUID.randomUUID()
        finding(personRef(caseId), confirmed)
        decide(caseId, confirmed, "CONFIRMED").then().statusCode(200)
        finding(entityRef(caseId), open)
        val evidence = "party closed, mandates revoked on 13 November: ticket SYN-216"
        val entityParty = UUID.fromString(journey.stored(caseId).entityPartyId)
        fun propose(text: String) = journey.operator().body("""{"evidence":"$text"}""")
            .post("/api/v1/kyb/cases/$caseId/monitoring/exit/propose")
        fun complete(text: String) = journey.operator().body("""{"evidence":"$text"}""")
            .post("/api/v1/kyb/cases/$caseId/monitoring/exit/complete")
        fun proposeAs(reviewer: String) =
            onEventLoop { onboarding.proposeExit(ProposeExitCommand(UUID.fromString(caseId), evidence, reviewer)) }

        // Must-refuse: a finding nobody decided, evidence that says nothing.
        propose(evidence).then().statusCode(409)
        decide(caseId, open, "CLEARED_FALSE_POSITIVE").then().statusCode(200)
        propose("done").then().statusCode(400)
        // Must-refuse: the case is SIGNED — approved, the relationship live in kyb.
        propose(evidence).then().statusCode(409)
        journey.operator().body("""{"reason":"relationship ended after a confirmed match"}""")
            .post("/api/v1/kyb/cases/$caseId/reject").then().statusCode(200)
        // Must-refuse: rejected in kyb, but party-service still has the entity party live.
        propose(evidence).then().statusCode(409)
        parties.closed += entityParty
        try {
            // Must-refuse: confirming with nothing proposed; the proposer confirming themselves.
            complete(evidence).then().statusCode(409)
            propose(evidence).then().statusCode(200)
                .body("monitoring.exitProposal.proposedBy", equalTo("service-account-openbank-edge"))
                .body("monitoring.exitOwedSince", notNullValue())
            complete(evidence).then().statusCode(409)
            assertThat(journey.stored(caseId).exitOwed).isTrue()
            assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" }

            // Another reviewer's proposal; the test principal is now the second pair of eyes. A
            // confirmation of evidence other than the pending proposal's is refused.
            proposeAs("reviewer-a")
            complete("evidence the proposal never said").then().statusCode(409)
            complete(evidence).then().statusCode(200)
                .body("status", equalTo("REJECTED"))
                .body("monitoring.exitOwedSince", nullValue())
                .body("monitoring.exitProposal.proposedBy", equalTo("reviewer-a"))
                .body("monitoring.exitCompleted.completedBy", equalTo("service-account-openbank-edge"))
                .body("monitoring.exitCompleted.evidence", equalTo(evidence))
        } finally {
            parties.closed -= entityParty
        }

        val exited = journey.stored(caseId)
        assertThat(exited.exitOwed).isFalse()
        assertThat(exited.exitCompleted).isTrue()
        // The same write withdrew everyone; the relay then tells the registry.
        assertThat(journey.rows(caseId)).allMatch { it.desired == "WITHDRAWN" && it.syncState == "PENDING" }
        assertThat(journey.rows(caseId).map { it.xmin }.toSet()).containsExactly(exited.xmin)
        run()
        assertThat(withdrawnFor(caseId)).hasSize(2)
        // Completed once.
        complete(evidence).then().statusCode(409)
    }

    /** The entity party's activation, as party-service puts it on the wire, through the real consumer. */
    private fun partyActivated(caseId: String) = onEventLoop {
        partyEvents.consume(
            """{"eventType":"KYC_STATUS_CHANGED","status":"ACTIVE","partyType":"COMPANY",""" +
                """"partyId":"${journey.stored(caseId).entityPartyId}"}""",
        )
    }

    @Test
    fun `party activation under a monitoring hold stays pending, and a clean decision applies it once`() {
        val held = journey.approved("71000330")
        val plain = journey.approved("71000348")
        run()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        finding(personRef(held), first)
        finding(entityRef(held), second)
        val eventsBefore = journey.events(held)

        partyActivated(held)
        partyActivated(plain)

        // Control: without a finding, activation completes the case.
        assertThat(journey.stored(plain).status).isEqualTo("ACTIVE")
        assertThat(journey.events(plain)).contains(KybEvents.COMPLETED)
        // Held: still SIGNED, nothing published; the activation is recorded as pending.
        val pending = journey.stored(held)
        assertThat(pending.status).isEqualTo("SIGNED")
        assertThat(journey.events(held)).isEqualTo(eventsBefore)
        // A redelivered activation changes nothing.
        partyActivated(held)
        assertThat(journey.stored(held).status).isEqualTo("SIGNED")

        decide(held, first, "CLEARED_FALSE_POSITIVE").then().statusCode(200).body("status", equalTo("SIGNED"))
        assertThat(journey.events(held)).isEqualTo(eventsBefore)
        // The last hold cleared: ACTIVE, and `completed` exactly once, with the decision's write.
        decide(held, second, "CLEARED_FALSE_POSITIVE").then().statusCode(200).body("status", equalTo("ACTIVE"))
        assertThat(journey.events(held) - eventsBefore.toSet()).containsExactly(KybEvents.COMPLETED)
        assertThat(journey.stored(held).updatedAt).describedAs("as if it had never waited").isEqualTo(pending.updatedAt)
    }

    @Test
    fun `party activation after a confirmed sanctions match is never applied`() {
        val caseId = journey.approved("71000356")
        run()
        val findingId = UUID.randomUUID()
        finding(personRef(caseId), findingId)
        decide(caseId, findingId, "CONFIRMED").then().statusCode(200)
        val eventsBefore = journey.events(caseId)

        partyActivated(caseId)

        assertThat(journey.stored(caseId).status).isEqualTo("SIGNED")
        assertThat(journey.events(caseId)).isEqualTo(eventsBefore)
        assertThat(journey.events(caseId)).doesNotContain(KybEvents.COMPLETED)
    }

    @Test
    fun `the applicant cannot end monitoring while a finding holds the case, and the abandon succeeds as ever`() {
        val caseId = journey.approved("71000267")
        run()
        val findingId = UUID.randomUUID()
        finding(personRef(caseId), findingId)

        journey.customer().post("/api/v1/kyb/cases/$caseId/abandon").then().statusCode(200)
            .body("status", equalTo("ABANDONED"))

        // The case ended for the applicant; its subjects are still registered.
        assertThat(journey.stored(caseId).status).isEqualTo("ABANDONED")
        assertThat(journey.rows(caseId)).allMatch { it.desired == "REGISTERED" && it.syncState == "SYNCED" }
        run()
        assertThat(withdrawnFor(caseId)).isEmpty()
        // The finding is still there for the operator, in the monitoring queue.
        journey.operator().get("/api/v1/kyb/monitoring/holds?size=100").then().body("id", hasItem(caseId))

        // Decided clean: nothing holds the ended case any more, and that write withdraws.
        decide(caseId, findingId, "CLEARED_FALSE_POSITIVE").then().statusCode(200)
        assertThat(journey.rows(caseId)).allMatch { it.desired == "WITHDRAWN" }
        run()
        assertThat(withdrawnFor(caseId)).hasSize(2)
    }

    /** Headers that belong to one request, not to the case. */
    private val perRequestHeaders = setOf("date", "traceparent", "tracestate", "x-correlation-id", "x-request-id")

    /** Status code, headers and body, with only the per-request parts of the fleet's error body blanked. */
    private fun whole(response: io.restassured.response.Response, blank: Regex? = null): String {
        val perRequest = Regex(""""(traceId|timestamp)":"[^"]*"""")
        val headers = response.headers.asList()
            .filter { it.name.lowercase() !in perRequestHeaders }
            .map { "${it.name.lowercase()}=${it.value}" }.sorted()
        var body = response.body.asString().replace(perRequest, "\"$1\":\"-\"")
        if (blank != null) body = body.replace(blank, "-")
        return "${response.statusCode} $headers $body"
    }

    /**
     * Every route an applicant can reach, asked the same thing before a monitoring finding, while
     * it is open, after it is confirmed (an exit owed) and after a second finding: status code,
     * headers and body must be equal, byte for byte, `updatedAt` included.
     *
     * `agreement/accept` is the one route that writes an approved case. So each phase first
     * compares every other route, unblanked, with what the same routes answered right after the
     * applicant's own last write; then calls `accept` and compares its answer with only the
     * timestamps that call itself writes blanked. `abandon` ends the case and is compared across
     * two cases in the test below.
     */
    @ParameterizedTest(name = "an applicant cannot tell a monitoring finding from none on a {0} case")
    @ValueSource(strings = ["SIGNED", "ACTIVE"])
    @Suppress("LongMethod") // the route list IS the test: every applicant route in one place
    fun `applicant-facing answers are equal with and without monitoring findings`(status: String) {
        val ico = if (status == "SIGNED") "71000135" else "71000143"
        val caseId = journey.approved(ico)
        if (status == "ACTIVE") {
            onEventLoop { onboarding.entityPartyActivated(UUID.fromString(journey.stored(caseId).entityPartyId)) }
        }
        assertThat(journey.stored(caseId).status).isEqualTo(status)
        run()
        val stranger = UUID.randomUUID()
        val start = """{"scheme":"CZ_ICO","identifier":"$ico","initiatorPartyId":"%s"}"""
        val disclosures = StubDocumentGateway.DISCLOSURES.joinToString(",") {
            """{"code":"${it.code}","version":"${it.version}","sha256":"${it.sha256}"}"""
        }
        fun other() = journey.operator().header(KybMonitoringJourney.PARTY, stranger.toString())
        val routes: Map<String, () -> io.restassured.response.Response> = linkedMapOf(
            "GET case" to { journey.customer().get("/api/v1/kyb/cases/$caseId") },
            "GET case as a stranger" to { other().get("/api/v1/kyb/cases/$caseId") },
            "GET my cases" to { journey.customer().get("/api/v1/kyb/cases?partyId=${journey.initiator}") },
            "GET the monitoring queue as a customer" to { journey.customer().get("/api/v1/kyb/monitoring/holds") },
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
                journey.customer().body("""{"signatureRef":"00000000-0000-4000-8000-000000000000"}""")
                    .post("/api/v1/kyb/cases/$caseId/sign")
            },
        )
        val accept = {
            journey.customer().body(
                """{"disclosures":[$disclosures]}""",
            ).post("/api/v1/kyb/cases/$caseId/agreement/accept")
        }
        // What `accept` itself writes on every call: the case's `updatedAt` and its own acceptance times.
        val ownWrite = Regex(""""(updatedAt|acceptedAt|at)":"[^"]*"""")
        fun reads(): List<String> = routes.map { (name, call) -> "$name -> ${whole(call())}" }
        fun accepted(): String = whole(accept(), blank = ownWrite)

        // Once to settle the routes that create something on first use.
        reads()
        val acceptReference = accepted()
        var afterOwnWrite = reads()
        assertThat(afterOwnWrite.first()).startsWith("GET case -> 200").contains("\"status\":\"$status\"")
        assertThat(acceptReference).startsWith("200")

        fun assertUnmoved(phase: String) {
            val now = reads()
            assertThat(routes.keys.filterIndexed { i, _ -> now[i] != afterOwnWrite[i] }).describedAs(phase).isEmpty()
            assertThat(accepted()).describedAs("$phase: agreement/accept").isEqualTo(acceptReference)
            afterOwnWrite = reads()
        }
        assertUnmoved("control: the routes are stable when nothing happens")

        val findingId = UUID.randomUUID()
        finding(personRef(caseId), findingId)
        assertThat(journey.stored(caseId).reviewOpen).isTrue()
        assertUnmoved("while a finding is open")

        decide(caseId, findingId, "CONFIRMED").then().statusCode(200)
        assertThat(journey.stored(caseId).exitOwed).isTrue()
        assertUnmoved("after it was confirmed and an exit is owed")

        finding(entityRef(caseId))
        assertUnmoved("after a second finding")
        assertThat(journey.stored(caseId).status).isEqualTo(status)
    }

    @Test
    fun `abandoning a signed case answers the same, whole, with and without an open finding`() {
        val plain = journey.approved("71000160")
        val watched = journey.approved("71000151")
        run()
        finding(personRef(watched))
        assertThat(journey.stored(watched).reviewOpen).isTrue()
        // Everything that names one case rather than the other, and nothing else. The two
        // representatives' names are the same length in bytes, so `content-length` is compared too.
        val perCase = Regex(
            """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|\d{4}-\d{2}-\d{2}T[0-9:.]+Z|""" +
                """7100016\d|7100015\d|""" +
                """Bohdana Kontrolovaná|Ctibor Odcházející|[0-9a-f]{64}""",
        )
        fun abandon(caseId: String) =
            whole(journey.customer().post("/api/v1/kyb/cases/$caseId/abandon"), blank = perCase)

        val withFinding = abandon(watched)
        val without = abandon(plain)

        assertThat(withFinding).isEqualTo(without)
        assertThat(without).startsWith("200").contains("\"status\":\"ABANDONED\"")
        // Control: the comparison is not blind — a second abandon is a different answer.
        assertThat(abandon(plain)).isNotEqualTo(without)
    }

    @Test
    fun `one finding id reported for two cases is a finding on each of them`() {
        val first = journey.approved("71000275")
        val second = journey.approved("71000283")
        run()
        val findingId = UUID.randomUUID()

        finding(personRef(first), findingId)
        finding(personRef(second), findingId)

        assertThat(journey.findings(first)).containsOnlyKeys(findingId.toString())
        assertThat(journey.findings(second)).containsOnlyKeys(findingId.toString())
        assertThat(journey.stored(first).reviewOpen).isTrue()
        assertThat(journey.stored(second).reviewOpen).isTrue()
        // Each is decided by itself.
        decide(first, findingId, "CLEARED_FALSE_POSITIVE").then().statusCode(200)
        assertThat(journey.findings(first)).containsEntry(findingId.toString(), "CLEARED_FALSE_POSITIVE")
        assertThat(journey.findings(second)).containsEntry(findingId.toString(), "OPEN")
        assertThat(journey.stored(second).reviewOpen).isTrue()
    }

    @Test
    fun `a case write whose findings cannot all be stored as given commits nothing`() {
        val caseId = journey.approved("71000291")
        run()
        val before = journey.stored(caseId)
        val findingId = UUID.randomUUID()
        // A stored decision the case being written does not know about.
        journey.execute(
            "insert into kyb_monitoring_findings (finding_id, case_id, subject_key, match_kind, list_type, " +
                "list_entry_ref, list_entry_version, match_score, match_status, detection, received_at, disposition, " +
                "decided_by, decision_reason, decided_at) values (?, ?, ?, 'SANCTIONS', 'EU', 'id:planted', '1', " +
                "1.0, " +
                "'HIT', 'LIST_DELTA', now(), 'CONFIRMED', 'somebody', 'planted by the test', now())",
            findingId,
            UUID.fromString(caseId),
            representativeKey(caseId),
        )

        val conflictsBefore = counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "decision_conflict")
        val failure = runCatching { finding(personRef(caseId), findingId) }.exceptionOrNull()

        // Nacked as its own failure, not retried as a lost race: dead-lettered; nothing of the write is there.
        assertThat(failure).isInstanceOf(FindingDecisionConflictException::class.java)
        assertThat(counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "decision_conflict"))
            .isEqualTo(conflictsBefore + 1)
        val after = journey.stored(caseId)
        assertThat(after.xmin).describedAs("the case row was not written").isEqualTo(before.xmin)
        assertThat(after.findingCount).isZero()
        assertThat(after.reviewOpen).isFalse()
        assertThat(journey.findings(caseId)).containsEntry(findingId.toString(), "CONFIRMED").hasSize(1)

        // Control: without the planted row the same delivery is recorded.
        journey.execute("delete from kyb_monitoring_findings where case_id = ?", UUID.fromString(caseId))
        finding(personRef(caseId), findingId)
        assertThat(journey.stored(caseId).reviewOpen).isTrue()
    }

    @Test
    fun `a finding delivered while an operator decides another one on the same case is applied, not dead-lettered`() {
        val caseId = journey.approved("71000313")
        run()
        val first = UUID.randomUUID()
        finding(personRef(caseId), first)
        val conflictsBefore = counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "decision_conflict")
        val second = UUID.randomUUID()

        // The operator's decision, held open: it has locked the case and decided the first finding.
        val delivery = journey.connection().use { operator ->
            operator.autoCommit = false
            fun exec(sql: String, vararg args: Any?) = operator.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.execute()
            }
            exec("select version from kyb_cases where case_id = ? for update", UUID.fromString(caseId))
            exec(
                "update kyb_monitoring_findings set disposition = 'CLEARED_FALSE_POSITIVE', decided_by = 'op', " +
                    "decision_reason = 'decided concurrently', decided_at = now() where case_id = ? and finding_id = ?",
                UUID.fromString(caseId),
                first,
            )
            exec("update kyb_cases set version = version + 1 where case_id = ?", UUID.fromString(caseId))
            // The delivery starts while the decision is uncommitted, and has to wait for it.
            val pending = CompletableFuture.supplyAsync { runCatching { finding(personRef(caseId), second) } }
            Thread.sleep(CONCURRENT_OVERLAP_MS)
            operator.commit()
            pending
        }.get(WAIT_SECONDS, TimeUnit.SECONDS)

        assertThat(delivery.exceptionOrNull()).describedAs("a lost race is retried, not dead-lettered").isNull()
        assertThat(journey.findings(caseId))
            .containsEntry(first.toString(), "CLEARED_FALSE_POSITIVE")
            .containsEntry(second.toString(), "OPEN")
        assertThat(counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "decision_conflict"))
            .isEqualTo(conflictsBefore)
    }

    @Test
    fun `two decisions about one finding that differ only in their reason are a conflict, not a duplicate`() {
        val caseId = journey.approved("71000305")
        run()
        val findingId = UUID.randomUUID()
        finding(personRef(caseId), findingId)
        decide(caseId, findingId, "CLEARED_FALSE_POSITIVE").then().statusCode(200)
        val stored = onEventLoop { store.findingsOf(UUID.fromString(caseId)) }.single()

        // A writer holding the same decision under another reason: the stored one must not count as written.
        val failure = runCatching {
            onEventLoop {
                Panache.withTransaction {
                    store.saveFindingsInTransaction(
                        UUID.fromString(caseId),
                        listOf(stored.copy(decisionReason = "a different reason")),
                    )
                }.awaitSuspending()
            }
        }.exceptionOrNull()
        // The identical decision is a duplicate and writes cleanly.
        onEventLoop {
            Panache.withTransaction { store.saveFindingsInTransaction(UUID.fromString(caseId), listOf(stored)) }
                .awaitSuspending()
        }

        assertThat(failure).isInstanceOf(FindingDecisionConflictException::class.java)
        assertThat(onEventLoop { store.findingsOf(UUID.fromString(caseId)) }.single().decisionReason)
            .isEqualTo(stored.decisionReason)
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

        assertThat(journey.stored(caseId).findingCount).isZero()
        assertThat(journey.stored(caseId).xmin).isEqualTo(case.xmin)
        assertThat(counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "foreign_owner")).isEqualTo(foreign + 1)
        assertThat(
            counted(MicrometerKybMonitoringMetrics.FINDINGS, "outcome", "unknown_subject"),
        ).isEqualTo(unknown + 1)

        // Control: the same reference under this service's own name is recorded.
        send(MonitoredSubjectMatchedWire.body(person))
        assertThat(journey.stored(caseId).findingCount).isEqualTo(1)
    }

    @Test
    fun `a settled withdrawal is re-sent, so a registration that landed after it cannot outlive it`() {
        val caseId = journey.approved("71000364")
        run()
        journey.customer().post("/api/v1/kyb/cases/$caseId/abandon").then().statusCode(200)
        run()
        assertThat(journey.rows(caseId)).allMatch { it.desired == "WITHDRAWN" && it.syncState == "SYNCED" }
        val sent = withdrawnFor(caseId).size
        assertThat(sent).isEqualTo(2)
        journey.execute(
            "update kyb_monitored_subjects set updated_at = now() - interval '8 days' where case_id = ?",
            UUID.fromString(caseId),
        )

        run()

        assertThat(withdrawnFor(caseId)).describedAs("each DELETE sent again").hasSize(sent + 2)
        assertThat(journey.rows(caseId)).allMatch { it.desired == "WITHDRAWN" && it.syncState == "SYNCED" }
        // Control: a fresh settlement is not re-sent.
        run()
        assertThat(withdrawnFor(caseId)).hasSize(sent + 2)
    }

    @Test
    fun `switched off, withdrawals of subjects the registry holds are still sent, and nothing else is`() {
        val held = journey.approved("71000372")
        run()
        assertThat(registeredFor(held)).hasSize(2)
        switch(enabled = false)
        val never = journey.approved("71000381")
        journey.customer().post("/api/v1/kyb/cases/$held/abandon").then().statusCode(200)
        journey.customer().post("/api/v1/kyb/cases/$never/abandon").then().statusCode(200)

        assertThat(run()).isEqualTo(MonitoringRunOutcome.DISABLED)

        assertThat(withdrawnFor(held)).describedAs("the registry stops monitoring an ended customer").hasSize(2)
        assertThat(journey.rows(held)).allMatch { it.desired == "WITHDRAWN" && it.syncState == "SYNCED" }
        // A subject the registry never held: nothing to withdraw, and nothing is called for it.
        assertThat(registeredFor(never)).isEmpty()
        assertThat(withdrawnFor(never)).isEmpty()
        assertThat(journey.rows(never)).allMatch { it.desired == "WITHDRAWN" && it.syncState == "PENDING" }
    }

    @Test
    fun `a re-assertion does not overwrite a withdrawal that commits while it runs`() {
        val caseId = journey.approved("71000208")
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
            it.desired == "WITHDRAWN" && it.syncState == "PENDING" && it.generation == 1L
        }
    }

    @Test
    fun `two relays running at once deliver each row once`() {
        val caseId = journey.approved("71000097")
        val registeredBefore = counted(MicrometerKybMonitoringMetrics.REGISTRATIONS, "outcome", "registered")
        // Slow enough that the second relay arrives while the first is inside its call.
        registry.slowBy = SLOW_CALL_MS

        val relays = (1..2).map { CompletableFuture.supplyAsync { run() } }
        relays.forEach { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }

        assertThat(registeredFor(caseId)).describedAs("one PUT per subject, not one per relay").hasSize(2)
        assertThat(journey.rows(caseId)).allMatch { it.syncState == "SYNCED" }
        assertThat(counted(MicrometerKybMonitoringMetrics.REGISTRATIONS, "outcome", "registered"))
            .isEqualTo(registeredBefore + 2)
    }

    @Test
    fun `a withdrawal is never sent while the registration before it is still in flight`() {
        val caseId = journey.approved("71000241")
        val refs = journey.rows(caseId).map { it.subjectRef }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        refs.forEach {
            registry.holdRegistration[it] = gate
            registry.entered[it] = java.util.concurrent.CountDownLatch(1)
        }
        // One relay is inside the registration of the first row ...
        val first = CompletableFuture.supplyAsync { run() }
        assertThat(refs.any { registry.entered.getValue(it).await(WAIT_SECONDS, TimeUnit.SECONDS) }).isTrue()
        // ... when the case ends, and a second relay comes by.
        journey.operator().body("""{"reason":"withdrawn before activation"}""")
            .post("/api/v1/kyb/cases/$caseId/reject").then().statusCode(200)
        run()
        val inFlight = refs.first { registry.entered.getValue(it).count == 0L }
        assertThat(registry.calls).describedAs("the row in flight is leased: no DELETE overtakes its PUT")
            .doesNotContain("DELETE $inFlight")

        gate.complete(Unit)
        first.get(WAIT_SECONDS, TimeUnit.SECONDS)
        run()

        // The PUT landed first and the DELETE after it, so the registry ends without the subject.
        assertThat(registry.calls.filter { it.endsWith(inFlight) }).containsExactly("PUT $inFlight", "DELETE $inFlight")
        assertThat(journey.rows(caseId)).allMatch { it.desired == "WITHDRAWN" && it.syncState == "SYNCED" }
    }

    private companion object {
        const val OWNER = "openbank-kyb"
        const val BATCH = 200
        const val BLOCKED_FOR_MS = 700L
        const val SLOW_CALL_MS = 400L
        const val WAIT_SECONDS = 30L
        const val CONCURRENT_OVERLAP_MS = 1_000L
    }
}
