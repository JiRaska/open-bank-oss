// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * ADR-0334 slice S6a: the `retirement` pack registers `org.openbank.retirement.pension-savings`
 * through the same trusted-pack seeder as banking/insurance (ADR-0257), and the CZ DPS / DIP
 * specifications carry one offering per fund strategy through the real `/api/v2` surface.
 *
 * Offering values come from `retirement-pack/cz-illustrative-offerings.json` and are placeholders
 * that need legal and commercial review; the schema forces every instance to say so via
 * `reviewStatus`.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestSecurity(user = "retirement-pack-operator", roles = ["ROLE_OPERATOR"])
class RetirementCatalogPackResourceTest {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var mapper: ObjectMapper

    @Test
    fun `retirement pension-savings v1 is registered as a trusted product type`() {
        Given { this } When {
            get("/api/v2/product-types")
        } Then {
            statusCode(200)
            body("id", hasItem(SCHEMA_ID))
        }
    }

    @Test
    fun `retirement v2 requires selected unique questionnaire instrument classes while v1 remains valid`() {
        val attributes = fixtureOfferings().first { it["attributes"]["productLine"].asText() == "DIP" }["attributes"]
        validate(attributes.toString(), expectedValid = true, version = 1)
        validate(attributes.toString(), expectedValid = false, version = 2)
        validate(attributes.with { putArray("instrumentClasses") }, expectedValid = false, version = 2)
        validate(
            attributes.with { putArray("instrumentClasses").add("BOND_FUNDS").add("BOND_FUNDS") },
            expectedValid = false,
            version = 2,
        )
        validate(attributes.with { putArray("instrumentClasses").add("UNKNOWN") }, expectedValid = false, version = 2)
        validate(
            attributes.with { putArray("instrumentClasses").add("BOND_FUNDS").add("EQUITY_FUNDS") },
            expectedValid = true,
            version = 2,
        )
    }

    @Test
    fun `v2 represents every current DPS and DIP onboarding strategy`() {
        val byLine = fixtureOfferings().associateBy { it["attributes"]["productLine"].asText() }
        mapOf(
            "DPS" to listOf("CONSERVATIVE", "BALANCED", "SUSTAINABLE_BALANCED", "DYNAMIC", "LIFECYCLE"),
            "DIP" to listOf(
                "CONSERVATIVE",
                "BALANCED",
                "SUSTAINABLE_BALANCED",
                "DYNAMIC",
                "EQUITY_GLOBAL",
                "LIFECYCLE",
            ),
        ).forEach { (line, strategies) ->
            val base = requireNotNull(byLine[line])["attributes"]
            strategies.forEach { strategy ->
                validate(
                    base.with {
                        put("fundStrategy", strategy)
                        putArray("instrumentClasses").add(if (line == "DPS") "PENSION_FUNDS" else "BOND_FUNDS")
                    },
                    expectedValid = true,
                    version = 2,
                )
            }
        }
    }

    @Test
    fun `every illustrative CZ DPS and DIP offering validates against the pack`() {
        val offerings = fixtureOfferings()
        assertThat(
            offerings.map {
                it["attributes"]["productLine"].asText() to it["attributes"]["fundStrategy"].asText()
            },
        )
            .containsExactlyInAnyOrderElementsOf(
                listOf("DPS", "DIP").flatMap { line -> STRATEGIES.map { line to it } },
            )
        offerings.forEach { offering ->
            assertThat(offering["attributes"]["reviewStatus"].asText())
                .isEqualTo("ILLUSTRATIVE_REQUIRES_LEGAL_AND_COMMERCIAL_REVIEW")
            validate(offering["attributes"].toString(), expectedValid = true)
        }
    }

