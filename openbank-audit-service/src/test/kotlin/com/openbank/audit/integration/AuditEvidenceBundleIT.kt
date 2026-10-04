// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.audit.integration

import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.domain.model.OccurredAtSource
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * ADR-0214 D3 evidence bundle over real Postgres and the real hash chain (#11900): oldest first,
 * every entry's hash recomputed, an edited row reported as MISMATCH, and the route open to
 * credit-risk staff while staying closed to operators.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
// Shares AuditAuthzWiringIT's profile (enforced authz + a stub OPA that grants this route) so the
// suite does not boot an extra app: with a profile of its own the full suite ran out of test heap.
@TestProfile(AuditAuthzWiringIT.EnforcedProfile::class)
class AuditEvidenceBundleIT {

    @Inject
    lateinit var repository: AuditRepository

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun entry(aggregateId: String, eventType: String, occurredAt: String) = AuditEntry(
        id = UUID.randomUUID(),
        eventType = eventType,
        aggregateType = "LOAN_APPLICATION",
        aggregateId = aggregateId,
        actorId = "credit-officer-1",
        actorType = "HUMAN",
        payload = """{"aggregateId":"$aggregateId","eventType":"$eventType"}""",
        sourceService = "lending-service",
        correlationId = aggregateId,
        occurredAt = Instant.parse(occurredAt),
        recordedAt = Instant.parse(occurredAt).plusSeconds(1),
        occurredAtSource = OccurredAtSource.EVENT,
    )

    /** Written out of order on purpose: the bundle must sort by event time, not insertion. */
    private fun seed(aggregateId: String): List<AuditEntry> {
        val decided = entry(aggregateId, "lending.application.decided", "2026-09-02T10:00:00Z")
        val submitted = entry(aggregateId, "lending.application.submitted", "2026-09-01T10:00:00Z")
        val disbursed = entry(aggregateId, "lending.loan.disbursed", "2026-09-03T10:00:00Z")
        listOf(decided, submitted, disbursed).forEach { e -> onEventLoop { repository.save(e) } }
        return listOf(submitted, decided, disbursed)
    }

    @Test
    @TestSecurity(user = "risk-1", roles = ["ROLE_CREDIT_RISK"])
    fun `credit risk reads the bundle oldest first with every hash verified`() {
        val applicationId = UUID.randomUUID().toString()
        val ordered = seed(applicationId)

        given().`when`().get("/api/v1/audit/evidence/$applicationId").then()
            .statusCode(200)
            .body("attestation", equalTo("audit-chain"))
            .body("entryCount", equalTo(3))
            .body("truncated", equalTo(false))
            .body("tampered", equalTo(false))
            .body("hashStatusCounts.VERIFIED", equalTo(3))
            .body("entries.eventType", contains(*ordered.map { it.eventType }.toTypedArray()))
            .body("entries.entryId", contains(*ordered.map { it.id.toString() }.toTypedArray()))
            .body("fullChainVerification", equalTo("/api/v1/audit/integrity"))
    }

    @Test
    @TestSecurity(user = "compliance-1", roles = ["ROLE_COMPLIANCE"])
    fun `an entry edited after it was written is reported as MISMATCH and the bundle as tampered`() {
        val applicationId = UUID.randomUUID().toString()
        val ordered = seed(applicationId)
        // The V2 append-only RULE discards an ordinary UPDATE (zero rows, no error) — asserted, not
        // assumed — so the edit the bundle must expose is one that BYPASSES the rule.
        assertThat(
            updatePayload(ordered[1].id),
        ).describedAs("the append-only rule must discard a plain UPDATE").isEqualTo(0)
        withoutImmutabilityRule { assertThat(updatePayload(ordered[1].id)).isEqualTo(1) }

        given().`when`().get("/api/v1/audit/evidence/$applicationId").then()
            .statusCode(200)
            .body("tampered", equalTo(true))
            .body("hashStatusCounts.MISMATCH", equalTo(1))
            .body("hashStatusCounts.VERIFIED", equalTo(2))
            .body("entries[1].hashStatus", equalTo("MISMATCH"))
    }

    @Test
    @TestSecurity(user = "risk-1", roles = ["ROLE_CREDIT_RISK"])
    fun `an aggregate with no history is an empty bundle, not an error`() {
        given().`when`().get("/api/v1/audit/evidence/${UUID.randomUUID()}").then()
            .statusCode(200)
            .body("entryCount", equalTo(0))
            .body("tampered", equalTo(false))
    }

    @Test
    @TestSecurity(user = "op-1", roles = ["ROLE_OPERATOR"])
    fun `an operator is refused`() {
        given().`when`().get("/api/v1/audit/evidence/${UUID.randomUUID()}").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "risk-1", roles = ["ROLE_CREDIT_RISK"])
    fun `credit risk gains the evidence route only, not the general trail`() {
        given().`when`().get("/api/v1/audit/entries/${UUID.randomUUID()}").then().statusCode(403)
    }

    private fun jdbc() = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        "openbank",
        "openbank_secret",
    )

    private fun updatePayload(entryId: UUID): Int = jdbc().use { c ->
        c.prepareStatement("UPDATE audit_entries SET payload = ? WHERE entry_id = ?::uuid").use { ps ->
            ps.setString(1, """{"forged":true}""")
            ps.setString(2, entryId.toString())
            ps.executeUpdate()
        }
    }

    private fun withoutImmutabilityRule(block: () -> Unit) = jdbc().use { c ->
        c.createStatement().use { it.execute("DROP RULE no_update_audit ON audit_entries") }
        try {
            block()
        } finally {
            c.createStatement().use {
                it.execute("CREATE OR REPLACE RULE no_update_audit AS ON UPDATE TO audit_entries DO INSTEAD NOTHING")
            }
        }
    }
}
