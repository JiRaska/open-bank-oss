// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.productcatalog.infrastructure.catalog.CatalogJson
import com.openbank.productcatalog.infrastructure.catalog.CatalogSchemaProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
@Suppress("LargeClass")
class CatalogPlatformResourceTest {
    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var mapper: ObjectMapper

    @Inject
    lateinit var catalogJson: CatalogJson

    @Test
    fun validatesTrustedIndustryPacks() {
        Given { this } When {
            get("/api/v2/product-types")
        } Then {
            statusCode(200)
            header("X-API-Version", equalTo("v2"))
            body("size()", greaterThanOrEqualTo(2))
        }

        validateInsurance(INSURANCE_ATTRIBUTES, expectedValid = true)
        validateInsurance(INSURANCE_ATTRIBUTES.dropLast(1) + ",\"cardConfig\":{}}", expectedValid = false)
        validateInsurance(
            """{"coverage":{"amount":"100000.00","currency":"EUR"},"termYears":20,"premiumModel":"FIXED"}""",
            expectedValid = false,
        )
    }

    @Test
    fun publishesWithFourEyesAtomicallyAndExactly() {
        val specificationId = createSpecification("INS_TERM_LIFE_E2E")
        val offeringId = createOffering(specificationId, "INS_TERM_LIFE_E2E_CZ")
        val targetOfferingId = createOffering(specificationId, "INS_TERM_LIFE_TARGET_CZ")
        val revisionId = createRevision(offeringId, "Reference term life")
        insertDraftRelationship(revisionId, targetOfferingId)
        val relocationDraftId = createRevision(targetOfferingId, "Relocation target")
        val publishPath = "/api/v2/offerings/$offeringId/revisions/$revisionId/publish"

        Given {
            contentType("application/json")
            body("""{"reason":"self approval"}""")
            header("If-Match", "\"0\"")
        } When {
            post(publishPath)
        } Then {
            statusCode(403)
            body("code", equalTo("FOUR_EYES_REQUIRED"))
        }

        setMaker(revisionId, "independent-maker")
        assertPublishPreconditions(publishPath)
        val beforePublication = Instant.now().minusSeconds(1)
        publishAndAssertProjection(publishPath, offeringId, beforePublication)

        Given {
            contentType("application/json")
            body(revisionPayload("Changed after publication"))
            header("If-Match", "\"1\"")
        } When {
            put("/api/v2/offerings/$offeringId/revisions/$revisionId")
        } Then {
            statusCode(409)
            body("code", equalTo("CATALOG_CONFLICT"))
        }

        assertThat(decimalPrice(revisionId)).isEqualByComparingTo(BigDecimal(EXACT_PRICE))
        assertThat(rowCount("catalog_approvals", revisionId)).isEqualTo(1)
        assertThat(rowCount("catalog_audit", revisionId)).isEqualTo(2)
        assertThat(rowCount("catalog_outbox", revisionId)).isEqualTo(2)
        assertThat(validEventEnvelopeCount(revisionId)).isEqualTo(2)
        assertEventCursorAdvancesWithoutDuplicates()
        assertPublishedSnapshotIsDatabaseImmutable(revisionId, targetOfferingId, relocationDraftId)
    }

    @Test
    fun rejectsOversizedDeepAndOutOfRangeRevisionInputsBeforePersistence() {
        val specificationId = createSpecification("INS_TERM_LIFE_LIMITS")
        val offeringId = createOffering(specificationId, "INS_TERM_LIFE_LIMITS_CZ")
        val endpoint = "/api/v2/offerings/$offeringId/revisions"

        val deep = mapper.readTree(revisionPayload("Deep input")) as ObjectNode
        var nested = deep.with("attributes")
        repeat(CatalogSchemaProfile.MAX_NESTING_DEPTH + 1) { nested = nested.putObject("level") }
        given().contentType("application/json").body(mapper.writeValueAsString(deep)).post(endpoint).then()
            .statusCode(400)

        val oversized = mapper.readTree(revisionPayload("Large input")) as ObjectNode
        oversized.with("attributes").put("blob", "x".repeat(CatalogSchemaProfile.MAX_INSTANCE_BYTES + 1))
        given().contentType("application/json").body(mapper.writeValueAsString(oversized)).post(endpoint).then()
            .statusCode(400)

        val excessiveDecimal = mapper.readTree(revisionPayload("Decimal overflow")) as ObjectNode
        (excessiveDecimal.withArray("prices")[0] as ObjectNode).put("value", "100000000000000000000.00")
        given().contentType("application/json").body(mapper.writeValueAsString(excessiveDecimal)).post(endpoint).then()
            .statusCode(400)

        assertThat(aggregateCount("catalog_revisions", "offering_id", offeringId)).isZero()
    }

