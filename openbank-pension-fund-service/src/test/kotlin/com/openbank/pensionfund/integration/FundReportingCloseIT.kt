// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.integration

import com.openbank.libs.idempotency.RequestFingerprint
import com.openbank.pensionfund.application.port.StoreChanges
import com.openbank.pensionfund.application.usecase.FundReportingService
import com.openbank.pensionfund.application.usecase.NavService
import com.openbank.pensionfund.domain.model.ForwardPricer
import com.openbank.pensionfund.domain.model.Fund
import com.openbank.pensionfund.domain.model.FundPeriodCalculator
import com.openbank.pensionfund.domain.model.FundStatus
import com.openbank.pensionfund.domain.model.NavFigures
import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
import com.openbank.pensionfund.domain.model.UnitHolding
import com.openbank.pensionfund.domain.model.UnitTransaction
import com.openbank.pensionfund.domain.model.UnitTransactionType
import com.openbank.pensionfund.infrastructure.persistence.FUND_REPORTING_PAYLOAD_SCHEMA_VERSION
import com.openbank.pensionfund.infrastructure.persistence.FundReportingCloseRepository
import com.openbank.pensionfund.infrastructure.persistence.FundReportingSealCommand
import com.openbank.pensionfund.infrastructure.persistence.PanachePensionFundStore
import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.ClaimType
import io.quarkus.test.security.oidc.OidcSecurity
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Real HTTP/Postgres proof of explicit seal, idempotent replay and historical bytes. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(FundReportingCloseIT.ManagementOpenApi::class)
class FundReportingCloseIT {
    class ManagementOpenApi : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.management.enabled" to "true",
            "quarkus.management.test-port" to "18255",
        )
    }

    @Inject lateinit var store: PanachePensionFundStore

    @Inject lateinit var closes: FundReportingCloseRepository

    @Inject lateinit var vertx: Vertx

    @Inject lateinit var navs: NavService

    @Inject lateinit var reporting: FundReportingService

    private fun <T> onVertx(block: suspend () -> T): T {
        val result = CompletableFuture<T>()
        val context = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(context, true)
        val dispatcher = Executor { command -> context.runOnContext { command.run() } }.asCoroutineDispatcher()
        CoroutineScope(dispatcher).launch {
            try {
                result.complete(block())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        return result.get(30, TimeUnit.SECONDS)
    }

    private fun seed(fundId: UUID, navId: UUID, date: LocalDate): NavRecord {
        val at = Instant.parse("2026-09-30T17:00:00Z")
        val nav = NavRecord(
            id = navId, fundId = fundId, valuationDate = date,
            figures = NavFigures(
                grossAssets = BigDecimal("100.00"),
                accruedManagementFee = BigDecimal.ZERO,
                otherLiabilities = BigDecimal.ZERO,
                netAssets = BigDecimal("100.00"),
                unitsOutstanding = BigDecimal("100.000000"),
                navPerUnit = BigDecimal.ONE,
            ),
            status = NavStatus.PUBLISHED, calculatedBy = "maker", calculatedAt = at,
            approvedBy = "checker", publishedAt = at, positionsRecorded = false,
        )
        onVertx {
            store.commit(
                StoreChanges(
                    funds = listOf(
                        Fund(
                            id = fundId, name = "Close fund",
                            isin = "CZ${fundId.toString().replace("-", "").take(9).uppercase()}0",
                            lei = "315700ABCDEF12345678",
                            depositaryReference = "DEP", custodyAccountReference = "CUST-CLOSE", currency = "CZK",
                            riskClass = 3, mandatoryConservative = false, managementFeeRate = BigDecimal.ZERO,
                            launchNavPerUnit = BigDecimal.ONE, status = FundStatus.ACTIVE,
                            createdAt = at, updatedAt = at,
                        ),
                    ),
                    navs = listOf(nav),
                ),
            )
        }
        return nav
    }

    private fun seedRepriceRows(nav: NavRecord): List<UnitTransaction> {
        val types = listOf(
            UnitTransactionType.SUBSCRIBE,
            UnitTransactionType.SWITCH_IN,
            UnitTransactionType.REDEEM,
            UnitTransactionType.SWITCH_OUT,
            UnitTransactionType.FEE,
        )
        val rows = types.map { type ->
            UnitTransaction(
                id = UUID.randomUUID(), orderId = null, contractId = UUID.randomUUID(), fundId = nav.fundId,
                type = type,
                units = BigDecimal(if (type == UnitTransactionType.FEE) "1.000000" else "10.000000"),
                amount = BigDecimal(if (type == UnitTransactionType.FEE) "1.00" else "10.00"),
                navId = nav.id, navPerUnit = nav.navPerUnit, pricedAt = checkNotNull(nav.publishedAt),
            )
        }
        onVertx {
            store.commit(
                StoreChanges(
                    transactions = rows,
                    holdings = rows.map { UnitHolding(it.contractId, nav.fundId, BigDecimal("100.000000")) },
                ),
            )
        }
        return rows
    }

    private fun assertSealDeniedWithoutRows() {
        val fundId = UUID.randomUUID()
        seed(fundId, UUID.randomUUID(), LocalDate.parse("2026-09-30"))
        given().header("Idempotency-Key", "denied-seal")
            .contentType("application/json")
            .body("""{"periodStart":"2026-09-01","periodEnd":"2026-09-30"}""")
            .`when`().post("/api/v1/reporting/funds/$fundId/period-closes")
            .then().statusCode(403)
        assertThat(closeCounts(fundId)).containsExactly(0L, 0L)
    }

    private fun closeCounts(fundId: UUID): List<Long> {
        val cfg = ConfigProvider.getConfig()
        java.sql.DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            return listOf("fund_reporting_closes", "fund_reporting_close_receipts").map { table ->
                connection.prepareStatement("SELECT count(*) FROM $table WHERE fund_id = ?").use { statement ->
                    statement.setObject(1, fundId)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
                }
            }
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.test.invalid"),
            Claim(key = "sub", value = "service-account-openbank-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `operator-role service account cannot seal`() = assertSealDeniedWithoutRows()

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.test.invalid"),
            Claim(key = "sub", value = "service-account-openbank-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `service account cannot replay an existing command receipt`() {
        val fundId = UUID.randomUUID()
        seed(fundId, UUID.randomUUID(), LocalDate.parse("2026-09-30"))
        val start = LocalDate.parse("2026-09-01")
        val end = LocalDate.parse("2026-09-30")
        val actor = "service-account-openbank-services"
        val issuer = "https://issuer.test.invalid"
        val key = "denied-seal"
        val scopedKey = RequestFingerprint.of(
            "POST",
            "fund-period-close",
            "${issuer.length}:$issuer${actor.length}:$actor${key.length}:$key",
        )
        val requestSha = RequestFingerprint.of("POST", "fund-period-close", "$fundId|$start|$end")
        val report = onVertx { reporting.periodReport(fundId, start, end) }
        onVertx {
            closes.seal(
                FundReportingSealCommand(
                    fundId, start, end, report.closingNavId, report.fingerprint,
                    FUND_REPORTING_PAYLOAD_SCHEMA_VERSION, "{}", actor, Instant.now(), scopedKey, requestSha,
                ),
            )
        }
        val before = closeCounts(fundId)
        assertThat(before).containsExactly(1L, 1L)
        given().header("Idempotency-Key", key).contentType("application/json")
            .body("""{"periodStart":"2026-09-01","periodEnd":"2026-09-30"}""")
            .`when`().post("/api/v1/reporting/funds/$fundId/period-closes")
            .then().statusCode(403)
        assertThat(closeCounts(fundId)).isEqualTo(before)
    }

    @Test
    @TestSecurity(user = "masked-client", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.test.invalid"),
            Claim(key = "sub", value = "masked-client"),
            Claim(key = "upn", value = "masked-client"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `masked service-account username cannot seal`() = assertSealDeniedWithoutRows()

    @Test
    @TestSecurity(user = "client-id-only", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.test.invalid"),
            Claim(key = "sub", value = "client-id-only"),
            Claim(key = "preferred_username", value = "operator"),
            Claim(key = "client_id", value = "openbank-services"),
        ],
    )
    fun `client credentials marker cannot seal`() = assertSealDeniedWithoutRows()

    @Test
    @TestSecurity(user = "malformed-username", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.test.invalid"),
            Claim(key = "sub", value = "malformed-username"),
            Claim(key = "preferred_username", value = "true", type = ClaimType.BOOLEAN),
        ],
    )
    fun `token with malformed staff username cannot seal`() = assertSealDeniedWithoutRows()

    @Test
    @TestSecurity(user = "role-without-jwt", roles = ["ROLE_OPERATOR"])
    fun `operator role without a validated JWT cannot seal`() = assertSealDeniedWithoutRows()

    @Test
    @TestSecurity(user = "agent:fund-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.test.invalid"),
            Claim(key = "sub", value = "agent:fund-operator"),
            Claim(key = "preferred_username", value = "fund-operator"),
        ],
    )
    fun `agent with operator role cannot seal`() = assertSealDeniedWithoutRows()

    @Test
    @TestSecurity(user = "reporter", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.test.invalid"),
            Claim(key = "sub", value = "reporter"),
            Claim(key = "preferred_username", value = "reporter"),
        ],
    )
    fun `explicit close replays identical bytes and a correction creates a new revision`() {
        val fundId = UUID.randomUUID()
        val date = LocalDate.parse("2026-09-30")
        val nav = seed(fundId, UUID.randomUUID(), date)
        val path = "/api/v1/reporting/funds/$fundId/period-closes"
        val request = """{"periodStart":"2026-09-01","periodEnd":"2026-09-30"}"""
        val first = given().header("Idempotency-Key", "close-1").contentType("application/json").body(request)
            .`when`().post(path).then().statusCode(201).extract().jsonPath()
        val closeId = first.getString("id")
        val revision = first.getLong("revision")
        val original = given().`when`().get("$path/$closeId").then().statusCode(200).extract().response()
        assertThat(original.header("X-Reporting-Payload-SHA256")).isEqualTo(first.getString("payloadSha256"))
        assertThat(original.header("X-Reporting-Payload-Schema-Version")).isEqualTo("1.3.0")
        assertThat(sha256(original.asString())).isEqualTo(first.getString("payloadSha256"))
        assertThat(original.asString()).contains("\"loansOutstanding\":null")
        val retry = given().header("Idempotency-Key", "close-1").contentType("application/json").body(request)
            .`when`().post(path).then().statusCode(201).extract().jsonPath()
        assertThat(retry.getString("id")).isEqualTo(closeId)
        assertThat(retry.getLong("revision")).isEqualTo(revision)
        given().header("Idempotency-Key", "close-1").contentType("application/json")
            .body("""{"periodStart":"2026-09-02","periodEnd":"2026-09-30"}""")
            .`when`().post(path).then().statusCode(409)

        // Equal source identity with different bytes must conflict, not return a stale close.
        assertThatThrownBy {
            onVertx {
                closes.seal(
                    FundReportingSealCommand(
                        fundId,
                        LocalDate.parse("2026-09-01"),
                        date,
                        nav.id,
                        first.getString("sourceFingerprint"),
                        FUND_REPORTING_PAYLOAD_SCHEMA_VERSION,
                        original.asString() + " ",
                        "reporter",
                        Instant.now(),
                    ),
                )
            }
        }.hasRootCauseInstanceOf(com.openbank.libs.domain.error.ResourceConflictException::class.java)
        assertDatabaseRejectsMutation(UUID.fromString(closeId))

        publishCorrection(nav)
        val lostReplyRetry = given().header("Idempotency-Key", "close-1")
            .contentType("application/json").body(request)
            .`when`().post(path).then().statusCode(201).extract().jsonPath()
        assertThat(lostReplyRetry.getString("id")).isEqualTo(closeId)
        val corrected = given().header("Idempotency-Key", "close-2")
            .contentType("application/json").body(request)
            .`when`().post(path).then().statusCode(201).extract().jsonPath()
        assertThat(corrected.getString("id")).isNotEqualTo(closeId)
        assertThat(corrected.getLong("revision")).isGreaterThan(revision)
        assertThat(given().`when`().get("$path/$closeId").then().statusCode(200).extract().asString())
            .isEqualTo(original.asString())
        given().`when`().get("/api/v1/reporting/funds/${UUID.randomUUID()}/period-closes/$closeId")
            .then().statusCode(404)
    }

    private fun publishCorrection(nav: NavRecord) {
        onVertx {
            store.commit(
                StoreChanges(
                    navs = listOf(
                        nav.supersede(),
                        nav.copy(
                            id = UUID.randomUUID(),
                            correctsNavId = nav.id,
                            figures = nav.figures.copy(
                                grossAssets = BigDecimal("101.00"),
                                netAssets = BigDecimal("101.00"),
                                navPerUnit = BigDecimal("1.01"),
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    @TestSecurity(user = "reporter", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.test.invalid"),
            Claim(key = "sub", value = "reporter"),
            Claim(key = "preferred_username", value = "reporter"),
        ],
    )
    fun `legacy projection allows an earlier NAV but technical seal requires an exact close NAV`() {
        val fundId = UUID.randomUUID()
        seed(fundId, UUID.randomUUID(), LocalDate.parse("2026-09-29"))
        val path = "/api/v1/reporting/funds/$fundId"
        val live = given().queryParam("periodStart", "2026-09-01").queryParam("periodEnd", "2026-09-30")
            .`when`().get("$path/period-figures").then().statusCode(200).extract().jsonPath()
        assertThat(live.getString("closingValuationDate")).isEqualTo("2026-09-29")
        given().header("Idempotency-Key", "strict-seal-only").contentType("application/json")
            .body("""{"periodStart":"2026-09-01","periodEnd":"2026-09-30"}""")
            .`when`().post("$path/period-closes").then().statusCode(409)
    }

    @Test
    @TestSecurity(user = "reporter", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.test.invalid"),
            Claim(key = "sub", value = "reporter"),
            Claim(key = "preferred_username", value = "reporter"),
        ],
    )
    fun `two NAV corrections retain enough pricing invariants to replay the original unit figures`() {
        val fundId = UUID.randomUUID()
        val day = LocalDate.parse("2026-09-30")
        val periodStart = LocalDate.parse("2026-09-01")
        val originalNav = seed(fundId, UUID.randomUUID(), day)
        val originalRows = seedRepriceRows(originalNav)
        val originalReport = onVertx { reporting.periodReport(fundId, periodStart, day) }
        val closePath = "/api/v1/reporting/funds/$fundId/period-closes"
        val closeId = given().header("Idempotency-Key", "multi-correction-replay")
            .contentType("application/json")
            .body("""{"periodStart":"2026-09-01","periodEnd":"2026-09-30"}""")
            .`when`().post(closePath).then().statusCode(201).extract().jsonPath().getString("id")
        val sealed = given().`when`().get("$closePath/$closeId").then().statusCode(200).extract().response()
        val sealedBytes = sealed.asString()
        val sealedSha = sealed.header("X-Reporting-Payload-SHA256")
        assertThat(sha256(sealedBytes)).isEqualTo(sealedSha)

        fun correct(previous: NavRecord, price: String): NavRecord = onVertx {
            val calculated = previous.copy(
                id = UUID.randomUUID(),
                status = NavStatus.CALCULATED,
                correctsNavId = previous.id,
                figures = previous.figures.copy(
                    grossAssets = BigDecimal(price).multiply(BigDecimal("100")),
                    netAssets = BigDecimal(price).multiply(BigDecimal("100")),
                    navPerUnit = BigDecimal(price),
                ),
                approvedBy = null,
                publishedAt = null,
            )
            store.commit(StoreChanges(navs = listOf(calculated)))
            navs.publish(calculated.id, "checker").nav
        }

        val firstCorrection = correct(originalNav, "1.250000")
        val secondCorrection = correct(firstCorrection, "1.500000")
        val latestRows = onVertx { store.transactionsPricedAt(secondCorrection.id) }
        assertThat(latestRows).hasSize(originalRows.size)
        assertThat(latestRows.map { it.id }).containsExactlyInAnyOrderElementsOf(originalRows.map { it.id })
        assertThat(latestRows.map { it.correctedFromNavId }.distinct()).containsExactly(firstCorrection.id)
        val retainedNavs = onVertx { store.navs(fundId) }.associateBy { it.id }
        assertThat(retainedNavs.getValue(secondCorrection.id).correctsNavId).isEqualTo(firstCorrection.id)
        assertThat(retainedNavs.getValue(firstCorrection.id).correctsNavId).isEqualTo(originalNav.id)
        assertThat(retainedNavs.getValue(firstCorrection.id).status).isEqualTo(NavStatus.SUPERSEDED)
        assertThat(retainedNavs.getValue(originalNav.id).status).isEqualTo(NavStatus.SUPERSEDED)

        // The old row was overwritten. Reconstruct its numerical values through the retained NAV
        // chain and the existing pricing rules; this is a test of recoverability, not stored history.
        val retainedOriginal = retainedNavs.getValue(originalNav.id).copy(status = NavStatus.PUBLISHED)
        val reconstructed = latestRows.map { current ->
            val recovered = ForwardPricer.reprice(current, retainedOriginal).transaction
            val original = originalRows.single { it.id == current.id }
            assertThat(recovered.units).isEqualByComparingTo(original.units)
            assertThat(recovered.amount).isEqualByComparingTo(original.amount)
            assertThat(recovered.navPerUnit).isEqualByComparingTo(original.navPerUnit)
            recovered
        }
        val replay = FundPeriodCalculator.calculate(
            onVertx { checkNotNull(store.fund(fundId)) },
            periodStart,
            day,
            listOf(retainedOriginal),
            reconstructed,
            emptyMap(),
        )
        assertThat(replay).isEqualTo(originalReport)
        val live = onVertx { reporting.periodReport(fundId, periodStart, day) }
        assertThat(live.closingNavId).isEqualTo(secondCorrection.id)
        assertThat(live.fingerprint).isNotEqualTo(originalReport.fingerprint)
        val historical = given().`when`().get("$closePath/$closeId").then().statusCode(200).extract().response()
        assertThat(historical.asString()).isEqualTo(sealedBytes)
        assertThat(historical.header("X-Reporting-Payload-SHA256")).isEqualTo(sealedSha)
    }

    @Test
    @TestSecurity(user = "reporter", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.test.invalid"),
            Claim(key = "sub", value = "reporter"),
            Claim(key = "preferred_username", value = "reporter"),
        ],
    )
    fun `same fingerprint with different serialized bytes returns HTTP conflict`() {
        val fundId = UUID.randomUUID()
        val date = LocalDate.parse("2026-09-30")
        val nav = seed(fundId, UUID.randomUUID(), date)
        val path = "/api/v1/reporting/funds/$fundId/period-closes"
        val live = given().queryParam("periodStart", "2026-09-01").queryParam("periodEnd", "2026-09-30")
            .`when`().get("/api/v1/reporting/funds/$fundId/period-figures")
            .then().statusCode(200).extract().jsonPath()
        val existing = onVertx {
            closes.seal(
                FundReportingSealCommand(
                    fundId, LocalDate.parse("2026-09-01"), date, nav.id,
                    live.getString("fingerprint"), FUND_REPORTING_PAYLOAD_SCHEMA_VERSION,
                    "{}", "reporter", Instant.now(),
                ),
            )
        }
        given().header("Idempotency-Key", "close-conflict").contentType("application/json")
            .body("""{"periodStart":"2026-09-01","periodEnd":"2026-09-30"}""")
            .`when`().post(path).then().statusCode(409)
        assertThat(given().`when`().get("$path/${existing.id}").then().statusCode(200).extract().asString())
            .isEqualTo("{}")
    }

    @Test
    @TestSecurity(user = "reporter", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.test.invalid"),
            Claim(key = "sub", value = "reporter"),
            Claim(key = "preferred_username", value = "reporter"),
        ],
    )
    fun `concurrent matching commands commit one receipt and close`() {
        val fundId = UUID.randomUUID()
        seed(fundId, UUID.randomUUID(), LocalDate.parse("2026-09-30"))
        val path = "/api/v1/reporting/funds/$fundId/period-closes"
        val request = """{"periodStart":"2026-09-01","periodEnd":"2026-09-30"}"""
        fun post(): String = given().header("Idempotency-Key", "parallel-1")
            .contentType("application/json").body(request)
            .`when`().post(path).then().statusCode(201).extract().jsonPath().getString("id")
        val first = CompletableFuture.supplyAsync { post() }
        val second = CompletableFuture.supplyAsync { post() }
        assertThat(first.get(60, TimeUnit.SECONDS)).isEqualTo(second.get(60, TimeUnit.SECONDS))
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun assertDatabaseRejectsMutation(closeId: UUID) {
        val mutationFailure = runCatching {
            onVertx {
                Panache.withTransaction {
                    Panache.getSession().flatMap { session ->
                        session.createNativeQuery<Int>(
                            "UPDATE fund_reporting_closes SET payload_json = '{}' WHERE id = :id",
                        )
                            .setParameter("id", closeId).executeUpdate()
                    }
                }.awaitSuspending()
            }
        }.exceptionOrNull()
        assertThat(mutationFailure).isNotNull
        assertThat(mutationFailure!!.stackTraceToString()).contains("fund_reporting_closes is immutable")
    }

    @Test
    fun `management OpenAPI serves the declared additive contract version`() {
        val openApi = given().port(18255).accept("application/yaml")
            .`when`().get("/q/openapi").then().statusCode(200).extract().asString()
        assertThat(openApi).contains("version: 1.3.0")
        assertThat(openApi).contains("/api/v1/reporting/funds/{fundId}/period-closes")
    }
}
