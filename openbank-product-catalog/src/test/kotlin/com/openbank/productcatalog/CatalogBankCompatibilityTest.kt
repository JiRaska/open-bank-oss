// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.productcatalog.infrastructure.persistence.BankV1CompatibilityBackfill
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.security.Principal
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
@Suppress("LargeClass")
class CatalogBankCompatibilityTest {
    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var backfill: BankV1CompatibilityBackfill

    @Inject
    lateinit var testIdentity: TestIdentityAssociation

    @Inject
    lateinit var mapper: ObjectMapper

    @Test
    fun `legacy backfill preserves all canonical ids and is idempotent`() {
        val products = scalar("SELECT COUNT(*) FROM products")
        assertThat(products).isGreaterThan(0)
        assertThat(scalar("SELECT COUNT(*) FROM bank_v1_product_mapping")).isEqualTo(products)
        assertThat(
            scalar(
                """SELECT COUNT(*) FROM bank_v1_product_mapping m """ +
                    """JOIN catalog_specifications s ON s.id = m.product_id """ +
                    """JOIN products p ON p.id = m.product_id WHERE s.code = p.code""",
            ),
        ).isEqualTo(products)

        assertThat(backfill.run()).isZero()
        assertThat(scalar("SELECT COUNT(*) FROM bank_v1_product_mapping")).isEqualTo(products)
    }

    @Test
    fun `periodic reconciliation catches a rollback era v1 write without another restart`() {
        switchIdentity("rollback-setup")
        val code = "CURRENT_ROLLBACK_${UUID.randomUUID().toString().take(8).uppercase()}"
        val created = given().contentType("application/json")
            .body("""{"code":"$code","name":"Before rollback","type":"CURRENT","currency":"EUR"}""")
            .post("/api/v1/products").then().statusCode(201).extract()
        val productId = UUID.fromString(created.jsonPath().getString("id"))
        val offeringId = mappedOffering(productId)
        val rollbackDocument = mapper.readTree(created.asString()) as ObjectNode
        rollbackDocument.put("name", "Changed by P0 rollback")

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE products SET doc = to_jsonb(CAST(? AS text)), status = 'DRAFT' WHERE id = ?",
            ).use { statement ->
                statement.setString(1, mapper.writeValueAsString(rollbackDocument))
                statement.setObject(2, productId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }

        runBlocking { backfill.reconcileAfterRollingWriters() }
        assertThat(legacyName(latestDraft(offeringId))).isEqualTo("Changed by P0 rollback")
        assertThat(backfill.run()).isZero()
    }

    @Test
    fun `backfill reconciles a publication made by a rollback era v2 writer`() {
        switchIdentity("rollback-v2-author")
        val code = "CURRENT_V2_ROLLBACK_${UUID.randomUUID().toString().take(8).uppercase()}"
        val created = given().contentType("application/json")
            .body("""{"code":"$code","name":"Before v2 rollback","type":"CURRENT","currency":"EUR"}""")
            .post("/api/v1/products").then().statusCode(201).extract()
        val productId = UUID.fromString(created.jsonPath().getString("id"))
        val offeringId = mappedOffering(productId)
        val revisionId = latestDraft(offeringId)

        simulateRollbackEraV2Publication(revisionId)

        assertThat(backfill.run()).isEqualTo(1)
        given().get("/api/v1/products/$productId").then()
            .statusCode(200)
            .body("name", equalTo("Before v2 rollback"))
            .body("status", equalTo("ACTIVE"))
        assertThat(backfill.run()).isZero()
    }

    @Test
    fun `v1 update cannot overwrite a draft edited independently through v2`() {
        switchIdentity("draft-conflict-author")
        val productId = createLegacyDraft("CURRENT_DRAFT_CONFLICT")
        val offeringId = mappedOffering(productId)
        val revisionId = latestDraft(offeringId)
        try {
            editDraftName(offeringId, revisionId, "Independent v2 edit")
            val update = mapper.readTree(
                given().get("/api/v1/products/$productId").then().statusCode(200).extract().asString(),
            ) as ObjectNode
            update.put("name", "Conflicting v1 edit")

            given().contentType("application/json")
                .header("If-Match", "\"0\"")
                .body(mapper.writeValueAsString(update))
                .put("/api/v1/products/$productId").then()
                .statusCode(409)
                .body("code", equalTo("CATALOG_CONFLICT"))

            assertThat(legacyName(revisionId)).isEqualTo("Independent v2 edit")
        } finally {
            deleteLegacyDraft(productId, offeringId, revisionId)
        }
    }