    @Test
    fun advancesSchemaVersionOnlyOnANewPinnedRevision() {
        installInsuranceSchemaVersion(2)
        val specificationId = createSpecification("INS_TERM_LIFE_SCHEMA_ADVANCE")
        val offeringId = createOffering(specificationId, "INS_TERM_LIFE_SCHEMA_ADVANCE_CZ")
        val first = createRevision(offeringId, "Schema version one")
        val second = createRevision(offeringId, "Schema version two", schemaVersion = 2)

        val mismatchedUpdate = mapper.readTree(revisionPayload("Wrong in-place schema", schemaVersion = 2))
        given().contentType("application/json").header("If-Match", "\"0\"")
            .body(mapper.writeValueAsString(mismatchedUpdate))
            .put("/api/v2/offerings/$offeringId/revisions/$first").then()
            .statusCode(400)

        given().get("/api/v2/offerings/$offeringId/revisions/$first").then()
            .statusCode(200)
            .body("schemaRef.version", equalTo(1))
        given().get("/api/v2/offerings/$offeringId/revisions/$second").then()
            .statusCode(200)
            .body("schemaRef.version", equalTo(2))
    }

    @Test
    fun listsProductStudioAggregatesWithoutCrossOfferingRevisionLeakage() {
        val specificationId = createSpecification("INS_PRODUCT_STUDIO_LIST")
        val firstOfferingId = createOffering(specificationId, "INS_PRODUCT_STUDIO_LIST_CZ")
        val secondOfferingId = createOffering(specificationId, "INS_PRODUCT_STUDIO_LIST_DE")
        val firstRevisionId = createRevision(firstOfferingId, "Czech draft")
        createRevision(secondOfferingId, "German draft")

        val specificationIds = given().get("/api/v2/specifications").then().statusCode(200)
            .extract().jsonPath().getList("id", String::class.java)
        val offeringIds = given().get("/api/v2/offerings").then().statusCode(200)
            .extract().jsonPath().getList("id", String::class.java)
        val revisionIds = given().get("/api/v2/offerings/$firstOfferingId/revisions").then()
            .statusCode(200).extract().jsonPath().getList("id", String::class.java)

        assertThat(specificationIds).contains(specificationId.toString())
        assertThat(offeringIds).contains(firstOfferingId.toString(), secondOfferingId.toString())
        assertThat(revisionIds).containsExactly(firstRevisionId.toString())
    }

    @Test
    fun acceptsOutboxWritesFromThePreviousV2BinaryDuringRollingDeployment() {
        val eventId = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO catalog_outbox " +
                    "(id, aggregate_type, aggregate_id, event_type, schema_version, occurred_at, payload) " +
                    "VALUES (?, 'REVISION', ?, 'test.previous_v2_writer', 1, now(), CAST(? AS jsonb))",
            ).use { statement ->
                statement.setObject(1, eventId)
                statement.setObject(2, UUID.randomUUID())
                statement.setString(3, "{\"source\":\"previous-v2-binary\"}")
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
        assertThat(previousWriterEventCount(eventId)).isEqualTo(1)
    }

