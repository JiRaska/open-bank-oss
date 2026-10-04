// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.integration

import com.openbank.standingorder.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * #11938: a `SEPA_CREDIT` standing order executes as an SCT, which is euro-only. A non-EUR one is
 * refused at creation (and at edit, which is a create with `replacesStandingOrderId`) as 400
 * CURRENCY_NOT_ALLOWED — the same wire contract as sepa-payment — and nothing is persisted. On main
 * before this change every refused case here was 201 and an ACTIVE row that failed on every due date.
 * `DOMESTIC` orders keep their rules: CZK stays 201.
 */
@QuarkusTest
@QuarkusTestResource(StandingOrderReplaceIT.NoDispatchResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
class StandingOrderSepaCurrencyIT {

    @Inject
    lateinit var dataSource: DataSource

    @ParameterizedTest(name = "SEPA_CREDIT in {0} -> 400 CURRENCY_NOT_ALLOWED, nothing persisted")
    @ValueSource(strings = ["USD", "CZK", "gbp"])
    fun `a non-EUR SEPA_CREDIT order is refused and nothing is persisted`(currency: String) {
        val party = UUID.randomUUID()

        val refused = post(body(party, "SEPA_CREDIT", currency))

        assertThat(refused.statusCode).describedAs(refused.asString()).isEqualTo(HTTP_BAD_REQUEST)
        val problem: Map<String, Any?> = refused.jsonPath().getMap("")
        val occurrence = problem["correlationId"] as String
        assertThat(occurrence).matches(UUID_RE)
        assertThat(problem["timestamp"] as String).isNotBlank()
        assertThat(problem["instance"]).isEqualTo("urn:openbank:error-occurrence:$occurrence")
        assertThat(problem["traceId"]).isEqualTo(occurrence)
        assertThat(problem - VOLATILE).isEqualTo(EXPECTED_PROBLEM)
        assertThat(refused.asString()).doesNotContain(currency).doesNotContain(currency.uppercase())
        assertThat(rows(party)).isZero()
    }

    @Test
    fun `an edit that turns an order into a non-EUR SEPA_CREDIT is refused and changes nothing`() {
        val party = UUID.randomUUID()
        val original = post(body(party, "SEPA_CREDIT", "EUR"))
        assertThat(original.statusCode).describedAs(original.asString()).isEqualTo(HTTP_CREATED)
        val originalId = original.jsonPath().getString("id")

        val refused = post(body(party, "SEPA_CREDIT", "USD", replaces = originalId))

        assertThat(refused.statusCode).describedAs(refused.asString()).isEqualTo(HTTP_BAD_REQUEST)
        assertThat(refused.jsonPath().getString("code")).isEqualTo("CURRENCY_NOT_ALLOWED")
        assertThat(rows(party)).isEqualTo(1)
        assertThat(rows(party, "ACTIVE")).isEqualTo(1)
    }

    @ParameterizedTest(name = "SEPA_CREDIT in {0} -> 201")
    @ValueSource(strings = ["EUR", "eur"])
    fun `a EUR SEPA_CREDIT order is accepted`(currency: String) {
        val party = UUID.randomUUID()
        val created = post(body(party, "SEPA_CREDIT", currency))
        assertThat(created.statusCode).describedAs(created.asString()).isEqualTo(HTTP_CREATED)
        assertThat(rows(party)).isEqualTo(1)
    }

    @ParameterizedTest(name = "DOMESTIC in {0} -> 201 (unchanged)")
    @ValueSource(strings = ["CZK", "EUR"])
    fun `a DOMESTIC order keeps its current rules`(currency: String) {
        val party = UUID.randomUUID()
        val created = post(body(party, "DOMESTIC", currency))
        assertThat(created.statusCode).describedAs(created.asString()).isEqualTo(HTTP_CREATED)
        assertThat(rows(party)).isEqualTo(1)
    }

    private fun post(json: String): Response =
        RestAssured.given().contentType("application/json").body(json).post("/api/v1/standing-orders")

    private fun body(party: UUID, paymentType: String, currency: String, replaces: String? = null) = """
        {
          "idempotencyKey": "so-eur-${UUID.randomUUID()}",
          "partyId": "$party",
          "debitAccountId": "${UUID.randomUUID()}",
          "debtorIban": "CZ6508000000192000145399",
          "debtorName": "Alice Example",
          "creditorIban": "DE89370400440532013000",
          "creditorName": "Berlin Utility",
          "creditorBic": "COBADEFFXXX",
          "amountMinorUnits": 1000,
          "currency": "$currency",
          "frequency": "MONTHLY",
          "paymentType": "$paymentType",
          "remittanceInfo": null,
          "startDate": "${LocalDate.now().plusDays(1)}",
          "endDate": null${replaces?.let { ",\n  \"replacesStandingOrderId\": \"$it\"" }.orEmpty()}
        }
    """.trimIndent()

    private fun rows(party: UUID, status: String? = null): Int = dataSource.connection.use { c ->
        val sql = "select count(*) from standing_orders where party_id = ?" + (status?.let { " and status = ?" } ?: "")
        c.prepareStatement(sql).use { ps ->
            ps.setObject(1, party)
            status?.let { ps.setString(2, it) }
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private companion object {
        const val HTTP_CREATED = 201
        const val HTTP_BAD_REQUEST = 400
        const val UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        val VOLATILE = setOf("instance", "correlationId", "traceId", "timestamp")
        const val MESSAGE = "SEPA_CREDIT accepts EUR only"
        val EXPECTED_PROBLEM: Map<String, Any?> = mapOf(
            "type" to "urn:openbank:error:currency-not-allowed",
            "title" to "The currency is not allowed by the payment scheme",
            "status" to 400,
            "detail" to "currency: $MESSAGE",
            "code" to "CURRENCY_NOT_ALLOWED",
            "retryable" to false,
            "violations" to listOf(
                mapOf("field" to "currency", "message" to MESSAGE, "code" to "CURRENCY_NOT_ALLOWED"),
            ),
            "message" to "currency: $MESSAGE",
            "details" to listOf(mapOf("field" to "currency", "message" to MESSAGE, "rejectedValue" to null)),
        )
    }
}
