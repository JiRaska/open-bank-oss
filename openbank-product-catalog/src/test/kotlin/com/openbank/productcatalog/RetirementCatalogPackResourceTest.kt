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
    fun `every illustrative CZ DPS and DIP offering validates against the pack`() {
        val offerings = fixtureOfferings()
        val pairs = offerings.map { offering ->
            offering["attributes"]["productLine"].asText() to offering["attributes"]["fundStrategy"].asText()
        }
        assertThat(pairs)
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

    private fun validate(attributes: String, expectedValid: Boolean) {
        Given {
            contentType("application/json")
            body("""{"attributes":$attributes}""")
        } When {
            post("/api/v2/product-types/$SCHEMA_ID/versions/1/validate")
        } Then {
            statusCode(200)
            body("valid", equalTo(expectedValid))
        }
    }

    private fun createSpecification(code: String): UUID = UUID.fromString(
        Given {
            contentType("application/json")
            body("""{"code":"$code","schemaRef":{"id":"$SCHEMA_ID","version":1}}""")
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

    private fun createRevision(offeringId: UUID, offering: JsonNode): UUID = UUID.fromString(
        Given {
            contentType("application/json")
            body(
                mapper.writeValueAsString(
                    mapOf(
                        "schemaRef" to mapOf("id" to SCHEMA_ID, "version" to 1),
                        "name" to mapOf("en" to offering["name"].asText()),
                        "attributes" to offering["attributes"],
                    ),
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

    private companion object {
        const val SCHEMA_ID = "org.openbank.retirement.pension-savings"
        val STRATEGIES = listOf("CONSERVATIVE", "BALANCED", "DYNAMIC", "LIFECYCLE")
    }
}