    @Test
    fun `V7 rollout fails closed when a draft has no published comparison baseline`() {
        switchIdentity("v6-conflict-author")
        val productId = createLegacyDraft("CURRENT_V6_CONFLICT")
        val offeringId = mappedOffering(productId)
        val revisionId = latestDraft(offeringId)
        try {
            editDraftName(offeringId, revisionId, "Work authored before V6")
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE bank_v1_product_mapping SET last_synced_product_revision = -1, " +
                        "last_synced_draft_revision = -2 WHERE product_id = ?",
                ).use { statement ->
                    statement.setObject(1, productId)
                    assertThat(statement.executeUpdate()).isEqualTo(1)
                }
            }

            assertThatThrownBy { backfill.run() }
                .hasMessageContaining("has no published compatibility baseline")
            assertThat(legacyName(revisionId)).isEqualTo("Work authored before V6")
        } finally {
            deleteLegacyDraft(productId, offeringId, revisionId)
        }
    }

    @Test
    fun `V7 accepts an equivalent dual write made by the previous bank adapter`() {
        switchIdentity("previous-adapter-author")
        val productId = createLegacyDraft("CURRENT_PREVIOUS_ADAPTER")
        val offeringId = mappedOffering(productId)
        val before = watermarks(productId)
        val update = mapper.readTree(
            given().get("/api/v1/products/$productId").then().statusCode(200).extract().asString(),
        ) as ObjectNode
        update.put("name", "Changed atomically by the previous adapter")
        given().contentType("application/json").header("If-Match", "\"0\"")
            .body(mapper.writeValueAsString(update)).put("/api/v1/products/$productId").then().statusCode(200)
        setWatermarks(productId, before.first, before.second)

        assertThat(backfill.run()).isEqualTo(1)
        assertThat(legacyName(latestDraft(offeringId))).isEqualTo("Changed atomically by the previous adapter")
        assertThat(backfill.run()).isZero()
    }

    @Test
    fun `V7 detects a rollback era deactivation against its active published baseline`() {
        switchIdentity("deactivation-author")
        val productId = createLegacyDraft("CURRENT_V7_DEACTIVATED")
        val offeringId = mappedOffering(productId)
        publishLatestDraft(offeringId, "deactivation-checker")
        rawLegacyStatusChange(productId, "INACTIVE")
        resetCompatibilityWatermarks(productId)

        assertThat(backfill.run()).isEqualTo(1)
        assertThat(legacyStatus(latestDraft(offeringId))).isEqualTo("INACTIVE")
    }

    @Test
    fun `V7 upgrade turns a one sided rollback era v1 change into a draft`() {
        switchIdentity("v7-one-sided-author")
        val productId = createLegacyDraft("CURRENT_V7_ONE_SIDED")
        val offeringId = mappedOffering(productId)
        publishLatestDraft(offeringId, "v7-one-sided-checker")
        rawLegacyNameChange(productId, "Changed before V7 started")
        resetCompatibilityWatermarks(productId)

        assertThat(backfill.run()).isEqualTo(1)
        assertThat(legacyName(latestDraft(offeringId))).isEqualTo("Changed before V7 started")
    }

    @Test
    fun `V7 upgrade rejects independent rollback era changes on both authorities`() {
        switchIdentity("v7-divergent-author")
        val productId = createLegacyDraft("CURRENT_V7_DIVERGENT")
        val offeringId = mappedOffering(productId)
        publishLatestDraft(offeringId, "v7-divergent-checker")
        val originalName = given().get("/api/v1/products/$productId").then()
            .statusCode(200)
            .extract().jsonPath().getString("name")
        rawLegacyNameChange(productId, "Independent legacy change")
        simulateNewPublishedRevision(offeringId)
        resetCompatibilityWatermarks(productId)

        val lateProductId = createLegacyDraft("ZZZ_LATE_ROLLBACK_WRITE")
        val lateOfferingId = mappedOffering(lateProductId)
        publishLatestDraft(lateOfferingId, "late-write-checker")
        rawLegacyNameChange(lateProductId, "Late write must not be blocked")
        resetCompatibilityWatermarks(lateProductId)

        assertThatThrownBy { backfill.run() }
            .hasMessage("banking product $productId changed independently in v1 and v2 and requires reconciliation")
        assertThat(legacyName(latestDraft(lateOfferingId))).isEqualTo("Late write must not be blocked")
        given().get("/api/v1/products/$productId").then()
            .statusCode(200)
            .body("name", equalTo("Independent legacy change"))

        rawLegacyNameChange(productId, originalName)
        resetCompatibilityWatermarks(productId)
        assertThat(backfill.run()).isEqualTo(1)
    }

    @Test
    fun `live v2 publish cannot overwrite a rollback era v1 write`() {
        switchIdentity("live-race-author")
        val productId = createLegacyDraft("CURRENT_LIVE_RACE")
        val offeringId = mappedOffering(productId)
        val revisionId = latestDraft(offeringId)
        rawLegacyNameChange(productId, "Must survive publication")
        switchIdentity("live-race-checker")

        given().contentType("application/json")
            .header("If-Match", "\"0\"")
            .body("""{"reason":"independent approval after rollback writer"}""")
            .post("/api/v2/offerings/$offeringId/revisions/$revisionId/publish")
            .then().statusCode(409)
            .body("code", equalTo("CATALOG_CONFLICT"))
        given().get("/api/v1/products/$productId").then()
            .statusCode(200)
            .body("name", equalTo("Must survive publication"))

        assertThat(backfill.run()).isEqualTo(1)
    }

    @Test
    fun `v1 edit removes normalized relationships from the mapped draft`() {
        switchIdentity("relationship-author")
        val sourceId = createLegacyDraft("CURRENT_REL_SOURCE")
        val targetId = createLegacyDraft("CURRENT_REL_TARGET")
        val offeringId = mappedOffering(sourceId)
        val revisionId = latestDraft(offeringId)
        insertRelationship(revisionId, mappedOffering(targetId))

        val source = given().get("/api/v1/products/$sourceId").then().statusCode(200).extract().asString()
        val update = mapper.readTree(source) as ObjectNode
        update.put("name", "Relationship removed by v1")
        given().contentType("application/json")
            .header("If-Match", "\"0\"")
            .body(mapper.writeValueAsString(update))
            .put("/api/v1/products/$sourceId").then().statusCode(200)

        assertThat(relationshipCount(revisionId)).isZero()
    }

    @Test
    fun `v1 draft changes and v2 publication share one lossless banking projection`() {
        switchIdentity("bank-author")
        val code = "CURRENT_COMPAT_${UUID.randomUUID().toString().take(8).uppercase()}"
        val created = given()
            .contentType("application/json")
            .body("""{"code":"$code","name":"Compatibility draft","type":"CURRENT","currency":"EUR"}""")
            .post("/api/v1/products")
            .then()
            .statusCode(201)
            .header("ETag", equalTo("\"0\""))
            .extract()
        val productId = UUID.fromString(created.jsonPath().getString("id"))
        val offeringId = mappedOffering(productId)
        val revisionId = latestDraft(offeringId)

        given()
            .contentType("application/json")
            .header("If-Match", "\"0\"")
            .body("""{"code":"$code","name":"Compatibility edited","type":"CURRENT","currency":"EUR"}""")
            .put("/api/v1/products/$productId")
            .then()
            .statusCode(200)
            .header("ETag", equalTo("\"1\""))

        assertThat(legacyName(revisionId)).isEqualTo("Compatibility edited")
        assertThat(changeCount(revisionId, "BANK_V1_DRAFT_UPDATED")).isEqualTo(1)

        given()
            .contentType("application/json")
            .header("If-Match", "\"1\"")
            .body("""{"reason":"author must not approve their own banking edit"}""")
            .post("/api/v2/offerings/$offeringId/revisions/$revisionId/publish")
            .then()
            .statusCode(403)
            .body("code", equalTo("FOUR_EYES_REQUIRED"))

        switchIdentity("bank-checker")

        given()
            .contentType("application/json")
            .header("If-Match", "\"1\"")
            .body("""{"reason":"approved compatibility projection"}""")
            .post("/api/v2/offerings/$offeringId/revisions/$revisionId/publish")
            .then()
            .statusCode(200)

        given()
            .get("/api/v1/products/$productId")
            .then()
            .statusCode(200)
            .body("id", equalTo(productId.toString()))
            .body("code", equalTo(code))
            .body("name", equalTo("Compatibility edited"))
            .body("status", equalTo("ACTIVE"))
    }

    @Test
    fun `long legacy fee ids remain collision safe in the v2 projection`() {
        val code = "CURRENT_FEES_${UUID.randomUUID().toString().take(8).uppercase()}"
        val common = "fee-prefix-${"a".repeat(80)}"
        val created = given()
            .contentType("application/json")
            .body(
                """{"code":"$code","name":"Fee collision proof","type":"CURRENT","currency":"EUR","fees":[""" +
                    """{"id":"$common-x","name":"Fee A","type":"SERVICE","amount":1.0,""" +
                    """"currency":"EUR","frequency":"MONTHLY"},{"id":"$common-y","name":"Fee B",""" +
                    """"type":"SERVICE","amount":2.0,"currency":"EUR","frequency":"MONTHLY"}]}""",
            )
            .post("/api/v1/products")
            .then()
            .statusCode(201)
            .extract()

        val productId = UUID.fromString(created.jsonPath().getString("id"))
        val revisionId = latestDraft(mappedOffering(productId))
        assertThat(priceCodeCounts(revisionId)).containsExactly(3L, 3L)
    }

    @Test
    fun `banking projection rejects malformed contradictory and invalid legacy documents`() {
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { request -> request.with("attributes").put("legacyDocument", "{") },
            { request -> request.with("attributes").put("currency", "CZK") },
            { request ->
                val attributes = request.with("attributes")
                val legacy = mapper.readTree(attributes.path("legacyDocument").asText()) as ObjectNode
                legacy.putArray("fees").addObject()
                    .put("id", "negative-fee")
                    .put("name", "Negative fee")
                    .put("type", "SERVICE")
                    .put("amount", -1.0)
                    .put("currency", "EUR")
                    .put("frequency", "MONTHLY")
                attributes.put("legacyDocument", mapper.writeValueAsString(legacy))
            },
            { request -> request.with("name").put("en", "Contradictory outer name") },
            { request ->
                request.putArray("eligibility").addObject()
                    .put("field", "customer.age")
                    .put("operator", "GREATER_OR_EQUAL")
                    .put("expected", 18)
                    .putObject("explanation")
                    .put("en", "Adults only")
            },
        )

        mutations.forEachIndexed { index, mutate ->
            switchIdentity("bank-author-$index")
            val code = "CURRENT_INVALID_${index}_${UUID.randomUUID().toString().take(6).uppercase()}"
            val created = given()
                .contentType("application/json")
                .body("""{"code":"$code","name":"Invalid projection","type":"CURRENT","currency":"EUR"}""")
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract()
            val productId = UUID.fromString(created.jsonPath().getString("id"))
            val offeringId = mappedOffering(productId)
            val revisionId = latestDraft(offeringId)
            val request = revisionRequest(offeringId, revisionId)
            mutate(request)

            given()
                .contentType("application/json")
                .header("If-Match", "\"0\"")
                .body(mapper.writeValueAsString(request))
                .put("/api/v2/offerings/$offeringId/revisions/$revisionId")
                .then()
                .statusCode(200)

            switchIdentity("bank-checker-$index")
            given()
                .contentType("application/json")
                .header("If-Match", "\"1\"")
                .body("""{"reason":"must reject a lossy bank projection"}""")
                .post("/api/v2/offerings/$offeringId/revisions/$revisionId/publish")
                .then()
                .statusCode(409)
                .body("code", equalTo("CATALOG_CONFLICT"))
        }
    }

    private fun mappedOffering(productId: UUID): UUID = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT default_offering_id FROM bank_v1_product_mapping WHERE product_id = ?",
        ).use { statement ->
            statement.setObject(1, productId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getObject(1, UUID::class.java)
            }
        }
    }

    private fun latestDraft(offeringId: UUID): UUID = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT id FROM catalog_revisions WHERE offering_id = ? AND state = 'DRAFT' ORDER BY revision_no DESC",
        ).use { statement ->
            statement.setObject(1, offeringId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getObject(1, UUID::class.java)
            }
        }
    }

    private fun legacyName(revisionId: UUID): String = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT (content->'attributes'->>'legacyDocument')::jsonb->>'name' FROM catalog_revisions WHERE id = ?",
        ).use { statement ->
            statement.setObject(1, revisionId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }

    private fun priceCodeCounts(revisionId: UUID): List<Long> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*), COUNT(DISTINCT code) FROM catalog_price_components WHERE revision_id = ?",
        ).use { statement ->
            statement.setObject(1, revisionId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                listOf(rows.getLong(1), rows.getLong(2))
            }
        }
    }

    private fun revisionRequest(offeringId: UUID, revisionId: UUID): ObjectNode {
        val response = given()
            .get("/api/v2/offerings/$offeringId/revisions/$revisionId")
            .then()
            .statusCode(200)
            .extract()
            .asString()
        val revision = mapper.readTree(response)
        val content = revision.path("content")
        return mapper.createObjectNode().apply {
            set<ObjectNode>("schemaRef", revision.path("schemaRef").deepCopy())
            set<ObjectNode>("name", content.path("name").deepCopy())
            set<ObjectNode>("attributes", content.path("attributes").deepCopy())
            replace("prices", content.path("prices").deepCopy())
            replace("eligibility", content.path("eligibility").deepCopy())
            replace("relationships", content.path("relationships").deepCopy())
            replace("documentCodes", content.path("documentCodes").deepCopy())
        }
    }

    private fun editDraftName(offeringId: UUID, revisionId: UUID, name: String) {
        val request = revisionRequest(offeringId, revisionId)
        request.with("name").put("en", name)
        val attributes = request.with("attributes")
        val legacy = mapper.readTree(attributes.path("legacyDocument").asText()) as ObjectNode
        legacy.put("name", name)
        attributes.put("legacyDocument", mapper.writeValueAsString(legacy))
        given().contentType("application/json")
            .header("If-Match", "\"0\"")
            .body(mapper.writeValueAsString(request))
            .put("/api/v2/offerings/$offeringId/revisions/$revisionId")
            .then().statusCode(200)
    }

    private fun deleteLegacyDraft(productId: UUID, offeringId: UUID, revisionId: UUID) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            listOf(
                "DELETE FROM catalog_price_components WHERE revision_id = ?" to revisionId,
                "DELETE FROM catalog_relationships WHERE revision_id = ?" to revisionId,
                "DELETE FROM catalog_revisions WHERE id = ?" to revisionId,
                "DELETE FROM bank_v1_product_mapping WHERE product_id = ?" to productId,
                "DELETE FROM catalog_offerings WHERE id = ?" to offeringId,
                "DELETE FROM catalog_specifications WHERE id = ?" to productId,
                "DELETE FROM products WHERE id = ?" to productId,
            ).forEach { (sql, id) ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setObject(1, id)
                    statement.executeUpdate()
                }
            }
            connection.commit()
        }
    }

    private fun createLegacyDraft(prefix: String): UUID {
        val code = "${prefix}_${UUID.randomUUID().toString().take(8).uppercase()}"
        val response = given().contentType("application/json")
            .body("""{"code":"$code","name":"$code","type":"CURRENT","currency":"EUR"}""")
            .post("/api/v1/products").then().statusCode(201).extract()
        return UUID.fromString(response.jsonPath().getString("id"))
    }

    private fun publishLatestDraft(offeringId: UUID, checker: String) {
        val revisionId = latestDraft(offeringId)
        switchIdentity(checker)
        given().contentType("application/json")
            .header("If-Match", "\"0\"")
            .body("""{"reason":"bootstrap an active compatibility projection"}""")
            .post("/api/v2/offerings/$offeringId/revisions/$revisionId/publish")
            .then().statusCode(200)
    }

    private fun rawLegacyNameChange(productId: UUID, name: String) {
        val document = mapper.readTree(
            given().get("/api/v1/products/$productId").then().statusCode(200).extract().asString(),
        ) as ObjectNode
        document.put("name", name)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE products SET doc = to_jsonb(CAST(? AS text)) WHERE id = ?",
            ).use { statement ->
                statement.setString(1, mapper.writeValueAsString(document))
                statement.setObject(2, productId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private fun rawLegacyStatusChange(productId: UUID, status: String) {
        val document = mapper.readTree(
            given().get("/api/v1/products/$productId").then().statusCode(200).extract().asString(),
        ) as ObjectNode
        document.put("status", status)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE products SET doc = to_jsonb(CAST(? AS text)), status = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, mapper.writeValueAsString(document))
                statement.setString(2, status)
                statement.setObject(3, productId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private fun legacyStatus(revisionId: UUID): String = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT (content->'attributes'->>'legacyDocument')::jsonb->>'status' " +
                "FROM catalog_revisions WHERE id = ?",
        ).use { statement ->
            statement.setObject(1, revisionId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }

    private fun watermarks(productId: UUID): Pair<Long, Long> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT last_synced_product_revision, last_synced_draft_revision " +
                "FROM bank_v1_product_mapping WHERE product_id = ?",
        ).use { statement ->
            statement.setObject(1, productId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1) to rows.getLong(2)
            }
        }
    }

    private fun setWatermarks(productId: UUID, productRevision: Long, draftRevision: Long) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE bank_v1_product_mapping SET last_synced_product_revision = ?, " +
                    "last_synced_draft_revision = ? WHERE product_id = ?",
            ).use { statement ->
                statement.setLong(1, productRevision)
                statement.setLong(2, draftRevision)
                statement.setObject(3, productId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private fun resetCompatibilityWatermarks(productId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE bank_v1_product_mapping SET last_synced_product_revision = -1, " +
                    "last_synced_draft_revision = -2 WHERE product_id = ?",
            ).use { statement ->
                statement.setObject(1, productId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private fun simulateNewPublishedRevision(offeringId: UUID) {
        val replacementId = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "UPDATE catalog_revisions SET state = 'SUPERSEDED', effective_to = now() " +
                        "WHERE offering_id = ? AND state = 'PUBLISHED'",
                ).use { statement ->
                    statement.setObject(1, offeringId)
                    assertThat(statement.executeUpdate()).isEqualTo(1)
                }
                connection.prepareStatement(
                    "INSERT INTO catalog_revisions " +
                        "(id, offering_id, revision_no, schema_id, schema_version, state, content, effective_from, " +
                        "maker_id, checker_id, reason, content_hash, created_at, updated_at, lock_version) " +
                        "SELECT ?, offering_id, revision_no + 1, schema_id, schema_version, " +
                        "'PUBLISHED', content, now(), " +
                        "'rollback-v2-maker', 'rollback-v2-checker', 'independent rollback publication', " +
                        "repeat('b', 64), now(), now(), 0 FROM catalog_revisions " +
                        "WHERE offering_id = ? AND state = 'SUPERSEDED' ORDER BY revision_no DESC LIMIT 1",
                ).use { statement ->
                    statement.setObject(1, replacementId)
                    statement.setObject(2, offeringId)
                    assertThat(statement.executeUpdate()).isEqualTo(1)
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    private fun insertRelationship(revisionId: UUID, targetOfferingId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO catalog_relationships (id, revision_id, target_offering_id, kind) " +
                    "VALUES (?, ?, ?, 'RELATED')",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, revisionId)
                statement.setObject(3, targetOfferingId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private fun relationshipCount(revisionId: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM catalog_relationships WHERE revision_id = ?",
        ).use { statement ->
            statement.setObject(1, revisionId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private fun simulateRollbackEraV2Publication(revisionId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE catalog_revisions SET state = 'PUBLISHED', checker_id = 'rollback-v2-checker', " +
                    "reason = 'published by rollback-era v2', content_hash = repeat('a', 64) WHERE id = ?",
            ).use { statement ->
                statement.setObject(1, revisionId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private fun switchIdentity(name: String) {
        testIdentity.setTestIdentity(
            QuarkusSecurityIdentity.builder()
                .setPrincipal(Principal { name })
                .addRole("ROLE_OPERATOR")
                .build(),
        )
    }

    private fun changeCount(revisionId: UUID, action: String): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM catalog_audit WHERE aggregate_id = ? AND action = ?",
        ).use { statement ->
            statement.setObject(1, revisionId)
            statement.setString(2, action)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private fun scalar(sql: String): Long = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }
}
