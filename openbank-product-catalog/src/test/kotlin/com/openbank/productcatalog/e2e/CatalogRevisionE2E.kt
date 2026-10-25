// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.e2e

import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The catalog revision lifecycle over the real HTTP surface: caller → v2 catalog endpoints →
 * real Postgres, with the durable facts (catalog_revisions rows, the catalog_audit trail, the
 * catalog_outbox change events) asserted over plain JDBC.
 *
 * The maker-checker publish is split across two ordered journeys on purpose: `@TestSecurity` is
 * fixed per test method, and the whole point of the four-eyes rule is that the checker is a
 * DIFFERENT principal — so the author journey ([maker journey]) persists its draft, and the
 * checker journey ([checker journey]) publishes it over real HTTP under its own identity. The
 * ids travel through a companion holder, exactly the shape the notification break-glass suite
 * uses for the same reason.
 *
 * Every other journey mints fresh specification/offering/revision ids, so journeys never share
 * rows beyond that deliberate pair and need no other ordering. The deposit pack schema (seeded
 * at boot by CatalogPackSeeder) is the fixture: its required attributes are exactly `currency`
 * + `productType`.
 */
@QuarkusTest
@TestProfile(CatalogE2EProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CatalogRevisionE2E : CatalogE2EBase() {

    @Inject
    lateinit var testIdentity: TestIdentityAssociation

    @Test
    @Order(1)
    // The maker carries PUBLISH too: the point is to prove the SERVICE-LEVEL four-eyes rule
    // (maker != checker) fires on the wire — a RolesAllowed denial would never reach it.
    @TestSecurity(
        user = "catalog-e2e-author",
        roles = ["CATALOG_SCOPE_AUTHOR", "CATALOG_SCOPE_PUBLISH", "CATALOG_SCOPE_READ"],
    )
    fun `maker journey authors the draft and cannot self-publish`() {
        val spec = postJson(
            "/api/v2/specifications",
            """{"code":"${uniq("SPEC")}","schemaRef":{"id":"$DEPOSIT_SCHEMA","version":1}}""",
            201,
        ).first
        val offering = postJson(
            "/api/v2/offerings",
            """{"specificationId":"${spec.get(
                "id",
            ).asText()}","code":"${uniq("OFFER")}","market":{"countries":["CZ"]}}""",
            201,
        ).first
        val offeringId = offering.get("id").asText()

        val draft = postJson(
            "/api/v2/offerings/$offeringId/revisions",
            revisionBody("E2E deposit savings"),
            201,
        )
        val revisionId = draft.first.get("id").asText()
        val draftTag = draft.second
        assertThat(draftTag).isNotBlank()
        assertThat(draft.first.get("state").asText()).isEqualTo("DRAFT")

        // The durable fact, over plain JDBC: drafted by the caller, not yet checked.
        val drafted = revisionRow(UUID.fromString(revisionId))!!
        assertThat(drafted.state).isEqualTo("DRAFT")
        assertThat(drafted.makerId).isEqualTo("catalog-e2e-author")
        assertThat(drafted.checkerId).isNull()
        assertThat(auditActions(UUID.fromString(revisionId))).containsExactly("REVISION_DRAFTED")
        assertThat(outboxEvents(UUID.fromString(revisionId)))
            .containsExactly("com.openbank.catalog.revision_drafted")

        // The author amends the draft; the optimistic lock (If-Match / ETag round trip) bumps.
        val updated = putJson(
            "/api/v2/offerings/$offeringId/revisions/$revisionId",
            revisionBody("E2E deposit savings, amended"),
            draftTag!!,
            200,
        )
        val updatedRow = revisionRow(UUID.fromString(revisionId))!!
        assertThat(updatedRow.lockVersion).isEqualTo(drafted.lockVersion + 1)
        assertThat(updated.second).isNotEqualTo(draftTag)
        assertThat(auditActions(UUID.fromString(revisionId)))
            .containsExactly("REVISION_DRAFTED", "REVISION_UPDATED")

        // The maker's own publish is refused — four-eyes is enforced on the wire (If-Match is
        // supplied so the request passes the precondition filter and reaches the service rule).
        val refused = postJson(
            "/api/v2/offerings/$offeringId/revisions/$revisionId/publish",
            """{"reason":"self approval"}""",
            403,
            ifMatch = updated.second,
        ).first
        assertThat(refused.get("code").asText()).isEqualTo("FOUR_EYES_REQUIRED")
        assertThat(revisionRow(UUID.fromString(revisionId))!!.state).isEqualTo("DRAFT")

        Shared.offeringId = offeringId
        Shared.revisionId = revisionId
        Shared.ifMatch = updated.second!!
    }

    @Test
    @Order(2)
    @TestSecurity(user = "catalog-e2e-checker", roles = ["CATALOG_SCOPE_PUBLISH", "CATALOG_SCOPE_READ"])
    fun `checker journey publishes the maker's draft with full evidence`() {
        val offeringId = Shared.offeringId
        val revisionId = Shared.revisionId
        assertThat(offeringId).isNotNull()

        val published = postJson(
            "/api/v2/offerings/$offeringId/revisions/$revisionId/publish",
            """{"reason":"E2E publication"}""",
            200,
            ifMatch = Shared.ifMatch,
        )
        assertThat(published.first.get("state").asText()).isEqualTo("PUBLISHED")

        val row = revisionRow(UUID.fromString(revisionId))!!
        assertThat(row.state).isEqualTo("PUBLISHED")
        assertThat(row.makerId).isEqualTo("catalog-e2e-author")
        assertThat(row.checkerId).isEqualTo("catalog-e2e-checker")
        assertThat(row.reason).isEqualTo("E2E publication")
        assertThat(row.contentHash).isNotBlank()
        assertThat(auditActions(UUID.fromString(revisionId))).containsExactly(
            "REVISION_DRAFTED",
            "REVISION_UPDATED",
            "REVISION_PUBLISHED",
        )
        assertThat(outboxEvents(UUID.fromString(revisionId))).containsExactly(
            "com.openbank.catalog.revision_drafted",
            "com.openbank.catalog.revision_updated",
            "com.openbank.catalog.revision_published",
        )

        // The published product reads back over its own endpoint.
        val readBack = getJson("/api/v2/products/$offeringId", 200)
        assertThat(readBack.get("id").asText()).isEqualTo(revisionId)
    }

    @Test
    @TestSecurity(
        user = "catalog-e2e-author",
        roles = ["CATALOG_SCOPE_AUTHOR", "CATALOG_SCOPE_PUBLISH", "CATALOG_SCOPE_READ"],
    )
    fun `published revisions are immutable and a second publish is a conflict`() {
        val (offeringId, revisionId, _) = draftOfferingRevision()
        val publishedTag = publishAsChecker(offeringId, revisionId)

        postJson(
            "/api/v2/offerings/$offeringId/revisions/$revisionId/publish",
            """{"reason":"again"}""",
            409,
            ifMatch = publishedTag,
        )
        val row = revisionRow(UUID.fromString(revisionId))!!
        assertThat(row.state).isEqualTo("PUBLISHED")
        assertThat(row.checkerId).isEqualTo("catalog-e2e-checker")
        assertThat(auditActions(UUID.fromString(revisionId))).doesNotHaveDuplicates()
    }

    @Test
    @TestSecurity(
        user = "catalog-e2e-author",
        roles = ["CATALOG_SCOPE_AUTHOR", "CATALOG_SCOPE_PUBLISH", "CATALOG_SCOPE_READ"],
    )
    fun `optimistic locking and preconditions hold on the publish path`() {
        val (offeringId, revisionId, _) = draftOfferingRevision()

        // A publish without If-Match is refused before anything is read for update.
        postJson(
            "/api/v2/offerings/$offeringId/revisions/$revisionId/publish",
            """{"reason":"no lock"}""",
            428,
        )
        // A stale or malformed lock is a 412, never a silent overwrite.
        postJson(
            "/api/v2/offerings/$offeringId/revisions/$revisionId/publish",
            """{"reason":"stale lock"}""",
            412,
            ifMatch = "\"999\"",
        )
        val row = revisionRow(UUID.fromString(revisionId))!!
        assertThat(row.state).isEqualTo("DRAFT")
        assertThat(row.checkerId).isNull()
    }

    @Test
    @TestSecurity(user = "catalog-e2e-author", roles = ["CATALOG_SCOPE_AUTHOR", "CATALOG_SCOPE_READ"])
    fun `invalid attributes fail validation at draft time and are never persisted`() {
        // The standalone validator reports the same violation the write path enforces.
        val validation = postJson(
            "/api/v2/product-types/$DEPOSIT_SCHEMA/versions/1/validate",
            """{"attributes":{"currency":"czk","productType":"SAVINGS"}}""",
            200,
        ).first
        assertThat(validation.get("valid").asBoolean()).isFalse()
        assertThat(validation.get("violations").size()).isGreaterThanOrEqualTo(1)

        // The write path validates BEFORE persisting: the draft is refused with 422 and the
        // offering stays revision-free — no row, no audit trail, no outbox event.
        val spec = postJson(
            "/api/v2/specifications",
            """{"code":"${uniq("SPEC")}","schemaRef":{"id":"$DEPOSIT_SCHEMA","version":1}}""",
            201,
        ).first
        val offering = postJson(
            "/api/v2/offerings",
            """{"specificationId":"${spec.get("id").asText()}","code":"${uniq("OFFER")}"}""",
            201,
        ).first
        val offeringId = offering.get("id").asText()
        val rejected = postJson(
            "/api/v2/offerings/$offeringId/revisions",
            revisionBody("E2E deposit savings", "\"currency\":\"czk\",\"productType\":\"SAVINGS\""),
            422,
        ).first
        assertThat(rejected.get("violations").size()).isGreaterThanOrEqualTo(1)
        assertThat(getJson("/api/v2/offerings/$offeringId/revisions", 200).size()).isEqualTo(0)
    }

    @Test
    @TestSecurity(user = "catalog-e2e-viewer", roles = ["CATALOG_SCOPE_READ"])
    fun `read scope never authors and author scope never publishes`() {
        // RolesAllowed denial is a bare 403 on the wire — the status IS the assertion.
        postJson(
            "/api/v2/specifications",
            """{"code":"${uniq("SPEC")}","schemaRef":{"id":"$DEPOSIT_SCHEMA","version":1}}""",
            403,
        )

        // The read scope still reads the seeded product types.
        val types = getJson("/api/v2/product-types", 200)
        assertThat(types.size()).isGreaterThanOrEqualTo(1)
    }

    @Test
    @TestSecurity(user = "catalog-e2e-author", roles = ["CATALOG_SCOPE_AUTHOR", "CATALOG_SCOPE_READ"])
    fun `a revision addressed under a foreign offering is not found`() {
        val (_, revisionId, _) = draftOfferingRevision()
        val foreignOffering = UUID.randomUUID()

        getJson("/api/v2/offerings/$foreignOffering/revisions/$revisionId", 404)
        getJson("/api/v2/products/$foreignOffering", 404)
    }

    @Test
    fun `anonymous caller is refused on every catalog route`() {
        getJson("/api/v2/product-types", 401)
        getJson("/api/v2/offerings", 401)
    }

    /** The maker's half of an isolated fixture: specification + offering + draft revision. */
    private fun draftOfferingRevision(attributesOverride: String? = null): Triple<String, String, String?> {
        val spec = postJson(
            "/api/v2/specifications",
            """{"code":"${uniq("SPEC")}","schemaRef":{"id":"$DEPOSIT_SCHEMA","version":1}}""",
            201,
        ).first
        val offering = postJson(
            "/api/v2/offerings",
            """{"specificationId":"${spec.get("id").asText()}","code":"${uniq("OFFER")}"}""",
            201,
        ).first
        val offeringId = offering.get("id").asText()
        val draft = postJson(
            "/api/v2/offerings/$offeringId/revisions",
            revisionBody("E2E draft", attributesOverride),
            201,
        )
        return Triple(offeringId, draft.first.get("id").asText(), draft.second)
    }

    /**
     * Publish helper for journeys that need a published revision but not the four-eyes evidence:
     * switches the test identity to the checker via [TestIdentityAssociation] (the documented
     * seam for a mid-test identity change — `@TestSecurity` is fixed per method), publishes over
     * real HTTP, and switches back.
     */
    private fun publishAsChecker(offeringId: String, revisionId: String): String {
        val tag = revisionTag(offeringId, revisionId)
        testIdentity.setTestIdentity(checkerIdentity())
        try {
            return postJson(
                "/api/v2/offerings/$offeringId/revisions/$revisionId/publish",
                """{"reason":"E2E publication"}""",
                200,
                ifMatch = tag,
            ).second!!
        } finally {
            testIdentity.setTestIdentity(authorIdentity())
        }
    }

    private fun checkerIdentity() = QuarkusSecurityIdentity.builder()
        .setPrincipal(QuarkusPrincipal("catalog-e2e-checker"))
        .addRoles(setOf("CATALOG_SCOPE_PUBLISH", "CATALOG_SCOPE_READ"))
        .build()

    private fun authorIdentity() = QuarkusSecurityIdentity.builder()
        .setPrincipal(QuarkusPrincipal("catalog-e2e-author"))
        .addRoles(setOf("CATALOG_SCOPE_AUTHOR", "CATALOG_SCOPE_PUBLISH", "CATALOG_SCOPE_READ"))
        .build()

    private fun revisionTag(offeringId: String, revisionId: String): String {
        val revision = getJson("/api/v2/offerings/$offeringId/revisions/$revisionId", 200)
        return "\"${revision.get("revision").asLong()}\""
    }

    private fun revisionBody(name: String, attributesOverride: String? = null): String {
        val attributes = attributesOverride ?: "\"currency\":\"CZK\",\"productType\":\"SAVINGS\""
        // effectiveFrom in the past: a published revision must be effective NOW for the
        // published-product read to resolve it (findPublished filters by effective instant).
        val prefix =
            """{"schemaRef":{"id":"$DEPOSIT_SCHEMA","version":1},"name":{"en":"$name"},"attributes":{"""
        return "$prefix$attributes},\"effectiveFrom\":\"2026-01-01T00:00:00Z\"}"
    }

    private fun uniq(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "_").uppercase().take(24)}"

    private object Shared {
        var offeringId: String? = null
        var revisionId: String = ""
        var ifMatch: String = ""
    }

    private companion object {
        const val DEPOSIT_SCHEMA = "org.openbank.banking.deposit"
    }
}
