// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.integration

import com.openbank.pensionfund.application.port.StoreChanges
import com.openbank.pensionfund.domain.model.Fund
import com.openbank.pensionfund.domain.model.FundStatus
import com.openbank.pensionfund.domain.model.NavFigures
import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
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

    @Test
    @TestSecurity(user = "reporter", roles = ["ROLE_OPERATOR"])
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