    @Test
    fun serializesDraftChildInsertionBeforeConcurrentPublication() {
        val specificationId = createSpecification("INS_CHILD_PUBLISH_SERIALIZATION")
        val offeringId = createOffering(specificationId, "INS_CHILD_PUBLISH_SERIALIZATION_CZ")
        val revisionId = createRevision(offeringId, "Concurrent child lock")
        val childInserted = CountDownLatch(1)
        val releaseChildTransaction = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val childCommit = executor.submit(
                Callable {
                    dataSource.connection.use { connection ->
                        connection.autoCommit = false
                        connection.prepareStatement(
                            "INSERT INTO catalog_price_components " +
                                "(id, revision_id, code, kind, value, unit, cadence, tax_treatment) " +
                                "VALUES (?, ?, 'CONCURRENT', 'RATE', 1, 'annual-rate', 'ANNUALLY', 'UNSPECIFIED')",
                        ).use { statement ->
                            statement.setObject(1, UUID.randomUUID())
                            statement.setObject(2, revisionId)
                            assertThat(statement.executeUpdate()).isEqualTo(1)
                        }
                        childInserted.countDown()
                        check(releaseChildTransaction.await(5, TimeUnit.SECONDS))
                        connection.commit()
                    }
                    true
                },
            )
            check(childInserted.await(5, TimeUnit.SECONDS))
            val publication = executor.submit(
                Callable {
                    dataSource.connection.use { connection ->
                        connection.prepareStatement(
                            "UPDATE catalog_revisions SET state = 'PUBLISHED', effective_from = now(), " +
                                "checker_id = 'concurrent-checker', reason = 'concurrency proof', " +
                                "content_hash = repeat('c', 64) WHERE id = ?",
                        ).use { statement ->
                            statement.setObject(1, revisionId)
                            statement.executeUpdate()
                        }
                    }
                },
            )

            assertThatThrownBy { publication.get(250, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)
            releaseChildTransaction.countDown()
            assertThat(childCommit.get(5, TimeUnit.SECONDS)).isTrue()
            assertThat(publication.get(5, TimeUnit.SECONDS)).isEqualTo(1)
        } finally {
            releaseChildTransaction.countDown()
            executor.shutdownNow()
        }
    }

    private fun publishAndAssertProjection(publishPath: String, offeringId: UUID, beforePublication: Instant) {
        Given {
            contentType("application/json")
            body("""{"reason":"approved product launch"}""")
            header("If-Match", "\"0\"")
        } When {
            post(publishPath)
        } Then {
            statusCode(200)
            header("ETag", equalTo("\"1\""))
            body("state", equalTo("PUBLISHED"))
            body("checkerId", equalTo("test-operator"))
        }

        Given { this } When {
            get("/api/v2/products/$offeringId?effectiveAt=$beforePublication")
        } Then {
            statusCode(404)
        }

        Given { this } When {
            get("/api/v2/products/$offeringId")
        } Then {
            statusCode(200)
            body("state", equalTo("PUBLISHED"))
            body("content.attributes.currency", nullValue())
            body("content.attributes.coverage.currency", equalTo("EUR"))
            body("content.attributes.premium.amount", equalTo("12.3400"))
            body("content.prices[0].value", equalTo(EXACT_PRICE))
        }
    }

    @Test
    fun onlyOneConcurrentDraftUpdateWins() {
        val specificationId = createSpecification("INS_TERM_LIFE_RACE")
        val offeringId = createOffering(specificationId, "INS_TERM_LIFE_RACE_CZ")
        val revisionId = createRevision(offeringId, "Before concurrent update")
        val path = "/api/v2/offerings/$offeringId/revisions/$revisionId"
        val auditsBefore = actionCount("catalog_audit", "REVISION_UPDATED")
        val eventsBefore = eventCount("com.openbank.catalog.revision_updated")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val results = try {
            executor.invokeAll(
                listOf("Concurrent A", "Concurrent B").map { name ->
                    Callable {
                        check(start.await(5, TimeUnit.SECONDS))
                        given()
                            .contentType("application/json")
                            .body(revisionPayload(name))
                            .header("If-Match", "\"0\"")
                            .put(path)
                            .statusCode
                    }
                }.also { start.countDown() },
            ).map { it.get(15, TimeUnit.SECONDS) }.sorted()
        } finally {
            executor.shutdownNow()
        }

        assertThat(results).containsExactly(200, 412)
        assertThat(actionCount("catalog_audit", "REVISION_UPDATED") - auditsBefore).isEqualTo(1)
        assertThat(eventCount("com.openbank.catalog.revision_updated") - eventsBefore).isEqualTo(1)
    }

    @Test
    fun lastContentEditorBecomesMakerAndCannotSelfPublish() {
        val specificationId = createSpecification("INS_TERM_LIFE_EDITOR")
        val offeringId = createOffering(specificationId, "INS_TERM_LIFE_EDITOR_CZ")
        val revisionId = createRevision(offeringId, "Initial author")
        setMaker(revisionId, "original-maker")

        Given {
            contentType("application/json")
            body(revisionPayload("Changed by test operator"))
            header("If-Match", "\"0\"")
        } When {
            put("/api/v2/offerings/$offeringId/revisions/$revisionId")
        } Then {
            statusCode(200)
            body("makerId", equalTo("test-operator"))
        }

        Given {
            contentType("application/json")
            body("""{"reason":"attempt to approve own edit"}""")
            header("If-Match", "\"1\"")
        } When {
            post("/api/v2/offerings/$offeringId/revisions/$revisionId/publish")
        } Then {
            statusCode(403)
            body("code", equalTo("FOUR_EYES_REQUIRED"))
        }
    }

