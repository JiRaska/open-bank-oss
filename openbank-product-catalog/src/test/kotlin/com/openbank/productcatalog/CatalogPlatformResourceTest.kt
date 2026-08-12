// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
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
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
class CatalogPlatformResourceTest {
    @Inject
    lateinit var dataSource: DataSource

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
        val revisionId = createRevision(offeringId, "Reference term life")
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
            get("/api/v2/products/$specificationId")
        } Then {
            statusCode(200)
            body("state", equalTo("PUBLISHED"))
            body("content.attributes.currency", nullValue())
            body("content.attributes.coverage.currency", equalTo("EUR"))
            body("content.attributes.premium.amount", equalTo("12.3400"))
        }

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
        assertPublishedSnapshotIsDatabaseImmutable(revisionId)
    }

    @Test
    fun onlyOneConcurrentDraftUpdateWins() {
        val specificationId = createSpecification("INS_TERM_LIFE_RACE")
        val offeringId = createOffering(specificationId, "INS_TERM_LIFE_RACE_CZ")
        val revisionId = createRevision(offeringId, "Before concurrent update")
        val path = "/api/v2/offerings/$offeringId/revisions/$revisionId"
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
        assertThat(actionCount("catalog_audit", "REVISION_UPDATED")).isEqualTo(1)
        assertThat(eventCount("com.openbank.catalog.revision_updated")).isEqualTo(1)
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

    private fun createRevision(offeringId: UUID, name: String): UUID = UUID.fromString(
        (
            Given {
                contentType("application/json")
                body(revisionPayload(name))
            } When {
                post("/api/v2/offerings/$offeringId/revisions")
            } Then {
                statusCode(201)
                header("ETag", equalTo("\"0\""))
                body("state", equalTo("DRAFT"))
            }
            ).extract().jsonPath().getString("id"),
    )

    private fun revisionPayload(name: String): String =
        """{"name":{"en":"$name"},"attributes":$INSURANCE_ATTRIBUTES,"prices":[{""" +
            """"code":"PREMIUM","kind":"AMOUNT","value":$EXACT_PRICE,"currency":"EUR","unit":"policy",""" +
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

    private fun assertPublishedSnapshotIsDatabaseImmutable(revisionId: UUID) {
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
