// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Broker → consumer → Postgres → HTTP end to end: an audit event published on Redpanda lands in
 * the append-only `audit_entries` chain with producer-declared attribution (asserted over plain
 * JDBC), redelivery of the same event id deduplicates, and the regulated trail / evidence /
 * by-actor routes read the fact back — with the SOX/DORA role gate pinned.
 */
@QuarkusTest
@TestProfile(AuditE2EProfile::class)
class AuditIngestionE2E : AuditE2EBase() {

    @Test
    @TestSecurity(user = "audit-e2e-auditor", roles = ["ROLE_AUDITOR"])
    fun `a published event lands in the chain with producer-declared attribution`() {
        val eventId = UUID.randomUUID()
        val aggregateId = UUID.randomUUID()
        publishAuditEvent(auditEvent(eventId, aggregateId, actorId = "audit-e2e-actor"))

        val row = awaitEntry(eventId)
        assertThat(row.eventType).isEqualTo("ACCOUNT_CREATED")
        assertThat(row.actorId).isEqualTo("audit-e2e-actor")
        // Envelope-first attribution (#6318): the producer's own sourceService survives verbatim.
        assertThat(row.sourceService).isEqualTo("party-service")

        val trail = getJson("/api/v1/audit/entries/$aggregateId", 200)
        assertThat(trail.isArray).isTrue()
        assertThat(trail.any { it.get("id").asText() == eventId.toString() }).isTrue()
    }

    @Test
    @TestSecurity(user = "audit-e2e-auditor", roles = ["ROLE_AUDITOR"])
    fun `a redelivered event id deduplicates to a single chain row`() {
        val eventId = UUID.randomUUID()
        val aggregateId = UUID.randomUUID()
        val body = auditEvent(eventId, aggregateId, actorId = "audit-e2e-actor")

        publishAuditEvent(body)
        awaitEntry(eventId)
        publishAuditEvent(body)

        Thread.sleep(500)
        val count = countEntries(eventId)
        assertThat(count).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "audit-e2e-auditor", roles = ["ROLE_AUDITOR"])
    fun `the evidence bundle reconstructs the aggregate with intact hashes`() {
        val eventId = UUID.randomUUID()
        val aggregateId = UUID.randomUUID()
        publishAuditEvent(auditEvent(eventId, aggregateId, actorId = "audit-e2e-actor"))
        awaitEntry(eventId)

        val bundle = getJson("/api/v1/audit/evidence/$aggregateId", 200)
        assertThat(bundle.get("aggregateId").asText()).isEqualTo(aggregateId.toString())
        assertThat(bundle.get("attestation").asText()).isEqualTo("audit-chain")
        assertThat(bundle.get("tampered").asBoolean()).isFalse()
        assertThat(bundle.get("entryCount").asInt()).isGreaterThanOrEqualTo(1)
        val statuses = bundle.get("hashStatusCounts")
        assertThat(statuses.get("VERIFIED").asInt()).isGreaterThanOrEqualTo(1)

        val integrity = getJson("/api/v1/audit/integrity", 200)
        assertThat(integrity.get("chainStatus").asText()).isEqualTo("INTACT")
    }

    @Test
    @TestSecurity(user = "audit-e2e-auditor", roles = ["ROLE_AUDITOR"])
    fun `the by-actor trail answers the forensic what-did-X-do query`() {
        val actorId = "audit-e2e-actor-${UUID.randomUUID()}"
        val eventId = UUID.randomUUID()
        val aggregateId = UUID.randomUUID()
        publishAuditEvent(auditEvent(eventId, aggregateId, actorId = actorId))
        awaitEntry(eventId)

        val trail = getJson("/api/v1/audit/entries/by-actor/$actorId", 200)
        assertThat(trail.isArray).isTrue()
        assertThat(trail.any { it.get("id").asText() == eventId.toString() }).isTrue()
    }

    @Test
    @TestSecurity(user = "audit-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `an operator without the audit role cannot inspect the trail`() {
        getJson("/api/v1/audit/entries/${UUID.randomUUID()}", 403)
        getJson("/api/v1/audit/evidence/${UUID.randomUUID()}", 403)
        getJson("/api/v1/audit/entries/by-actor/anyone", 403)
    }

    @Test
    fun `an anonymous caller is refused before any handler`() {
        getJson("/api/v1/audit/entries/${UUID.randomUUID()}", 401)
        getJson("/api/v1/audit/integrity", 401)
    }

    private fun countEntries(eventId: UUID): Long = dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT count(*) FROM audit_entries WHERE entry_id = ?").use { ps ->
            ps.setObject(1, eventId)
            ps.executeQuery().use { rs ->
                check(rs.next())
                rs.getLong(1)
            }
        }
    }
}