    @Test
    fun `DPS and DIP specifications carry one offering per strategy and publish`() {
        val byLine = fixtureOfferings().groupBy { it["attributes"]["productLine"].asText() }
        byLine.forEach { (line, offerings) ->
            val specificationId = createSpecification("CZ_${line}_PACK_E2E")
            val offeringIds = offerings.map { offering ->
                val offeringId = createOffering(specificationId, offering["offeringCode"].asText() + "_E2E")
                offeringId to createRevision(offeringId, offering)
            }
            assertThat(offeringIds).hasSize(STRATEGIES.size)

            val (offeringId, revisionId) = offeringIds.first()
            setMaker(revisionId, "independent-retirement-maker")
            Given {
                contentType("application/json")
                body("""{"reason":"illustrative retirement pack round trip"}""")
                header("If-Match", "\"0\"")
            } When {
                post("/api/v2/offerings/$offeringId/revisions/$revisionId/publish")
            } Then {
                statusCode(200)
                body("state", equalTo("PUBLISHED"))
            }
            Given { this } When {
                get("/api/v2/products/$offeringId")
            } Then {
                statusCode(200)
                body("content.attributes.productLine", equalTo(line))
                body("content.attributes.jurisdictionPackId", equalTo("CZ/$line"))
                body("content.attributes.reviewStatus", equalTo("ILLUSTRATIVE_REQUIRES_LEGAL_AND_COMMERCIAL_REVIEW"))
            }
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `v2 pension revision needs distinct legal and product evidence before publication`() {
        val offering = fixtureOfferings().first { it["attributes"]["productLine"].asText() == "DIP" }
        val specificationId = createSpecification("CZ_DIP_CLASSES_V2", version = 2)
        val offeringId = createOffering(specificationId, "CZ_DIP_CLASSES_V2")
        val attributes = (offering["attributes"].deepCopy<JsonNode>() as ObjectNode).apply {
            putArray("instrumentClasses").add("BOND_FUNDS").add("EQUITY_FUNDS")
            put("reviewStatus", "LEGAL_AND_COMMERCIAL_REVIEWED")
        }
        val revisionId = createRevision(
            offeringId,
            offering,
            version = 2,
            attributes = attributes,
            effectiveFrom = "2027-01-01T00:00:00Z",
        )
        setMaker(revisionId, "independent-retirement-maker")
        val publishPath = "/api/v2/offerings/$offeringId/revisions/$revisionId/publish"
        Given {
            contentType("application/json")
            body("""{"reason":"reviewed instrument class coverage"}""")
            header("If-Match", "\"0\"")
        } When { post(publishPath) } Then { statusCode(409) }
        insertPensionApproval(revisionId, "LEGAL_COUNSEL", "legal-subject")
        Given {
            contentType("application/json")
            body("""{"reason":"reviewed instrument class coverage"}""")
            header("If-Match", "\"0\"")
        } When { post(publishPath) } Then { statusCode(409) }
        insertPensionApproval(revisionId, "PRODUCT_OWNER", "product-subject")
        Given {
            contentType("application/json")
            body("""{"reason":"reviewed instrument class coverage"}""")
            header("If-Match", "\"0\"")
        } When {
            post(publishPath)
        } Then {
            statusCode(200)
            body("state", equalTo("PUBLISHED"))
        }
        Given { this } When {
            get("/api/v2/products/$offeringId?effectiveAt=2027-01-02T00:00:00Z")
        } Then {
            statusCode(200)
            body("content.attributes.instrumentClasses", equalTo(listOf("BOND_FUNDS", "EQUITY_FUNDS")))
        }

        val staleOffering = createOffering(specificationId, "CZ_DIP_CLASSES_STALE_V2")
        val staleRevision = createRevision(
            staleOffering,
            offering,
            version = 2,
            attributes = attributes,
            effectiveFrom = "2027-02-01T00:00:00Z",
        )
        setMaker(staleRevision, "independent-retirement-maker")
        insertPensionApproval(staleRevision, "LEGAL_COUNSEL", "legal-subject")
        insertPensionApproval(staleRevision, "PRODUCT_OWNER", "product-subject")
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE catalog_revisions SET effective_to = ?, lock_version = lock_version + 1 WHERE id = ?",
            ).use { statement ->
                statement.setTimestamp(1, java.sql.Timestamp.from(Instant.parse("2027-03-01T00:00:00Z")))
                statement.setObject(2, staleRevision)
                statement.executeUpdate()
            }
        }
        Given {
            contentType("application/json")
            body("""{"reason":"changed effective interval"}""")
            header("If-Match", "\"1\"")
        } When { post("/api/v2/offerings/$staleOffering/revisions/$staleRevision/publish") } Then {
            statusCode(409)
        }

        val sameActorOffering = createOffering(specificationId, "CZ_DIP_CLASSES_SAME_ACTOR_V2")
        val sameActorRevision = createRevision(
            sameActorOffering,
            offering,
            version = 2,
            attributes = attributes,
            effectiveFrom = "2027-04-01T00:00:00Z",
        )
        setMaker(sameActorRevision, "independent-retirement-maker")
        insertPensionApproval(sameActorRevision, "LEGAL_COUNSEL", "shared-subject")
        insertPensionApproval(sameActorRevision, "PRODUCT_OWNER", "shared-subject")
        Given {
            contentType("application/json")
            body("""{"reason":"same actor must not suffice"}""")
            header("If-Match", "\"0\"")
        } When { post("/api/v2/offerings/$sameActorOffering/revisions/$sameActorRevision/publish") } Then {
            statusCode(409)
        }
    }

    @Test
    fun `inconsistent or out-of-range retirement attributes are rejected`() {
        val base = fixtureOfferings().first { it["attributes"]["productLine"].asText() == "DPS" }["attributes"]
        validate(base.toString(), expectedValid = true)
        validate(base.with { put("jurisdictionPackId", "CZ/DIP") }, expectedValid = false)
        validate(base.with { putArray("permittedProviderTypes").add("BANK") }, expectedValid = false)
        validate(base.with { put("riskClass", 8) }, expectedValid = false)
        validate(base.with { put("riskClass", 0) }, expectedValid = false)
        validate(base.with { put("fundStrategy", "AGGRESSIVE") }, expectedValid = false)
        validate(base.with { remove("reviewStatus") }, expectedValid = false)
        validate(base.with { remove("feeSchedule") }, expectedValid = false)
        validate(base.with { put("notInSchema", "x") }, expectedValid = false)
        validate(
            base.with { (get("sustainability") as ObjectNode).put("sfdrArticle", "ARTICLE_10") },
            expectedValid = false,
        )
    }