    @Test
    fun rollsBackDomainAuditAndOutboxTogether() {
        val specificationId = createSpecification("INS_TERM_LIFE_ROLLBACK")
        val offeringId = createOffering(specificationId, "INS_TERM_LIFE_ROLLBACK_CZ")
        val draftsBefore = aggregateCount("catalog_revisions", "offering_id", offeringId)
        val auditBefore = actionCount("catalog_audit", "REVISION_DRAFTED")
        val outboxBefore = eventCount("com.openbank.catalog.revision_drafted")

        installFailingOutboxTrigger()
        try {
            Given {
                contentType("application/json")
                body(revisionPayload("Must roll back"))
            } When {
                post("/api/v2/offerings/$offeringId/revisions")
            } Then {
                statusCode(500)
            }
        } finally {
            removeFailingOutboxTrigger()
        }

        assertThat(aggregateCount("catalog_revisions", "offering_id", offeringId)).isEqualTo(draftsBefore)
        assertThat(actionCount("catalog_audit", "REVISION_DRAFTED")).isEqualTo(auditBefore)
        assertThat(eventCount("com.openbank.catalog.revision_drafted")).isEqualTo(outboxBefore)
    }

    private fun assertPublishPreconditions(publishPath: String) {
        Given {
            contentType("application/json")
            body("""{"reason":"approved product launch"}""")
        } When {
            post(publishPath)
        } Then {
            statusCode(428)
            body("code", equalTo("PRECONDITION_REQUIRED"))
        }

        Given {
            contentType("application/json")
            body("""{"reason":"approved product launch"}""")
            header("If-Match", "\"9\"")
        } When {
            post(publishPath)
        } Then {
            statusCode(412)
            body("code", equalTo("PRECONDITION_FAILED"))
        }
    }

    private fun validateInsurance(attributes: String, expectedValid: Boolean) {
        Given {
            contentType("application/json")
            body("""{"attributes":$attributes}""")
        } When {
            post("/api/v2/product-types/org.openbank.insurance.term-life/versions/1/validate")
        } Then {
            statusCode(200)
            body("valid", equalTo(expectedValid))
        }
    }

    private fun createSpecification(code: String): UUID = UUID.fromString(
        (
            Given {
                contentType("application/json")
                body("""{"code":"$code","schemaRef":{"id":"org.openbank.insurance.term-life","version":1}}""")
            } When {
                post("/api/v2/specifications")
            } Then {
                statusCode(201)
                header("ETag", equalTo("\"0\""))
            }
            ).extract().jsonPath().getString("id"),
    )

    private fun createOffering(specificationId: UUID, code: String): UUID = UUID.fromString(
        (
            Given {
                contentType("application/json")
                body(
                    """{"specificationId":"$specificationId","code":"$code","market":""" +
                        """{"countries":["CZ"],"channels":["WEB"],"locales":["cs-CZ"]}}""",
                )
            } When {
                post("/api/v2/offerings")
            } Then {
                statusCode(201)
                header("ETag", equalTo("\"0\""))
            }
            ).extract().jsonPath().getString("id"),
    )

    private fun createRevision(offeringId: UUID, name: String, schemaVersion: Int = 1): UUID = UUID.fromString(
        (
            Given {
                contentType("application/json")
                body(revisionPayload(name, schemaVersion))
            } When {
                post("/api/v2/offerings/$offeringId/revisions")
            } Then {
                statusCode(201)
                header("ETag", equalTo("\"0\""))
                body("state", equalTo("DRAFT"))
            }
            ).extract().jsonPath().getString("id"),
    )

    private fun revisionPayload(name: String, schemaVersion: Int = 1): String =
        """{"schemaRef":{"id":"org.openbank.insurance.term-life","version":$schemaVersion},""" +
            """"name":{"en":"$name"},"attributes":$INSURANCE_ATTRIBUTES,"prices":[{""" +
            """"code":"PREMIUM","kind":"AMOUNT","value":"$EXACT_PRICE","currency":"EUR","unit":"policy",""" +
            """"cadence":"MONTHLY","taxTreatment":"EXEMPT"}]}"""

