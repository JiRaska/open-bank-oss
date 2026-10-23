// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.contract

import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.productrevision.HmacIrSigner
import com.openbank.libs.productrevision.IrSignature
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
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * API contract of ADR-0352 phase 2 on the catalog (openapi.yaml 2.8.0): an IR-bearing revision is
 * published ONLY through `publish-ir`, which signs its canonical `revisionHash`; the plain publish
 * refuses it, `publish-ir` refuses a revision without IR, an invalid IR never becomes a draft, and
 * the signature a reader gets back verifies under the configured key. Negative authorization case:
 * a read-only principal is forbidden (403) from publish-ir.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
class IrPublishContractTest {

    @Inject
    lateinit var dataSource: DataSource

    private val irJson: String = checkNotNull(javaClass.getResourceAsStream("/product-ir/savings-fixed.json"))
        .readBytes().toString(Charsets.UTF_8)

    @Test
    fun `publish-ir signs the canonical revisionHash and every single-revision read returns the signature`() {
        val (offeringId, revisionId) = draft(irJson)
        setMaker(revisionId, "independent-ir-author")
        val expectedHash = ProductIrParser.parse(irJson).revisionHash

        val signature = Given {
            contentType("application/json")
            body("""{"reason":"approved savings behaviour"}""")
            header("If-Match", "\"0\"")
        } When {
            post("/api/v2/offerings/$offeringId/revisions/$revisionId/publish-ir")
        } Then {
            statusCode(200)
            header("ETag", equalTo("\"1\""))
            body("state", equalTo("PUBLISHED"))
            body("checkerId", equalTo("test-operator"))
            body("content.behaviour.productCode", equalTo("SAV-FIXED"))
            body("irSignature.revisionHash", equalTo(expectedHash))
            body("irSignature.algorithm", equalTo("hmac-sha256"))
            body("irSignature.keyId", equalTo("product-ir-publish"))
        } Extract { jsonPath().getString("irSignature.signature") }

        // The signature verifies under the test profile's dev key (application.yaml defaults) and binds
        // exactly the hash — a different hash does not verify.
        val devSigner = HmacIrSigner("CHANGE_ME_LOCAL_DEV_ONLY_32_BYTES".toByteArray(), "product-ir-publish")
        val sig = IrSignature("product-ir-publish", "hmac-sha256", signature)
        assertThat(devSigner.verify(expectedHash, sig)).isTrue()
        assertThat(devSigner.verify("0".repeat(64), sig)).isFalse()

        Given { this } When {
            get("/api/v2/revisions/$revisionId")
        } Then {
            statusCode(200)
            body("irSignature.revisionHash", equalTo(expectedHash))
            body("irSignature.signature", equalTo(signature))
        }
        Given { this } When {
            get("/api/v2/products/$offeringId")
        } Then {
            statusCode(200)
            body("state", equalTo("PUBLISHED"))
            body("irSignature.revisionHash", equalTo(expectedHash))
        }
        // The evidence row is immutable in the database (V15 trigger).
        dataSource.connection.use { c ->
            c.createStatement().use { st ->
                val failure = runCatching {
                    st.executeUpdate("DELETE FROM catalog_revision_ir_signatures WHERE revision_id = '$revisionId'")
                }.exceptionOrNull()
                assertThat(failure).hasMessageContaining("immutable")
            }
        }
    }

    @Test
    fun `the plain publish refuses an IR-bearing revision, so it can never be published unsigned`() {
        val (offeringId, revisionId) = draft(irJson)
        setMaker(revisionId, "independent-ir-author")
        Given {
            contentType("application/json")
            body("""{"reason":"trying the unsigned path"}""")
            header("If-Match", "\"0\"")
        } When {
            post("/api/v2/offerings/$offeringId/revisions/$revisionId/publish")
        } Then {
            statusCode(409)
            body("code", equalTo("CATALOG_CONFLICT"))
        }
        Given { this } When {
            get("/api/v2/revisions/$revisionId")
        } Then {
            statusCode(200)
            body("state", equalTo("DRAFT"))
            body("irSignature", nullValue())
        }
    }

    @Test
    fun `publish-ir refuses a revision that carries no IR`() {
        val (offeringId, revisionId) = draft(behaviour = null)
        setMaker(revisionId, "independent-author")
        Given {
            contentType("application/json")
            body("""{"reason":"no behaviour here"}""")
            header("If-Match", "\"0\"")
        } When {
            post("/api/v2/offerings/$offeringId/revisions/$revisionId/publish-ir")
        } Then {
            statusCode(409)
            body("code", equalTo("CATALOG_CONFLICT"))
        }
    }

    @Test
    fun `an invalid IR document never becomes a draft`() {
        val specificationId = createDepositSpecification("SAV_IR_INVALID")
        val offeringId = createOffering(specificationId, "SAV_IR_INVALID_CZ")
        val broken = irJson.replace("\"rateType\": \"FIXED\"", "\"rateType\": \"FLOATING\"")
        Given {
            contentType("application/json")
            body(revisionBody(broken))
        } When {
            post("/api/v2/offerings/$offeringId/revisions")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "catalog-reader", roles = ["CATALOG_SCOPE_READ"])
    fun `a read-only principal is forbidden from publish-ir`() {
        Given {
            contentType("application/json")
            body("""{"reason":"forbidden"}""")
            header("If-Match", "\"0\"")
        } When {
            post("/api/v2/offerings/${UUID.randomUUID()}/revisions/${UUID.randomUUID()}/publish-ir")
        } Then {
            statusCode(403)
        }
    }

    private fun draft(behaviour: String?): Pair<UUID, UUID> {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        val specificationId = createDepositSpecification("SAV_IR_$suffix")
        val offeringId = createOffering(specificationId, "SAV_IR_${suffix}_CZ")
        val revisionId = UUID.fromString(
            (
                Given {
                    contentType("application/json")
                    body(revisionBody(behaviour))
                } When {
                    post("/api/v2/offerings/$offeringId/revisions")
                } Then {
                    statusCode(201)
                    body("state", equalTo("DRAFT"))
                }
                ).extract().jsonPath().getString("id"),
        )
        return offeringId to revisionId
    }

    private fun revisionBody(behaviour: String?): String =
        """{"schemaRef":{"id":"org.openbank.banking.deposit","version":2},""" +
            """"name":{"en":"Savings with behaviour"},"attributes":$DEPOSIT_ATTRIBUTES""" +
            (behaviour?.let { ""","behaviour":$it""" } ?: "") + "}"

    private fun createDepositSpecification(code: String): UUID = UUID.fromString(
        (
            Given {
                contentType("application/json")
                body("""{"code":"$code","schemaRef":{"id":"org.openbank.banking.deposit","version":2}}""")
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
                body("""{"specificationId":"$specificationId","code":"$code","market":$MARKET}""")
            } When {
                post("/api/v2/offerings")
            } Then {
                statusCode(201)
            }
            ).extract().jsonPath().getString("id"),
    )

    private fun setMaker(revisionId: UUID, maker: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE catalog_revisions SET maker_id = ? WHERE id = ?").use { statement ->
                statement.setString(1, maker)
                statement.setObject(2, revisionId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private companion object {
        const val MARKET = """{"countries":["CZ"],"channels":["WEB"],"locales":["cs-CZ"]}"""
        const val DEPOSIT_ATTRIBUTES =
            """{"currency":"CZK","productType":"SAVINGS","interest":{"rateType":"FIXED","dayCount":"ACT_365","payoutFrequency":"MONTHLY","annualRate":"0.0425"}}"""
    }
}
