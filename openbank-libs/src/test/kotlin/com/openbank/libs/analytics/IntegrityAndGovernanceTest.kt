// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.analytics

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.Period
import java.util.UUID

/**
 * Covers the Phase-5 regulatory-remediation primitives (ADR-0023): tamper-evidence (F1+F2),
 * maker-checker four-eyes (F3), count reconciliation + evidence fingerprint (F4), completeness
 * gap detection (F5), per-category retention (F6) and schema governance (F7). Pure libs, no boot.
 */
class IntegrityAndGovernanceTest {

    private fun env(
        eventId: UUID = UUID.randomUUID(),
        aggregateType: String = "ACCOUNT",
        aggregateId: String = "acc-1",
        version: Long = 1,
        occurredAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        ingestedAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        ingestSource: IngestSource = IngestSource.STREAM,
        batchId: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ) = AnalyticsEnvelope(
        eventId = eventId, aggregateType = aggregateType, aggregateId = aggregateId,
        aggregateVersion = version, eventType = "account.changed", occurredAt = occurredAt,
        sourceService = "svc", schemaVersion = 1, ingestSource = ingestSource, batchId = batchId,
        ingestedAt = ingestedAt, payload = payload,
    )

    // --- F1+F2 integrity -------------------------------------------------------------------------

    @Test
    fun `recordHash is deterministic and independent of ingest lineage and time`() {
        val id = UUID.randomUUID()
        val stream = env(
            eventId = id,
            ingestSource = IngestSource.STREAM,
            batchId = null,
            ingestedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
        val reloaded = env(
            eventId = id,
            ingestSource = IngestSource.CORRECTION,
            batchId = "batch-9",
            ingestedAt = Instant.parse("2026-05-01T00:00:00Z"),
        )

        // Same event, same business content ⇒ same hash regardless of how/when it was loaded.
        assertThat(AnalyticsIntegrity.recordHash(stream)).isEqualTo(AnalyticsIntegrity.recordHash(reloaded))
    }

    @Test
    fun `recordHash changes when business content is tampered`() {
        val base = env(version = 1, payload = mapOf("amount" to "100"))
        val tampered = env(eventId = base.eventId, version = 1, payload = mapOf("amount" to "999"))
        assertThat(AnalyticsIntegrity.recordHash(base)).isNotEqualTo(AnalyticsIntegrity.recordHash(tampered))
    }

    @Test
    fun `canonical payload is order-independent for maps`() {
        val a = env(payload = mapOf("b" to "2", "a" to "1"))
        val b = env(eventId = a.eventId, payload = mapOf("a" to "1", "b" to "2"))
        assertThat(AnalyticsIntegrity.recordHash(a)).isEqualTo(AnalyticsIntegrity.recordHash(b))
    }

    @Test
    fun `merkleRoot is order-independent and detects a changed leaf`() {
        val e1 = env(eventId = UUID.randomUUID())
        val e2 = env(eventId = UUID.randomUUID())
        val e3 = env(eventId = UUID.randomUUID())

        val root1 = AnalyticsIntegrity.merkleRootOf(listOf(e1, e2, e3))
        val root2 = AnalyticsIntegrity.merkleRootOf(listOf(e3, e1, e2))
        assertThat(root1).isEqualTo(root2) // leaves sorted ⇒ ingest order does not matter

        val tampered = env(eventId = e2.eventId, payload = mapOf("x" to "changed"))
        val root3 = AnalyticsIntegrity.merkleRootOf(listOf(e1, tampered, e3))
        assertThat(root3).isNotEqualTo(root1) // any leaf change ⇒ different root
    }

    @Test
    fun `merkleRoot of empty batch is defined`() {
        assertThat(AnalyticsIntegrity.merkleRoot(emptyList())).isNotBlank()
    }

    // --- F3 maker-checker ------------------------------------------------------------------------

    @Test
    fun `approve by a different checker moves PROPOSED to APPROVED`() {
        val p = Proposal(id = "p1", action = "backfill", proposedBy = "alice", proposedAt = Instant.now())
        val approved = p.approve(checker = "bob", at = Instant.now())
        assertThat(approved.state).isEqualTo(ProposalState.APPROVED)
        assertThat(approved.decidedBy).isEqualTo("bob")
    }

    @Test
    fun `four-eyes rejects self-approval`() {
        val p = Proposal(id = "p1", action = "backfill", proposedBy = "alice", proposedAt = Instant.now())
        assertThatThrownBy { p.approve(checker = "alice", at = Instant.now()) }
            .isInstanceOf(MakerCheckerViolation::class.java)
    }

    @Test
    fun `cannot execute before approval and can execute once approved`() {
        val p = Proposal(id = "p1", action = "x", proposedBy = "alice", proposedAt = Instant.now())
        assertThatThrownBy { p.markExecuted(Instant.now()) }.isInstanceOf(MakerCheckerViolation::class.java)
        val done = p.approve("bob", Instant.now()).markExecuted(Instant.now())
        assertThat(done.state).isEqualTo(ProposalState.EXECUTED)
        assertThat(done.isTerminal).isTrue()
    }

    @Test
    fun `cannot approve an already-decided proposal`() {
        val rejected = Proposal(id = "p1", action = "x", proposedBy = "alice", proposedAt = Instant.now())
            .reject("bob", Instant.now())
        assertThatThrownBy { rejected.approve("carol", Instant.now()) }
            .isInstanceOf(MakerCheckerViolation::class.java)
    }

    // --- F4 count reconciliation + evidence ------------------------------------------------------

    @Test
    fun `countDiff flags per-type row count drift`() {
        val deltas = Reconciliation.countDiff(
            source = mapOf("ACCOUNT" to 100L, "PARTY" to 50L),
            warehouse = mapOf("ACCOUNT" to 100L, "PARTY" to 48L),
        )
        assertThat(deltas["ACCOUNT"]!!.inSync).isTrue()
        assertThat(deltas["PARTY"]!!.delta).isEqualTo(2L)
    }

    @Test
    fun `reconciliation fingerprint is stable for the same diff`() {
        val diff = Reconciliation.diff(
            mapOf(AggregateKey("ACCOUNT", "1") to 5L, AggregateKey("ACCOUNT", "2") to 7L),
            mapOf(AggregateKey("ACCOUNT", "1") to 5L),
        )
        assertThat(Reconciliation.fingerprint(diff)).isEqualTo(Reconciliation.fingerprint(diff))
    }

    // --- F5 completeness -------------------------------------------------------------------------

    @Test
    fun `completeness detects a missing version in the middle of the sequence`() {
        val events = listOf(env(version = 1), env(version = 2), env(version = 4)) // 3 missing
        val report = Completeness.gaps(events)
        assertThat(report.complete).isFalse()
        assertThat(report.gaps.single().missingVersions).containsExactly(3L)
    }

    @Test
    fun `completeness is COMPLETE for a contiguous sequence and tolerates duplicates`() {
        val events = listOf(env(version = 1), env(version = 1), env(version = 2), env(version = 3))
        val report = Completeness.gaps(events)
        assertThat(report.complete).isTrue()
        assertThat(report.status).isEqualTo("COMPLETE")
    }

    // --- F6 retention ----------------------------------------------------------------------------

    @Test
    fun `accounting category is a non-erasable 10-year statutory hold`() {
        val policy = RetentionPolicies.of(DataCategory.ACCOUNTING)
        assertThat(policy.erasable).isFalse()
        assertThat(policy.basis).isEqualTo(LegalBasis.LEGAL_OBLIGATION)
        assertThat(policy.retention.years).isGreaterThanOrEqualTo(10)
    }

    @Test
    fun `consent category is erasable with a shorter retention`() {
        val policy = RetentionPolicies.of(DataCategory.CONSENT)
        assertThat(policy.erasable).isTrue()
        assertThat(RetentionPolicies.erasableCategories()).contains(DataCategory.CONSENT)
        assertThat(RetentionPolicies.erasableCategories()).doesNotContain(DataCategory.ACCOUNTING)
    }

    @Test
    fun `unmapped aggregate type defaults to the strictest non-erasable category`() {
        assertThat(RetentionPolicies.categoryForAggregateType("SOMETHING_NEW"))
            .isEqualTo(DataCategory.ACCOUNTING)
        assertThat(RetentionPolicies.categoryForAggregateType("transaction"))
            .isEqualTo(DataCategory.ACCOUNTING)
    }

    @Test
    fun `policy expiry is created-at plus retention`() {
        val policy =
            CategoryPolicy(DataCategory.OPERATIONAL, LegalBasis.LEGITIMATE_INTEREST, Period.ofYears(1), erasable = true)
        val created = Instant.parse("2026-01-01T00:00:00Z")
        assertThat(policy.isExpired(created, Instant.parse("2026-06-01T00:00:00Z"))).isFalse()
        assertThat(policy.isExpired(created, Instant.parse("2027-02-01T00:00:00Z"))).isTrue()
    }

    // --- F7 schema governance --------------------------------------------------------------------

    @Test
    fun `schema catalog accepts known and backward-compatible versions, rejects newer or unknown`() {
        val catalog = SchemaCatalog(setOf(SchemaKey("account.changed", 1), SchemaKey("account.changed", 2)))
        assertThat(catalog.isKnown(SchemaKey("account.changed", 2))).isTrue()
        assertThat(catalog.isCompatible(SchemaKey("account.changed", 1))).isTrue()
        assertThat(catalog.isCompatible(SchemaKey("account.changed", 3))).isFalse() // newer than known
        assertThat(catalog.isCompatible(SchemaKey("party.created", 1))).isFalse() // unknown type
    }
}
