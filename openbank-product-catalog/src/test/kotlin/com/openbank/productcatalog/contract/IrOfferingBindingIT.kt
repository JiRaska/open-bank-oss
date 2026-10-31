// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.contract

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.productcatalog.GoldenOfferings
import com.openbank.productcatalog.application.ApproverIdentity
import com.openbank.productcatalog.application.BacktestEvidence
import com.openbank.productcatalog.application.CatalogPreconditionFailedException
import com.openbank.productcatalog.application.GenericCatalogService
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource

/**
 * ADR-0352 §7 (phase 5b): WHICH golden portfolio unlocks a revision is decided by the OFFERING, never
 * by the IR the author wrote. The IR's `productCode` must name the offering (`-` read as `_`) and its
 * `currency` must equal the revision's `currency` attribute, on lint, on every write and again at the
 * backtest; a mismatch is a 409. The golden portfolio is looked up by the offering's code only — no
 * currency fallback — so a product without its own entry cannot be backtested, nor therefore approved.
 * A machine principal may not run the backtest at all, and leaves no evidence row.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
class IrOfferingBindingIT {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var catalog: GenericCatalogService

    @Test
    fun `an IR naming another product is refused on write, so it never becomes a draft`() {
        val offeringId = offering("SAVINGS_FIXED")
        create(offeringId, revisionBody(CURRENT_ACCOUNT_IR, "EUR"), 409)
        Given { this } When { get("/api/v2/offerings/$offeringId/revisions") } Then {
            statusCode(200)
            body("findAll { it.state == 'DRAFT' }.size()", equalTo(0))
        }
    }

    @Test
    fun `an IR in another currency than the revision's currency attribute is refused on write`() {
        create(offering("SAVINGS_FIXED"), revisionBody(SAVINGS_FIXED_IR, "CZK"), 409)
    }

    @Test
    fun `an edit that re-points the IR at another product is refused`() {
        val offeringId = offering("SAVINGS_FIXED")
        val revisionId = create(offeringId, revisionBody(SAVINGS_FIXED_IR, "EUR"), 201)!!
        Given {
            contentType("application/json")
            header("If-Match", "\"0\"")
            body(revisionBody(CURRENT_ACCOUNT_IR, "EUR"))
        } When { put("/api/v2/offerings/$offeringId/revisions/$revisionId") } Then { statusCode(409) }
    }

    @Test
    fun `lint against an offering refuses a foreign productCode and accepts its own`() {
        val offeringId = offering("SAVINGS_FIXED")
        lint("""{"offeringId":"$offeringId","ir":$CURRENT_ACCOUNT_IR}""", 409)
        lint("""{"offeringId":"$offeringId","ir":$SAVINGS_FIXED_IR}""", 200)
        // Without an offering the lint is the plain dry run it always was.
        lint("""{"ir":$CURRENT_ACCOUNT_IR}""", 200)
    }

    @Test
    @TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = ISSUER),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "u-op"),
            Claim(key = "preferred_username", value = "test-operator"),
        ],
    )
    fun `a stored revision whose IR names a foreign product does not pick that product's golden portfolio`() {
        val offeringId = offering("SAVINGS_FIXED")
        val revisionId = create(offeringId, revisionBody(SAVINGS_FIXED_IR, "EUR"), 201)!!
        // However it got there (a row written before this check existed), the backtest re-checks it.
        dataSource.connection.use { c ->
            c.prepareStatement(
                "UPDATE catalog_revisions SET content = jsonb_set(content::jsonb, '{behaviour,productCode}', " +
                    "'\"CURRENT-ACCOUNT\"')::json WHERE id = ?",
            ).use { st ->
                st.setObject(1, revisionId)
                assertThat(st.executeUpdate()).isEqualTo(1)
            }
        }
        backtest(offeringId, revisionId, 409)
        assertThat(evidenceRows(revisionId)).isZero()
    }

    @Test
    @TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = ISSUER),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "u-op"),
            Claim(key = "preferred_username", value = "test-operator"),
        ],
    )
    fun `a product without its own golden portfolio cannot be backtested, failing closed`() {
        // EUR has golden portfolios for other products; there is no currency fallback.
        val offeringId = offering("SAVINGS_NO_GOLDEN")
        val ir = SAVINGS_FIXED_IR.replace("\"SAVINGS-FIXED\"", "\"SAVINGS-NO-GOLDEN\"")
        val revisionId = create(offeringId, revisionBody(ir, "EUR"), 201)!!
        backtest(offeringId, revisionId, 409)
        assertThat(evidenceRows(revisionId)).isZero()
    }

    @Test
    @TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = ISSUER),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "u-op"),
            Claim(key = "preferred_username", value = "test-operator"),
        ],
    )
    fun `a human approver's golden backtest of a bound revision is evidence`() {
        val offeringId = offering("SAVINGS_FIXED")
        val revisionId = create(offeringId, revisionBody(SAVINGS_FIXED_IR, "EUR"), 201)!!
        backtest(offeringId, revisionId, 200)
        assertThat(evidenceRows(revisionId)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = ISSUER),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "u-op"),
            Claim(key = "preferred_username", value = "test-operator"),
        ],
    )
    fun `recorded backtest evidence cannot be updated, deleted or truncated`() {
        val offeringId = offering("SAVINGS_FIXED")
        val revisionId = create(offeringId, revisionBody(SAVINGS_FIXED_IR, "EUR"), 201)!!
        backtest(offeringId, revisionId, 200)
        val statements = listOf(
            "UPDATE catalog_revision_backtests SET identical = NOT identical WHERE revision_id = '$revisionId'",
            "DELETE FROM catalog_revision_backtests WHERE revision_id = '$revisionId'",
            "TRUNCATE catalog_revision_backtests",
        )
        for (sql in statements) {
            dataSource.connection.use { c ->
                c.createStatement().use { st ->
                    assertThat(runCatching { st.execute(sql) }.exceptionOrNull()).describedAs(sql)
                        .hasMessageContaining("immutable")
                }
            }
        }
        assertThat(evidenceRows(revisionId)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = ISSUER),
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "sub", value = "svc-1"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `a machine principal is refused the backtest and records nothing`() {
        val offeringId = offering("SAVINGS_FIXED")
        val revisionId = create(offeringId, revisionBody(SAVINGS_FIXED_IR, "EUR"), 201)!!
        backtest(offeringId, revisionId, 403)
        assertThat(evidenceRows(revisionId)).isZero()
    }

    @Test
    fun `evidence of a row the backtest read is refused once the draft has been edited under it`() {
        val offeringId = offering("SAVINGS_FIXED")
        val revisionId = create(offeringId, revisionBody(SAVINGS_FIXED_IR, "EUR"), 201)!!
        // The backtest's read: the row as it was when the replay started.
        val read = VertxContextSupport.subscribeAndAwait {
            uni(CoroutineScope(Dispatchers.Unconfined)) { catalog.findRevision(revisionId) }
        }
        // An edit lands while the replay runs.
        Given {
            contentType("application/json")
            header("If-Match", "\"0\"")
            body(revisionBody(SAVINGS_FIXED_IR.replace("0.0250000000", "0.0350000000"), "EUR"))
        } When { put("/api/v2/offerings/$offeringId/revisions/$revisionId") } Then { statusCode(200) }
        val evidence = BacktestEvidence(
            approver = ApproverIdentity("test-operator", ISSUER, "u-op"),
            portfolioName = "approval-savings-fixed.json",
            portfolioSha256 = "0".repeat(64),
            identical = true,
            changedAccounts = 0,
            baseRevisionHash = "0".repeat(64),
            candidateRevisionHash = "0".repeat(64),
            canonicalDiff = "{}",
        )
        assertThatThrownBy {
            VertxContextSupport.subscribeAndAwait {
                uni(CoroutineScope(Dispatchers.Unconfined)) { catalog.recordBacktest(read, evidence) }
            }
        }.isInstanceOf(CatalogPreconditionFailedException::class.java)
        assertThat(evidenceRows(revisionId)).isZero()
    }

    private fun backtest(offeringId: UUID, revisionId: UUID, status: Int) {
        Given {
            contentType("application/json")
            body("""{"baseRevisionId":"$revisionId"}""")
        } When {
            post("/api/v2/offerings/$offeringId/revisions/$revisionId/behaviour-diff")
        } Then { statusCode(status) }
    }

    private fun lint(json: String, status: Int) {
        Given {
            contentType("application/json")
            body(json)
        } When { post("/api/v2/product-ir/lint") } Then { statusCode(status) }
    }

    private fun evidenceRows(revisionId: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM catalog_revision_backtests WHERE revision_id = ?").use { st ->
            st.setObject(1, revisionId)
            st.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun create(offeringId: UUID, json: String, status: Int): UUID? = Given {
        contentType("application/json")
        body(json)
    } When { post("/api/v2/offerings/$offeringId/revisions") } Then { statusCode(status) } Extract {
        jsonPath().getString("id")?.takeIf { status == 201 }?.let(UUID::fromString)
    }

    private fun offering(code: String): UUID =
        GoldenOfferings.offering(dataSource, code, "org.openbank.banking.deposit", 2, MARKET)

    private fun revisionBody(ir: String, currency: String): String =
        """{"schemaRef":{"id":"org.openbank.banking.deposit","version":2},"name":{"en":"Bound savings"},""" +
            """"attributes":{"currency":"$currency","productType":"SAVINGS","interest":{"rateType":"FIXED",""" +
            """"dayCount":"ACT_365","payoutFrequency":"MONTHLY","annualRate":"0.0250"}},"behaviour":$ir}"""

    private companion object {
        const val ISSUER = "https://example.invalid/catalog-issuer"
        const val MARKET = """{"countries":["CZ"],"channels":["WEB"],"locales":["cs-CZ"]}"""
        val SAVINGS_FIXED_IR: String =
            Files.readString(Path.of("..", "openbank-products", "ir", "savings-fixed.json")).trimEnd()
        val CURRENT_ACCOUNT_IR: String =
            Files.readString(Path.of("..", "openbank-products", "ir", "current-account.json")).trimEnd()
    }
}