    // --- fixtures -----------------------------------------------------------------------------

    private fun fixtureOfferings(): List<JsonNode> =
        requireNotNull(javaClass.getResourceAsStream("/retirement-pack/cz-illustrative-offerings.json"))
            .use(mapper::readTree)["offerings"].toList()

    private fun JsonNode.with(change: ObjectNode.() -> Unit): String =
        (deepCopy<JsonNode>() as ObjectNode).apply(change).toString()

    private fun validate(attributes: String, expectedValid: Boolean, version: Int = 1) {
        Given {
            contentType("application/json")
            body("""{"attributes":$attributes}""")
        } When {
            post("/api/v2/product-types/$SCHEMA_ID/versions/$version/validate")
        } Then {
            statusCode(200)
            body("valid", equalTo(expectedValid))
        }
    }

    private fun createSpecification(code: String, version: Int = 1): UUID = UUID.fromString(
        Given {
            contentType("application/json")
            body("""{"code":"$code","schemaRef":{"id":"$SCHEMA_ID","version":$version}}""")
        } When {
            post("/api/v2/specifications")
        } Then {
            statusCode(201)
        } Extract {
            path<String>("id")
        },
    )

    private fun createOffering(specificationId: UUID, code: String): UUID = UUID.fromString(
        Given {
            contentType("application/json")
            body("""{"specificationId":"$specificationId","code":"$code","market":{"countries":["CZ"]}}""")
        } When {
            post("/api/v2/offerings")
        } Then {
            statusCode(201)
        } Extract {
            path<String>("id")
        },
    )

    private fun createRevision(
        offeringId: UUID,
        offering: JsonNode,
        version: Int = 1,
        attributes: JsonNode = offering["attributes"],
        effectiveFrom: String? = null,
    ): UUID = UUID.fromString(
        Given {
            contentType("application/json")
            body(
                mapper.writeValueAsString(
                    mapOf(
                        "schemaRef" to mapOf("id" to SCHEMA_ID, "version" to version),
                        "name" to mapOf("en" to offering["name"].asText()),
                        "attributes" to attributes,
                    ) + (effectiveFrom?.let { mapOf("effectiveFrom" to it) } ?: emptyMap()),
                ),
            )
        } When {
            post("/api/v2/offerings/$offeringId/revisions")
        } Then {
            statusCode(201)
            body("state", equalTo("DRAFT"))
        } Extract {
            path<String>("id")
        },
    )

    private fun setMaker(revisionId: UUID, maker: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE catalog_revisions SET maker_id = ? WHERE id = ?").use { statement ->
                statement.setString(1, maker)
                statement.setObject(2, revisionId)
                statement.executeUpdate()
            }
        }
    }

    @Suppress("NestedBlockDepth")
    private fun insertPensionApproval(revisionId: UUID, role: String, subject: String) {
        dataSource.connection.use { connection ->
            val revision = connection.prepareStatement(
                "SELECT offering_id, schema_id, schema_version, effective_from, effective_to, " +
                    "content FROM catalog_revisions WHERE id = ?",
            ).use { statement ->
                statement.setObject(1, revisionId)
                statement.executeQuery().use { result ->
                    check(result.next())
                    mapper.createObjectNode().apply {
                        put("offeringId", result.getObject("offering_id").toString())
                        put("revisionId", revisionId.toString())
                        put("schemaId", result.getString("schema_id"))
                        put("schemaVersion", result.getInt("schema_version"))
                        put("effectiveFrom", result.getTimestamp("effective_from")?.toInstant()?.toString())
                        put("effectiveTo", result.getTimestamp("effective_to")?.toInstant()?.toString())
                        set<JsonNode>("content", mapper.readTree(result.getString("content")))
                    }
                }
            }
            val digest = com.openbank.productcatalog.infrastructure.catalog.CatalogJson(mapper).sha256(revision)
            connection.prepareStatement(
                "INSERT INTO pension_revision_approvals " +
                    "(id, revision_id, role, issuer, subject, digest, reason, approved_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, revisionId)
                statement.setString(3, role)
                statement.setString(4, "https://example.invalid/issuer")
                statement.setString(5, subject)
                statement.setString(6, digest)
                statement.setString(7, "separate approved decision")
                statement.setTimestamp(8, java.sql.Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
        }
    }

    private companion object {
        const val SCHEMA_ID = "org.openbank.retirement.pension-savings"
        val STRATEGIES = listOf("CONSERVATIVE", "BALANCED", "DYNAMIC", "LIFECYCLE")
    }
}