    private fun setMaker(revisionId: UUID, maker: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE catalog_revisions SET maker_id = ? WHERE id = ?").use { statement ->
                statement.setString(1, maker)
                statement.setObject(2, revisionId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private fun decimalPrice(revisionId: UUID): BigDecimal = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT value FROM catalog_price_components WHERE revision_id = ? AND code = 'PREMIUM'",
        ).use { statement ->
            statement.setObject(1, revisionId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getBigDecimal(1)
            }
        }
    }

    private fun insertDraftRelationship(revisionId: UUID, targetOfferingId: UUID) {
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

    private fun assertPublishedSnapshotIsDatabaseImmutable(
        revisionId: UUID,
        targetOfferingId: UUID,
        relocationDraftId: UUID,
    ) {
        assertThatThrownBy {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE catalog_revisions SET content = to_jsonb(CAST(? AS text)) WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, "{\"tampered\":true}")
                    statement.setObject(2, revisionId)
                    statement.executeUpdate()
                }
            }
        }.hasMessageContaining("is immutable")

        assertImmutableChild(
            "INSERT INTO catalog_price_components " +
                "(id, revision_id, code, kind, value, unit, cadence, tax_treatment) " +
                "VALUES (?, ?, 'LATE_PRICE', 'RATE', 1, 'annual-rate', 'ANNUALLY', 'UNSPECIFIED')",
            revisionId,
        )
        assertImmutableChild(
            "UPDATE catalog_price_components SET value = 2 WHERE revision_id = ? AND code = 'PREMIUM'",
            revisionId,
            idFirst = true,
        )
        assertImmutableChild(
            "DELETE FROM catalog_price_components WHERE revision_id = ? AND code = 'PREMIUM'",
            revisionId,
            idFirst = true,
        )
        assertImmutableRelocation("catalog_price_components", revisionId, relocationDraftId)
        assertImmutableRelationshipInsert(revisionId, targetOfferingId)
        assertImmutableChild(
            "UPDATE catalog_relationships SET kind = 'REPLACEMENT' WHERE revision_id = ?",
            revisionId,
            idFirst = true,
        )
        assertImmutableChild(
            "DELETE FROM catalog_relationships WHERE revision_id = ?",
            revisionId,
            idFirst = true,
        )
        assertImmutableRelocation("catalog_relationships", revisionId, relocationDraftId)
    }

    private fun assertImmutableRelocation(table: String, revisionId: UUID, draftRevisionId: UUID) {
        assertThatThrownBy {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE $table SET revision_id = ? WHERE revision_id = ?",
                ).use { statement ->
                    statement.setObject(1, draftRevisionId)
                    statement.setObject(2, revisionId)
                    statement.executeUpdate()
                }
            }
        }.hasMessageContaining("child is immutable")
    }

    private fun assertImmutableChild(sql: String, revisionId: UUID, idFirst: Boolean = false) {
        assertThatThrownBy {
            dataSource.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    if (idFirst) {
                        statement.setObject(1, revisionId)
                    } else {
                        statement.setObject(1, UUID.randomUUID())
                        statement.setObject(2, revisionId)
                    }
                    statement.executeUpdate()
                }
            }
        }.hasMessageContaining("child is immutable")
    }

    private fun assertImmutableRelationshipInsert(revisionId: UUID, targetOfferingId: UUID) {
        assertThatThrownBy {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO catalog_relationships (id, revision_id, target_offering_id, kind) " +
                        "VALUES (?, ?, ?, 'REPLACEMENT')",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, revisionId)
                    statement.setObject(3, targetOfferingId)
                    statement.executeUpdate()
                }
            }
        }.hasMessageContaining("child is immutable")
    }

    private fun assertEventCursorAdvancesWithoutDuplicates() {
        val firstPage = given().queryParam("limit", 1).get("/api/v2/events").then()
            .statusCode(200)
            .extract()
        val firstId = firstPage.jsonPath().getString("items[0].id")
        val cursor = firstPage.jsonPath().getString("nextCursor")
        assertThat(cursor).isNotBlank()

        val followingIds = given().queryParam("after", cursor).queryParam("limit", 500)
            .get("/api/v2/events").then().statusCode(200)
            .extract().jsonPath().getList("items.id", String::class.java)
        assertThat(followingIds).doesNotContain(firstId)
        given().queryParam("after", "not-a-cursor").get("/api/v2/events").then().statusCode(400)
    }

    private fun installInsuranceSchemaVersion(version: Int) {
        dataSource.connection.use { connection ->
            val document = connection.prepareStatement(
                "SELECT document FROM catalog_schemas " +
                    "WHERE schema_id = 'org.openbank.insurance.term-life' AND schema_version = 1",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    (mapper.readTree(rows.getString(1)) as ObjectNode).also {
                        it.put("\$id", "urn:catalog-schema:org.openbank.insurance.term-life:$version")
                    }
                }
            }
            connection.prepareStatement(
                "INSERT INTO catalog_schemas " +
                    "(key, schema_id, schema_version, document, sha256, registered_at) " +
                    "VALUES (?, 'org.openbank.insurance.term-life', ?, CAST(? AS jsonb), ?, now()) " +
                    "ON CONFLICT DO NOTHING",
            ).use { statement ->
                statement.setString(1, "org.openbank.insurance.term-life:$version")
                statement.setInt(2, version)
                statement.setString(3, mapper.writeValueAsString(document))
                statement.setString(4, catalogJson.sha256(document))
                statement.executeUpdate()
            }
        }
    }

    private fun installFailingOutboxTrigger() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """CREATE FUNCTION fail_test_catalog_outbox() RETURNS TRIGGER AS 'BEGIN """ +
                        """RAISE EXCEPTION ''forced outbox failure''; END;' LANGUAGE plpgsql""",
                )
                statement.execute(
                    """CREATE TRIGGER trg_fail_test_catalog_outbox BEFORE INSERT ON catalog_outbox """ +
                        """FOR EACH ROW WHEN (NEW.event_type = 'com.openbank.catalog.revision_drafted') """ +
                        """EXECUTE FUNCTION fail_test_catalog_outbox()""",
                )
            }
        }
    }

    private fun removeFailingOutboxTrigger() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TRIGGER IF EXISTS trg_fail_test_catalog_outbox ON catalog_outbox")
                statement.execute("DROP FUNCTION IF EXISTS fail_test_catalog_outbox()")
            }
        }
    }

    private fun aggregateCount(table: String, idColumn: String, id: UUID): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE $idColumn = ?").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getLong(1)
                }
            }
        }

    private fun actionCount(table: String, action: String): Long = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE action = ?").use { statement ->
            statement.setString(1, action)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private fun eventCount(eventType: String): Long = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM catalog_outbox WHERE event_type = ?").use { statement ->
            statement.setString(1, eventType)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private fun previousWriterEventCount(eventId: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM catalog_outbox " +
                "WHERE id = ? AND headers = '{}'::jsonb AND created_at IS NOT NULL",
        ).use { statement ->
            statement.setObject(1, eventId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private fun rowCount(table: String, aggregateId: UUID): Long = dataSource.connection.use { connection ->
        val idColumn = if (table == "catalog_approvals") "revision_id" else "aggregate_id"
        connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE $idColumn = ?").use { statement ->
            statement.setObject(1, aggregateId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private fun validEventEnvelopeCount(aggregateId: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT COUNT(*) FROM catalog_outbox WHERE aggregate_id = ? """ +
                """AND payload->>'eventId' = CAST(id AS text) """ +
                """AND payload->>'aggregateType' = aggregate_type """ +
                """AND payload->>'aggregateId' = CAST(aggregate_id AS text) """ +
                """AND payload->>'eventType' = event_type """ +
                """AND CAST(payload->>'schemaVersion' AS integer) = schema_version """ +
                """AND payload->>'occurredAt' IS NOT NULL AND payload->>'actorId' = 'test-operator'""",
        ).use { statement ->
            statement.setObject(1, aggregateId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private companion object {
        const val EXACT_PRICE = "10000000000000000000.10"
        const val INSURANCE_ATTRIBUTES =
            """{"coverage":{"amount":"100000.00","currency":"EUR"},"termYears":20,"smokerAccepted":true,"premiumModel":"FIXED","premium":{"amount":"12.3400","currency":"EUR","cadence":"MONTHLY"}}"""
    }
}
