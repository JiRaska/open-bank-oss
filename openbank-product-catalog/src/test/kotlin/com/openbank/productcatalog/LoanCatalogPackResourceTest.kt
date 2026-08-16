// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * ADR-0260 Phase B: proves the `banking.loan-v1` schema pack generalizes the ADR-0257 kernel to
 * loans the same way `banking.deposit-v1` and `insurance.term-life` already do — draft, publish,
 * pin — through the real `/api/v2` REST surface, not a domain-only unit test.
 *
 * `deposit-v1.schema.json` has NO test coverage of its own file content today (only a `SchemaRef`
 * reference in `CatalogKernelTest`); `loan-v1` gets a real round-trip proof here instead of
 * repeating that gap.
 *
 * No lending-service or interest-service change of any kind — Phase C (money-path wiring) is
 * explicitly out of scope for this pack and needs its own ADR (ADR-0260 References).
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestSecurity(user = "loan-pack-operator", roles = ["ROLE_OPERATOR"])
class LoanCatalogPackResourceTest {

    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `banking loan-v1 is registered as a trusted product type`() {
        Given { this } When {
            get("/api/v2/product-types")
        } Then {
            statusCode(200)
            body("id", hasItem("org.openbank.banking.loan"))
        }

        Given { this } When {
            get("/api/v2/product-types/org.openbank.banking.loan/versions/1")
        } Then {
            statusCode(200)
            body("id", equalTo("org.openbank.banking.loan"))
            body("version", equalTo(1))
        }
    }

    @Test
    fun `a loan revision drafts, publishes and pins through the v2 API`() {
        val specificationId = createLoanSpecification("LOAN_PACK_E2E")
        val offeringId = createOffering(specificationId, "LOAN_PACK_E2E_CZ")
        val revisionId = createRevision(offeringId, "Personal installment loan")
        setMaker(revisionId, "independent-loan-maker")

        Given {
            contentType("application/json")
            body("""{"reason":"loan launch approval"}""")
            header("If-Match", "\"0\"")
        } When {
            post("/api/v2/offerings/$offeringId/revisions/$revisionId/publish")
        } Then {
            statusCode(200)
            body("state", equalTo("PUBLISHED"))
            body("checkerId", equalTo("loan-pack-operator"))
        }

        // Published-view read: pinned attributes and the BASE_RATE_ANNUAL price component
        // survive the round trip exactly as authored (ADR-0260's "rate is a PriceComponent,
        // never an attributes field" decision).
        Given { this } When {
            get("/api/v2/products/$offeringId")
        } Then {
            statusCode(200)
            body("state", equalTo("PUBLISHED"))
            body("content.attributes.productType", equalTo("INSTALLMENT_LOAN"))
            body("content.attributes.amortizationMethod", equalTo("ANNUITY"))
            body("content.attributes.allocationOrder[0]", equalTo("FEES"))
            body("content.attributes.allocationOrder[1]", equalTo("PENALTY"))
            body("content.attributes.allocationOrder[2]", equalTo("INTEREST"))
            body("content.attributes.allocationOrder[3]", equalTo("PRINCIPAL"))
            body("content.prices[0].code", equalTo("BASE_RATE_ANNUAL"))
            body("content.prices[0].kind", equalTo("RATE"))
            body("content.prices[0].value", equalTo("5.90"))
        }

        // Immutability once PUBLISHED: an in-place edit of the published revision is rejected —
        // the same guarantee `publishesWithFourEyesAtomicallyAndExactly` proves for deposits.
        Given {
            contentType("application/json")
            body(revisionPayload("Attempted post-publish edit"))
            header("If-Match", "\"1\"")
        } When {
            put("/api/v2/offerings/$offeringId/revisions/$revisionId")
        } Then {
            statusCode(409)
        }
    }

    // 422, not 400: a closed-enum/additionalProperties violation is a JSON-SCHEMA semantic
    // failure (networknt validation, mapped by ProductCatalogExceptionMappers'
    // CatalogValidationException -> 422), distinct from the 400s
    // `rejectsOversizedDeepAndOutOfRangeRevisionInputsBeforePersistence` proves for the
    // structural profile checks (nesting depth, instance size) in `CatalogSchemaProfile`.

    @Test
    fun `attributes violating the closed amortizationMethod enum are rejected before persistence`() {
        val specificationId = createLoanSpecification("LOAN_PACK_BAD_ENUM")
        val offeringId = createOffering(specificationId, "LOAN_PACK_BAD_ENUM_CZ")

        Given {
            contentType("application/json")
            body(revisionPayload("Invalid amortization method", amortizationMethod = "\"COMPOUND_INTEREST\""))
        } When {
            post("/api/v2/offerings/$offeringId/revisions")
        } Then {
            statusCode(422)
        }
    }

    @Test
    fun `attributes with an unknown additional field are rejected (closed schema profile)`() {
        val specificationId = createLoanSpecification("LOAN_PACK_ADDL_PROP")
        val offeringId = createOffering(specificationId, "LOAN_PACK_ADDL_PROP_CZ")

        Given {
            contentType("application/json")
            body(revisionPayload("Unexpected extra field", extraField = ",\"notInSchema\":\"x\""))
        } When {
            post("/api/v2/offerings/$offeringId/revisions")
        } Then {
            statusCode(422)
        }
    }

    // --- fixtures -----------------------------------------------------------------------------

    private fun createLoanSpecification(code: String): UUID = UUID.fromString(
        (
            Given {
                contentType("application/json")
                body("""{"code":"$code","schemaRef":{"id":"org.openbank.banking.loan","version":1}}""")
            } When {
                post("/api/v2/specifications")
            } Then {
                statusCode(201)
            }
            ).extract().jsonPath().getString("id"),
    )

    private fun createOffering(specificationId: UUID, code: String): UUID = UUID.fromString(
        (
            Given {
                contentType("application/json")
                body("""{"specificationId":"$specificationId","code":"$code","market":{"countries":["CZ"]}}""")
            } When {
                post("/api/v2/offerings")
            } Then {
                statusCode(201)
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
                body("state", equalTo("DRAFT"))
            }
            ).extract().jsonPath().getString("id"),
    )

    private fun revisionPayload(
        name: String,
        amortizationMethod: String = "\"ANNUITY\"",
        extraField: String = "",
    ): String = """{"schemaRef":{"id":"org.openbank.banking.loan","version":1},""" +
        """"name":{"en":"$name"},"attributes":{""" +
        """"productType":"INSTALLMENT_LOAN","currency":"CZK","tenorMonths":60,""" +
        """"amortizationMethod":$amortizationMethod,"accrualBasis":"ACT_365",""" +
        """"allocationOrder":["FEES","PENALTY","INTEREST","PRINCIPAL"]$extraField},""" +
        """"prices":[{"code":"BASE_RATE_ANNUAL","kind":"RATE","value":"5.90","unit":"percent",""" +
        """"cadence":"ANNUALLY","taxTreatment":"UNSPECIFIED"}]}"""

    private fun setMaker(revisionId: UUID, maker: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE catalog_revisions SET maker_id = ? WHERE id = ?").use { statement ->
                statement.setString(1, maker)
                statement.setObject(2, revisionId)
                statement.executeUpdate()
            }
        }
    }
}
