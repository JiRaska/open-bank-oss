// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.product.ir.ProductIrCanonical
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.productrevision.HmacIrSigner
import com.openbank.libs.productrevision.IrSignature
import com.openbank.libs.productrevision.PinnedRevisionLoader
import com.openbank.libs.productrevision.PinnedRevisionUnavailableException
import com.openbank.libs.productrevision.RevisionIdentity
import com.openbank.libs.productrevision.RevisionLoadOutcome
import com.openbank.libs.productrevision.SignedRevisionCopy
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * API contract of ADR-0352 phase 2 on the catalog (openapi.yaml 2.9.0), end to end:
 * a verified human publishes an IR-bearing revision through `publish-ir`, the catalog signs its
 * canonical `revisionHash`, and a consumer-side [PinnedRevisionLoader] built from nothing but the
 * HTTP response verifies and loads it — the phase-2 "done when". Negative cases: a machine identity,
 * a non-JWT identity and a read-only principal are refused; the plain publish refuses IR; publish-ir
 * refuses a revision without IR; an invalid IR never becomes a draft; an edited copy does not load.
 *
 * The test profile runs `ir-signing.mode=hmac` with a test-only secret (application.yaml `%test`);
 * [IrSigningModeTest] proves that mode is refused in a production launch.
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

    @Inject
    lateinit var mapper: ObjectMapper

    private val irJson: String = checkNotNull(javaClass.getResourceAsStream("/product-ir/savings-fixed.json"))
        .readBytes().toString(Charsets.UTF_8)

    private val testVerifier = HmacIrSigner(TEST_SECRET.toByteArray(), "product-ir-publish")

    @Test
    @TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "azp",
                value = "openbank-admin-ui",
            ), Claim(key = "sub", value = "u-op"), Claim(key = "preferred_username", value = "test-operator"),
        ],
    )
    fun `human publish-ir signs the canonical hash and a consumer loader verifies it from the HTTP response`() {
        val (offeringId, revisionId) = draft(irJson)
        setMaker(revisionId, "independent-ir-author")
        val expectedHash = ProductIrParser.parse(irJson).revisionHash

        Given {
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
            body("irSignature.revisionHash", equalTo(expectedHash))
            body("irSignature.algorithm", equalTo("hmac-sha256"))
            body("irSignature.keyId", equalTo("product-ir-publish"))
        }

        // A consumer sees nothing but this response: the IR as served and the detached signature.
        val copy = signedCopyFromResponse(getRevision(revisionId))
        val pin = RevisionIdentity("SAV-FIXED", revisionId, 1, 1, expectedHash)
        assertThat(copy.identity).isEqualTo(pin)
        val loader = PinnedRevisionLoader({ p -> copy.takeIf { p == pin } }, testVerifier)
        val loaded = loader.load(pin)
        assertThat(loaded.productCode).isEqualTo("SAV-FIXED")
        assertThat(ProductIrCanonical.revisionHash(loaded)).isEqualTo(expectedHash)

        // A copy edited after signing does not load, whatever its signature says.
        val edited = copy.copy(irJson = copy.irJson.replace("0.0250", "0.0350"))
        assertThatThrownBy { PinnedRevisionLoader({ edited }, testVerifier).load(pin) }
            .isInstanceOf(PinnedRevisionUnavailableException::class.java)
            .extracting { (it as PinnedRevisionUnavailableException).outcome }
            .isEqualTo(RevisionLoadOutcome.HASH_MISMATCH)
        // A signature over another hash, or for another revision of the same IR, does not verify.
        assertThat(testVerifier.verify(pin.copy(revisionHash = "0".repeat(64)), copy.signature)).isFalse()
        assertThat(testVerifier.verify(pin.copy(revisionId = UUID.randomUUID()), copy.signature)).isFalse()
        assertThat(testVerifier.verify(pin.copy(revisionNumber = 2), copy.signature)).isFalse()

        // Every read carries the signature, list included (null means unsigned, never "not loaded").
        Given { this } When { get("/api/v2/products/$offeringId") } Then {
            statusCode(200)
            body("irSignature.revisionHash", equalTo(expectedHash))
        }
        Given { this } When { get("/api/v2/offerings/$offeringId/revisions") } Then {
            statusCode(200)
            body("[0].irSignature.revisionHash", equalTo(expectedHash))
        }
        // The evidence row is immutable in the database (V16 trigger).
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
    @TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "azp",
                value = "openbank-admin-ui",
            ), Claim(key = "sub", value = "u-op"), Claim(key = "preferred_username", value = "test-operator"),
        ],
    )
    fun `numeric decimals over HTTP hash exactly like the decimal strings of the committed file`() {
        // Same document, every quoted decimal written as a JSON number: the hash must not move.
        val numeric = Regex("\"(-?[0-9]+\\.[0-9]+)\"").replace(irJson) { it.groupValues[1] }
        assertThat(numeric).isNotEqualTo(irJson).contains("\"rate\": 0.0250")
        val (offeringId, revisionId) = draft(numeric)
        setMaker(revisionId, "independent-ir-author")
        Given {
            contentType("application/json")
            body("""{"reason":"numeric authoring"}""")
            header("If-Match", "\"0\"")
        } When {
            post("/api/v2/offerings/$offeringId/revisions/$revisionId/publish-ir")
        } Then {
            statusCode(200)
            body("irSignature.revisionHash", equalTo(ProductIrParser.parse(irJson).revisionHash))
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "sub", value = "sa-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
            Claim(key = "client_id", value = "openbank-services"),
        ],
    )
    fun `a service account holding operator and admin is refused publish-ir by the service itself`() {
        Given {
            contentType("application/json")
            body("""{"reason":"machine"}""")
            header("If-Match", "\"0\"")
        } When {
            post("/api/v2/offerings/${UUID.randomUUID()}/revisions/${UUID.randomUUID()}/publish-ir")
        } Then {
            statusCode(403)
        }
    }

    @Test
    fun `an identity without positive interactive-human evidence is refused publish-ir, failing closed`() {
        Given {
            contentType("application/json")
            body("""{"reason":"unverified"}""")
            header("If-Match", "\"0\"")
        } When {
            post("/api/v2/offerings/${UUID.randomUUID()}/revisions/${UUID.randomUUID()}/publish-ir")
        } Then {
            statusCode(403)
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
        Given { this } When { get("/api/v2/revisions/$revisionId") } Then {
            statusCode(200)
            body("state", equalTo("DRAFT"))
            body("irSignature", nullValue())
        }
    }

    @Test
    @TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "azp",
                value = "openbank-admin-ui",
            ), Claim(key = "sub", value = "u-op"), Claim(key = "preferred_username", value = "test-operator"),
        ],
    )
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
        assertThat(broken).isNotEqualTo(irJson)
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

    private fun getRevision(revisionId: UUID): String =
        Given { this } When { get("/api/v2/revisions/$revisionId") } Then { statusCode(200) } Extract
            { body().asString() }

    private fun signedCopyFromResponse(body: String): SignedRevisionCopy {
        val node = mapper.readTree(body)
        val sig = node.get("irSignature")
        val irJson = node.get("content").get("behaviour").toString()
        val ir = ProductIrParser.parse(irJson)
        return SignedRevisionCopy(
            irJson = irJson,
            identity = RevisionIdentity(
                productCode = ir.productCode,
                revisionId = UUID.fromString(node.get("id").asText()),
                revisionNumber = node.get("number").asLong(),
                irVersion = ir.irVersion,
                revisionHash = sig.get("revisionHash").asText(),
            ),
            signature = IrSignature(
                sig.get("keyId").asText(),
                sig.get("algorithm").asText(),
                sig.get("signature").asText(),
            ),
        )
    }

    private fun draft(behaviour: String?): Pair<UUID, UUID> {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        val specificationId = createDepositSpecification("SAV_IR_$suffix")
        val offeringId = createOffering(specificationId, "SAV_IR_${suffix}_CZ")
        val revisionId = UUID.fromString(
            Given {
                contentType("application/json")
                body(revisionBody(behaviour))
            } When {
                post("/api/v2/offerings/$offeringId/revisions")
            } Then {
                statusCode(201)
                body("state", equalTo("DRAFT"))
            } Extract { jsonPath().getString("id") },
        )
        return offeringId to revisionId
    }

    private fun revisionBody(behaviour: String?): String =
        """{"schemaRef":{"id":"org.openbank.banking.deposit","version":2},""" +
            """"name":{"en":"Savings with behaviour"},"attributes":$DEPOSIT_ATTRIBUTES""" +
            (behaviour?.let { ""","behaviour":$it""" } ?: "") + "}"

    private fun createDepositSpecification(code: String): UUID = UUID.fromString(
        Given {
            contentType("application/json")
            body("""{"code":"$code","schemaRef":{"id":"org.openbank.banking.deposit","version":2}}""")
        } When {
            post("/api/v2/specifications")
        } Then {
            statusCode(201)
        } Extract { jsonPath().getString("id") },
    )

    private fun createOffering(specificationId: UUID, code: String): UUID = UUID.fromString(
        Given {
            contentType("application/json")
            body("""{"specificationId":"$specificationId","code":"$code","market":$MARKET}""")
        } When {
            post("/api/v2/offerings")
        } Then {
            statusCode(201)
        } Extract { jsonPath().getString("id") },
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
        /** Matches application.yaml `%test` openbank.catalog.ir-signing.hmac-secret. */
        const val TEST_SECRET = "test-only-ir-signing-secret-not-for-production"
        const val MARKET = """{"countries":["CZ"],"channels":["WEB"],"locales":["cs-CZ"]}"""
        const val DEPOSIT_ATTRIBUTES =
            """{"currency":"CZK","productType":"SAVINGS","interest":{"rateType":"FIXED","dayCount":"ACT_365","payoutFrequency":"MONTHLY","annualRate":"0.0425"}}"""
    }
}
