// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.contract

import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.EventKind
import com.openbank.libs.product.ir.Posting
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.product.ir.ProductState
import com.openbank.libs.product.replay.DiffOptions
import com.openbank.libs.product.replay.ProductBehaviourDiff
import com.openbank.libs.product.replay.ReplayAccount
import com.openbank.libs.product.replay.ReplayPortfolio
import com.openbank.libs.product.replay.ReplayPosting
import com.openbank.libs.product.replay.ReplayRange
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
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

/**
 * API contract of the approval panel's backtest (ADR-0352 §11.4, phase 5b; openapi.yaml 2.11.0):
 * `POST /api/v2/offerings/{offeringId}/revisions/{revisionId}/behaviour-diff`.
 *
 * The claim that matters is the first test's last assertion: the service returns the exact bytes the
 * shared `ProductBehaviourDiff` produces for the same revisions, portfolio and range — the engine
 * `obp diff --behavioural` prints — and compares the candidate against the PUBLISHED revision, in that
 * order (revision A is the base). The rest are the bounds and the refusals.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
class BehaviourDiffContractTest {

    @Inject
    lateinit var dataSource: DataSource

    private val baseIr: String = Files.readString(
        Path.of("..", "openbank-products", "ir", "savings-fixed.json"),
    ).trimEnd()
    private val repricedIr: String = baseIr.replace("0.0250000000", "0.0350000000")

    @Test
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"), Claim(key = "sub", value = "u-op"),
            Claim(key = "preferred_username", value = "test-operator"),
        ],
    )
    fun `the backtest is ProductBehaviourDiff's own document, published revision first`() {
        assertThat(repricedIr).isNotEqualTo(baseIr)
        val (offeringId, publishedId) = publishedRevision(baseIr)
        val candidateId = draft(offeringId, repricedIr)

        val body = Given {
            contentType("application/json")
            body(diffRequest(PORTFOLIO, "2026-01-01", "2026-03-31", threshold = "0.50"))
        } When {
            post("/api/v2/offerings/$offeringId/revisions/$candidateId/behaviour-diff")
        } Then {
            statusCode(200)
            body("diff.schema", equalTo("openbank.product-behaviour-diff.v1"))
            body("diff.valid", equalTo(true))
            body("diff.identical", equalTo(false))
            body("diff.revisionA.revisionHash", equalTo(ProductIrParser.parse(baseIr).revisionHash))
            body("diff.revisionB.revisionHash", equalTo(ProductIrParser.parse(repricedIr).revisionHash))
        } Extract { jsonPath().getString("canonicalJson") }

        val expected = ProductBehaviourDiff(ProductInterpreter(FinanceProductArithmetic)).diff(
            ProductIrParser.parse(baseIr),
            ProductIrParser.parse(repricedIr),
            expectedPortfolio(),
            ReplayRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"), ZoneId.of("UTC")),
            DiffOptions(BigDecimal("0.50")),
        ).canonicalJson
        assertThat(body).isEqualTo(expected)

        // An explicit base equal to the candidate is identical, never a phantom difference.
        Given {
            contentType("application/json")
            body(diffRequest(PORTFOLIO, "2026-01-01", "2026-03-31", base = candidateId))
        } When {
            post("/api/v2/offerings/$offeringId/revisions/$candidateId/behaviour-diff")
        } Then {
            statusCode(200)
            body("diff.identical", equalTo(true))
        }
        assertThat(publishedId).isNotEqualTo(candidateId)
    }

    @Test
    fun `an oversized, malformed or out-of-range backtest is a 400 before any replay`() {
        val (offeringId, revisionId) = draftOffering(baseIr)
        val path = "/api/v2/offerings/$offeringId/revisions/$revisionId/behaviour-diff"
        val tooMany = """{"accounts":[${(0..500).joinToString(",") { account("a$it", "") }}]}"""
        val tooLong = """{"accounts":[${(0..1).joinToString(",") { account("a$it", "") }}]}"""
        listOf(
            diffRequest(tooMany, "2026-01-01", "2026-01-31", base = revisionId),
            diffRequest("""{"accounts":[],"extra":1}""", "2026-01-01", "2026-01-31", base = revisionId),
            diffRequest(
                """{"accounts":[{"id":"x","state":{"currency":"EUR"}}]}""",
                "2026-01-01",
                "2026-01-31",
                base = revisionId,
            ),
            diffRequest(PORTFOLIO, "2026-02-01", "2026-01-01", base = revisionId),
            // An exponent is not a money amount: it would sidestep every account-day bound.
            diffRequest(PORTFOLIO.replace("\"20000.00\"", "\"1E+20\""), "2026-01-01", "2026-01-31", base = revisionId),
            diffRequest(
                PORTFOLIO.replace("\"150.00\"", "\"0.0000000000001\""),
                "2026-01-01",
                "2026-01-31",
                base = revisionId,
            ),
            diffRequest(PORTFOLIO, "2026-01-01", "2026-01-31", base = revisionId, threshold = "1e999999"),
            diffRequest(tooLong, "1900-01-01", "2200-01-01", base = revisionId),
        ).forEach { bad ->
            Given {
                contentType("application/json")
                body(bad)
            } When {
                post(path)
            } Then {
                statusCode(400)
            }
        }
    }

    @Test
    fun `a revision of another offering, or an offering with nothing published, is a 404`() {
        val (offeringA, revisionA) = draftOffering(baseIr)
        val (offeringB, _) = draftOffering(baseIr)
        Given {
            contentType("application/json")
            body(diffRequest(PORTFOLIO, "2026-01-01", "2026-01-31", base = revisionA))
        } When {
            post("/api/v2/offerings/$offeringB/revisions/$revisionA/behaviour-diff")
        } Then {
            statusCode(404)
        }
        Given {
            contentType("application/json")
            body(diffRequest(PORTFOLIO, "2026-01-01", "2026-01-31"))
        } When {
            post("/api/v2/offerings/$offeringA/revisions/$revisionA/behaviour-diff")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "nobody", roles = ["SOME_OTHER_ROLE"])
    fun `a caller without catalog read is refused`() {
        Given {
            contentType("application/json")
            body(diffRequest(PORTFOLIO, "2026-01-01", "2026-01-31"))
        } When {
            post("/api/v2/offerings/${UUID.randomUUID()}/revisions/${UUID.randomUUID()}/behaviour-diff")
        } Then {
            statusCode(403)
        }
    }

    private fun expectedPortfolio() = ReplayPortfolio(
        listOf(
            ReplayAccount(
                "low",
                ProductState("EUR", BigDecimal("500.00"), openedOn = LocalDate.parse("2026-01-01")),
                emptyList(),
            ),
            ReplayAccount(
                "saver",
                ProductState("EUR", BigDecimal("20000.00"), openedOn = LocalDate.parse("2026-01-01")),
                listOf(
                    ReplayPosting(
                        LocalDate.parse("2026-02-10"),
                        Posting(EventKind.WITHDRAWAL, PostingDirection.DEBIT, BigDecimal("150.00")),
                    ),
                ),
            ),
        ),
    )

    private fun diffRequest(
        portfolio: String,
        from: String,
        to: String,
        base: UUID? = null,
        threshold: String? = null,
    ): String = """{"portfolio":$portfolio,"from":"$from","to":"$to"""" +
        (base?.let { ""","baseRevisionId":"$it"""" } ?: "") +
        (threshold?.let { ""","materialityThreshold":"$it"""" } ?: "") + "}"

    private fun publishedRevision(ir: String): Pair<UUID, UUID> {
        val (offeringId, revisionId) = draftOffering(ir)
        dataSource.connection.use { c ->
            c.prepareStatement("UPDATE catalog_revisions SET maker_id = ? WHERE id = ?").use { st ->
                st.setString(1, "independent-ir-author")
                st.setObject(2, revisionId)
                assertThat(st.executeUpdate()).isEqualTo(1)
            }
        }
        Given {
            contentType("application/json")
            body("""{"reason":"baseline for the backtest"}""")
            header("If-Match", "\"0\"")
        } When {
            post("/api/v2/offerings/$offeringId/revisions/$revisionId/publish-ir")
        } Then {
            statusCode(200)
        }
        return offeringId to revisionId
    }

    private fun draftOffering(ir: String): Pair<UUID, UUID> {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        val specificationId = id(
            "/api/v2/specifications",
            """{"code":"SAV_BD_$suffix","schemaRef":{"id":"org.openbank.banking.deposit","version":2}}""",
        )
        val offeringId =
            id(
                "/api/v2/offerings",
                """{"specificationId":"$specificationId","code":"SAV_BD_${suffix}_CZ","market":$MARKET}""",
            )
        return offeringId to draft(offeringId, ir)
    }

    private fun draft(offeringId: UUID, ir: String): UUID = id(
        "/api/v2/offerings/$offeringId/revisions",
        """{"schemaRef":{"id":"org.openbank.banking.deposit","version":2},""" +
            """"name":{"en":"Savings backtest"},"attributes":$DEPOSIT_ATTRIBUTES,"behaviour":$ir}""",
    )

    private fun id(path: String, body: String): UUID = UUID.fromString(
        Given {
            contentType("application/json")
            body(body)
        } When {
            post(path)
        } Then {
            statusCode(201)
        } Extract { jsonPath().getString("id") },
    )

    private companion object {
        const val MARKET = """{"countries":["CZ"],"channels":["WEB"],"locales":["cs-CZ"]}"""
        const val DEPOSIT_ATTRIBUTES =
            """{"currency":"CZK","productType":"SAVINGS","interest":{"rateType":"FIXED","dayCount":"ACT_365","payoutFrequency":"MONTHLY","annualRate":"0.0425"}}"""
        val PORTFOLIO = """{"accounts":[""" +
            account("low", "") + "," +
            account(
                "saver",
                """{"on":"2026-02-10","kind":"WITHDRAWAL","direction":"DEBIT","amount":"150.00"}""",
                "20000.00",
            ) +
            "]}"

        fun account(id: String, postings: String, balance: String = "500.00"): String =
            """{"id":"$id","state":{"currency":"EUR","balance":"$balance","openedOn":"2026-01-01"},"postings":[$postings]}"""
    }
}
