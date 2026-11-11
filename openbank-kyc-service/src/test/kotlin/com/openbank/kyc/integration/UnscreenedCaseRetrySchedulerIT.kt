// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.application.port.out.CustomerScreeningPort
import com.openbank.kyc.application.port.out.CustomerScreeningRequest
import com.openbank.kyc.application.port.out.CustomerScreeningResult
import com.openbank.kyc.application.port.out.CustomerScreeningUnavailableException
import com.openbank.kyc.application.port.out.PepScreeningResult
import com.openbank.kyc.application.port.out.PepScreeningStatus
import com.openbank.kyc.application.port.out.SanctionsScreeningOutcome
import com.openbank.kyc.application.port.out.SanctionsScreeningStatus
import com.openbank.kyc.infrastructure.kafka.PartyEventConsumer
import com.openbank.libs.testing.containers.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/**
 * A KYC case opened while sanctions-service was unavailable must not stay unscreened forever.
 *
 * The open path leaves the `SANCTIONS_SCREENING` check PENDING when the screen cannot be obtained
 * — correctly, a missing result must never pass a check — and acknowledges the party event. Until
 * the retry sweep existed nothing ever asked again: the case sat PENDING until it expired.
 *
 * ### Why this drives the real scheduler and the real open path
 *
 * The cases are opened by handing a `PARTY_CREATED` payload to the real consumer while the
 * provider stub is down, and the test then only flips the stub and WAITS. It never calls the
 * sweep. A direct call would supply the Vert.x context a scheduler-dispatched method does not
 * have, and would pass against a plain (non-`suspend`) `@Scheduled` method that in production
 * throws HR000068 on its first reactive query and does nothing (#2148, #2187).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(UnscreenedCaseRetrySchedulerIT.FastRetryProfile::class)
class UnscreenedCaseRetrySchedulerIT {

    /** Every value is a LITERAL: a test profile loads in a different classloader from the test. */
    class FastRetryProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.kyc.screening-retry.enabled" to "true",
            "openbank.kyc.screening-retry.interval" to "1s",
            "openbank.kyc.screening-retry.min-age" to "PT0S",
            "openbank.kyc.screening-retry.batch-size" to "2",
            // Sandbox straight-through approval ON: the retry must end where the open path would.
            "openbank.kyc.auto-approve" to "true",
            "openbank.outbox.dispatch-enabled" to "false",
            "quarkus.http.test-port" to "0",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(SwitchableScreening::class.java)
    }

    /**
     * A provider that is down until the test brings it up; behaviour is keyed by the party name.
     *
     * No `@Priority`: a prioritised alternative is selected in EVERY test application of the
     * module, and this one then answered "unavailable" to the Kafka screening IT as well. It is
     * selected by [FastRetryProfile.getEnabledAlternatives] alone.
     */
    @Alternative
    @ApplicationScoped
    class SwitchableScreening : CustomerScreeningPort {
        private val up = AtomicBoolean(false)
        private val keys = ConcurrentHashMap<String, MutableList<String>>()

        fun bringUp() = up.set(true)

        fun keysFor(name: String): List<String> = keys[name]?.toList() ?: emptyList()

        override suspend fun screenCustomer(request: CustomerScreeningRequest): CustomerScreeningResult {
            keys.computeIfAbsent(request.name) { CopyOnWriteArrayList() }.add(request.idempotencyKey)
            if (!up.get() || request.name in ALWAYS_DOWN) {
                throw CustomerScreeningUnavailableException(IllegalStateException("stub outage"))
            }
            // Not the port's declared failure: an unexpected fault in ONE case's retry.
            check(request.name != BROKEN) { "stub: unexpected failure for one case" }
            val sanctions = if (request.name == SANCTIONED) {
                SanctionsScreeningOutcome(SanctionsScreeningStatus.MATCH, MATCH_SCORE, SANCTIONED)
            } else {
                SanctionsScreeningOutcome(SanctionsScreeningStatus.CLEAR, 0.0, null)
            }
            return CustomerScreeningResult(sanctions, PepScreeningResult(PepScreeningStatus.CLEAR, 0.0, null))
        }
    }

    @Inject lateinit var consumer: PartyEventConsumer

    @Inject lateinit var screening: SwitchableScreening

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var registry: MeterRegistry

    private class Row(val caseId: UUID, val status: String, val riskLevel: String, val checks: JsonNode)

    private fun row(partyId: UUID): Row = dataSource.connection.use { c ->
        c.prepareStatement("SELECT case_id, status, risk_level, checks_json FROM kyc_cases WHERE party_id = ?").use {
            it.setObject(1, partyId)
            it.executeQuery().use { rs ->
                assertThat(rs.next()).describedAs("a KYC case for party %s", partyId).isTrue()
                Row(
                    rs.getObject("case_id", UUID::class.java),
                    rs.getString("status"),
                    rs.getString("risk_level"),
                    objectMapper.readTree(rs.getString("checks_json")),
                )
            }
        }
    }

    private fun Row.check(type: String): JsonNode = checks.first { it.path("checkType").asText() == type }

    private fun retainedName(partyId: UUID): String? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT pending_screening_name FROM kyc_cases WHERE party_id = ?").use {
            it.setObject(1, partyId)
            it.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
    }

    /** Hands a PARTY_CREATED event to the real consumer, on a Vert.x context as the connector does. */
    private fun onboard(legalName: String): UUID {
        val partyId = UUID.randomUUID()
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "eventType" to "PARTY_CREATED",
                "partyId" to partyId.toString(),
                "partyType" to "INDIVIDUAL",
                "legalName" to legalName,
            ),
        )
        VertxContextSupport.subscribeAndAwait {
            uni(CoroutineScope(Dispatchers.Unconfined)) { consumer.consume(payload) }
        }
        return partyId
    }

    private fun <T> await(what: String, probe: () -> T, done: (T) -> Boolean): T {
        val deadline = System.nanoTime() + BUDGET_NANOS
        var last = probe()
        while (!done(last) && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
            last = probe()
        }
        assertThat(done(last)).describedAs("timed out waiting for: %s", what).isTrue()
        return last
    }

    private fun gauge(name: String): Double = registry.find(name).tag("service", "kyc").gauge()?.value() ?: Double.NaN

    @Test
    fun `cases left unscreened by an outage are retried by the scheduler and end where the open path would`() {
        val clear = onboard(CLEAR)
        val sanctioned = onboard(SANCTIONED)
        val broken = onboard(BROKEN)
        val stillDown = onboard(STILL_DOWN)

        // The open path, provider down: nothing is passed on a missing result.
        for (party in listOf(clear, sanctioned, broken, stillDown)) {
            val opened = row(party)
            assertThat(opened.status).isEqualTo("OPEN")
            assertThat(opened.check("SANCTIONS_SCREENING").path("status").asText()).isEqualTo("PENDING")
            assertThat(opened.check("SANCTIONS_SCREENING").path("provider").isNull).isTrue()
        }

        // The defect: with the provider still down, something must ask again. Before the sweep
        // existed the open path's single call was the only one ever made.
        await("a scheduler-driven retry of the unscreened case", { screening.keysFor(CLEAR).size }) { it >= 2 }
        assertThat(row(clear).check("SANCTIONS_SCREENING").path("status").asText())
            .describedAs("a retry against a provider that is still down must not settle anything")
            .isEqualTo("PENDING")
        assertThat(retainedName(clear)).isEqualTo(CLEAR)

        screening.bringUp()

        // Must-clear: settled by the real screen, then approved exactly as the open path would.
        val approved = await("the clear case to be screened and auto-approved", { row(clear) }) {
            it.status ==
                "APPROVED"
        }
        assertThat(approved.check("SANCTIONS_SCREENING").path("status").asText()).isEqualTo("PASSED")
        assertThat(approved.check("SANCTIONS_SCREENING").path("provider").asText())
            .isEqualTo("openbank-sanctions-service:CUSTOMER")
        assertThat(approved.check("PEP_SCREENING").path("status").asText()).isEqualTo("PASSED")

        // Must-hit: a match found on retry goes to review and is never auto-approved.
        val hit = await("the sanctioned case to be screened", { row(sanctioned) }) {
            it.check("SANCTIONS_SCREENING").path("status").asText() == "MANUAL_REVIEW"
        }
        assertThat(hit.status).isEqualTo("OPEN")
        assertThat(hit.riskLevel).isEqualTo("VERY_HIGH")

        // The same idempotency key on every attempt, so a response lost after the provider stored
        // it is replayed rather than screened twice.
        for ((party, name) in listOf(clear to CLEAR, sanctioned to SANCTIONED, stillDown to STILL_DOWN)) {
            val keys = screening.keysFor(name)
            assertThat(keys.size).describedAs("attempts for %s", name).isGreaterThanOrEqualTo(2)
            assertThat(keys.toSet()).containsExactly("kyc-screen-${row(party).caseId}")
        }

        // Failure isolation and the bounded batch: one case throws on every attempt and one stays
        // unavailable, with a batch of two — the two healthy cases above still settled.
        await("the failing cases to be retried again", { screening.keysFor(BROKEN).size }) { it >= 2 }
        assertThat(row(broken).check("SANCTIONS_SCREENING").path("status").asText()).isEqualTo("PENDING")
        assertThat(row(stillDown).check("SANCTIONS_SCREENING").path("status").asText()).isEqualTo("PENDING")
        assertThat(row(broken).status).isEqualTo("OPEN")

        // The name is kept only while a retry needs it.
        assertThat(retainedName(clear)).isNull()
        assertThat(retainedName(sanctioned)).isNull()
        assertThat(retainedName(stillDown)).isEqualTo(STILL_DOWN)

        // A settled case is not screened again.
        val settledCalls = screening.keysFor(CLEAR).size
        Thread.sleep(SETTLE_MILLIS)
        assertThat(screening.keysFor(CLEAR)).hasSize(settledCalls)

        // What is left is visible: two cases pending and unscreened, the oldest with a real age.
        await("the backlog gauge", { gauge("openbank.kyc.sanctions.unscreened.cases") }) { it == 2.0 }
        await("the oldest-age gauge", { gauge("openbank.kyc.sanctions.unscreened.oldest.age.seconds") }) { it > 0.0 }
        val heartbeat = registry.find("openbank.workflow.success.recorded")
            .tag("workflow", "kyc-unscreened-case-retry").gauge()?.value()
        assertThat(heartbeat).describedAs("ADR-0237 heartbeat of the sweep").isEqualTo(1.0)
    }

    @Test
    fun `erasing a party forgets its retained name, and the sweep stops asking about it`() {
        val erased = onboard(ERASED)
        await("the name to be retained for a retry", { retainedName(erased) }) { it == ERASED }

        val payload = objectMapper.writeValueAsString(
            mapOf(
                "eventType" to "PARTY_ERASED",
                "partyId" to erased.toString(),
            ),
        )
        VertxContextSupport.subscribeAndAwait {
            uni(CoroutineScope(Dispatchers.Unconfined)) { consumer.consume(payload) }
        }

        assertThat(retainedName(erased)).describedAs("the legal name is PII (GDPR Art. 17)").isNull()
        // A pass that began before the erasure may still finish one call; nothing may follow it.
        Thread.sleep(SETTLE_MILLIS)
        val callsAfterErasure = screening.keysFor(ERASED).size
        Thread.sleep(SETTLE_MILLIS)
        assertThat(screening.keysFor(ERASED)).hasSize(callsAfterErasure)
        assertThat(retainedName(erased)).isNull()
    }

    @Test
    fun `a case marked erased is never picked up, even if a name is still stored on it`() {
        // Not a state the service produces — erasure clears the name — but the sweep must not
        // depend on that: the erasure mark alone has to keep the name from being sent anywhere.
        val caseId = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO kyc_cases (id, case_id, party_id, status, risk_level, checks_json, created_at,
                                       updated_at, erased_at, pending_screening_name, screening_attempted_at)
                VALUES ($GHOST_ROW_ID, ?, ?, 'OPEN', 'MEDIUM', ?, NOW() - INTERVAL '1 hour',
                        NOW() - INTERVAL '1 hour', NOW() - INTERVAL '30 minutes', ?, NOW() - INTERVAL '1 hour')
                """.trimIndent(),
            ).use { st ->
                st.setObject(1, caseId)
                st.setObject(2, UUID.randomUUID())
                st.setString(
                    3,
                    """[{"id":"${UUID.randomUUID()}","caseId":"$caseId","checkType":"SANCTIONS_SCREENING",""" +
                        """"status":"PENDING","result":null,"provider":null,"performedAt":null,""" +
                        """"createdAt":"2026-11-01T00:00:00Z"}]""",
                )
                st.setString(4, GHOST)
                st.executeUpdate()
            }
        }
        // Known-positive for the wait: a live case opened now IS retried within the same window.
        val control = onboard(LIVE_CONTROL)
        await("the live control case to be retried", { screening.keysFor(LIVE_CONTROL).size }) { it >= 3 }

        assertThat(screening.keysFor(GHOST)).describedAs("the name stored on an erased case").isEmpty()

        // Close the control case so it does not stay in the backlog the other test counts.
        dataSource.connection.use { c ->
            c.prepareStatement("UPDATE kyc_cases SET status = 'EXPIRED' WHERE party_id = ?").use {
                it.setObject(1, control)
                it.executeUpdate()
            }
        }
        await("the closed case's name to be released", { retainedName(control) }) { it == null }
    }

    private companion object {
        const val GHOST = "Retry Ghost Person"

        // Explicit and far from the Hibernate sequence: the BIGSERIAL default would hand out an id
        // the application's own pooled sequence already used.
        const val GHOST_ROW_ID = 9_000_000_001L
        const val LIVE_CONTROL = "Retry Control Person"
        const val ERASED = "Retry Erased Person"
        const val CLEAR = "Retry Clear Person"
        const val SANCTIONED = "Retry Sanctioned Person"
        const val BROKEN = "Retry Broken Person"
        const val STILL_DOWN = "Retry Outage Person"
        const val MATCH_SCORE = 0.97
        const val BUDGET_NANOS = 90_000_000_000L
        const val POLL_MILLIS = 200L
        const val SETTLE_MILLIS = 3_000L
        val ALWAYS_DOWN = setOf(STILL_DOWN, ERASED, LIVE_CONTROL, GHOST)
    }
}
