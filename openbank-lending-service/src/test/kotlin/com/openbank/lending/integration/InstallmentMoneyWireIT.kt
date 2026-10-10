// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.usecase.LendingService
import com.openbank.lending.application.usecase.TerminationService
import com.openbank.lending.domain.model.Collateral
import com.openbank.lending.domain.model.CollateralType
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.lending.SettlementQuote
import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Real HTTP serialization of a schedule row; only the use case is replaced with a deterministic fixture. */
@QuarkusTest
@QuarkusTestResource(
    value = com.openbank.libs.testing.containers.PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class InstallmentMoneyWireIT {
    private val mapper = ObjectMapper()
    private val api = Yaml().load<Map<String, Any>>(javaClass.classLoader.getResourceAsStream("openapi.yaml")!!)
    private val paths = api["paths"] as Map<*, *>
    private val schemas = (api["components"] as Map<*, *>)["schemas"] as Map<*, *>

    private fun responseSchema(path: String, method: String, status: String): Map<*, *> {
        val operation = (paths[path] as Map<*, *>)[method] as Map<*, *>
        val response = (operation["responses"] as Map<*, *>)[status] as Map<*, *>
        return ((response["content"] as Map<*, *>)["application/json"] as Map<*, *>)["schema"] as Map<*, *>
    }

    private fun assertMoney(value: com.fasterxml.jackson.databind.JsonNode, field: String) {
        val money = value.path(field)
        val required = (schemas["MoneyResponse"] as Map<*, *>)["required"] as List<*>
        required.forEach { assertThat(money.has(it as String)).describedAs("$field.$it").isTrue() }
        assertThat(money.path("amount").isNumber).describedAs(field).isTrue()
        assertThat(money.path("currency").path("code").asText()).isEqualTo("EUR")
        assertThat(money.path("currency").path("defaultFractionDigits").asInt()).isEqualTo(2)
    }

    @Test
    @TestSecurity(user = "wire-it-officer", roles = ["ROLE_LENDING_OFFICER"])
    fun `schedule Money fields match the published installment schema`() {
        val id = LoanId(UUID.randomUUID())
        val amount = Money.of("100.00", "EUR")
        val installment = LoanInstallment(
            loanId = id,
            number = 1,
            dueDate = LocalDate.of(2026, 11, 1),
            openingBalance = amount,
            principal = amount,
            interest = amount,
            payment = amount,
            closingBalance = amount,
        )
        val servicing = mockk<LendingService>()
        every { servicing.getSchedule(id) } returns Uni.createFrom().item(listOf(installment))
        QuarkusMock.installMockForType(servicing, LendingService::class.java)

        val response = given().`when`().get("/api/v1/lending/loans/${id.value}/schedule").then()
            .statusCode(200).extract().body().asString()
        val row = ObjectMapper().readTree(response).single()
        val document = Yaml().load<Map<String, Any>>(javaClass.classLoader.getResourceAsStream("openapi.yaml")!!)
        val paths = document["paths"] as Map<*, *>
        val schedule = paths["/api/v1/lending/loans/{id}/schedule"] as Map<*, *>
        val get = schedule["get"] as Map<*, *>
        val responses = get["responses"] as Map<*, *>
        val ok = responses["200"] as Map<*, *>
        val content = ok["content"] as Map<*, *>
        val json = content["application/json"] as Map<*, *>
        val items = (json["schema"] as Map<*, *>)["items"] as Map<*, *>
        assertThat(items["\$ref"]).isEqualTo("#/components/schemas/LoanInstallment")
        val schemas = (document["components"] as Map<*, *>)["schemas"] as Map<*, *>
        val properties = (schemas["LoanInstallment"] as Map<*, *>)["properties"] as Map<*, *>
        val required = (schemas["LoanInstallment"] as Map<*, *>)["required"] as List<*>
        val moneyRequired = (schemas["MoneyResponse"] as Map<*, *>)["required"] as List<*>
        required.forEach { assertThat(row.has(it as String)).describedAs("required $it").isTrue() }
        listOf("openingBalance", "principal", "interest", "payment", "closingBalance").forEach { field ->
            assertThat((properties[field] as Map<*, *>)["\$ref"]).isEqualTo("#/components/schemas/MoneyResponse")
            val money = row.path(field)
            moneyRequired.forEach { assertThat(money.has(it as String)).describedAs("$field.$it").isTrue() }
            assertThat(money.path("amount").isNumber).describedAs(field).isTrue()
            assertThat(money.path("currency").path("code").asText()).describedAs(field).isEqualTo("EUR")
            assertThat(money.path("currency").path("defaultFractionDigits").asInt()).isEqualTo(2)
            listOf("isNonNegative", "isZero", "isNegative", "isPositive").forEach { predicate ->
                assertThat(money.path(predicate).isBoolean).describedAs("$field.$predicate").isTrue()
            }
        }
        val repay = paths["/api/v1/lending/loans/{id}/installments/{installmentId}/repay"] as Map<*, *>
        val repayResponse = ((repay["post"] as Map<*, *>)["responses"] as Map<*, *>)["200"] as Map<*, *>
        val repayContent = repayResponse["content"] as Map<*, *>
        val repaySchema = (repayContent["application/json"] as Map<*, *>)["schema"] as Map<*, *>
        assertThat(repaySchema["\$ref"]).isEqualTo("#/components/schemas/LoanInstallment")
    }

    @Test
    @TestSecurity(user = "wire-it-officer", roles = ["ROLE_LENDING_OFFICER"])
    fun `settlement quote serializes five Money fields declared by its response schema`() {
        val id = LoanId(UUID.randomUUID())
        val amount = Money.of("100.00", "EUR")
        val quote = SettlementQuote(
            LocalDate.of(2026, 10, 10),
            LocalDate.of(2026, 11, 10),
            amount,
            amount,
            amount,
            amount,
            amount,
            false,
        )
        val termination = mockk<TerminationService>()
        every { termination.requestSettlementQuote(id, any()) } returns Uni.createFrom().item(quote)
        QuarkusMock.installMockForType(termination, TerminationService::class.java)
        val body = given().contentType("application/json")
            .post("/api/v1/lending/loans/${id.value}/settlement-quote").then()
            .statusCode(201).extract().body().asString()
        val value = mapper.readTree(body)
        val schema = schemas["SettlementQuote"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>
        (schema["required"] as List<*>).forEach {
            assertThat(value.has(it as String)).describedAs("required $it").isTrue()
        }
        listOf("outstandingPrincipal", "accruedInterest", "compensation", "unappliedCredit", "total").forEach { field ->
            assertThat((properties[field] as Map<*, *>)["\$ref"]).isEqualTo("#/components/schemas/MoneyResponse")
            assertMoney(value, field)
        }
        assertThat(responseSchema("/api/v1/lending/loans/{id}/settlement-quote", "post", "201")["\$ref"])
            .isEqualTo("#/components/schemas/SettlementQuote")
    }

    @Test
    @TestSecurity(user = "wire-it-officer", roles = ["ROLE_LENDING_OFFICER"])
    fun `collateral list serializes Money and its three routes declare the same response schema`() {
        val id = LoanId(UUID.randomUUID())
        val collateral = Collateral(
            loanId = id,
            type = CollateralType.REAL_ESTATE,
            marketValue = Money.of("100.00", "EUR"),
            valuedAt = OffsetDateTime.parse("2026-10-10T12:00:00Z"),
            registeredBy = "wire-it-officer",
            createdAt = OffsetDateTime.parse("2026-10-10T12:00:00Z"),
        )
        val lending = mockk<LendingService>()
        every { lending.list(id) } returns Uni.createFrom().item(listOf(collateral))
        QuarkusMock.installMockForType(lending, LendingService::class.java)
        val body = given().get("/api/v1/lending/loans/${id.value}/collateral").then()
            .statusCode(200).extract().body().asString()
        val value = mapper.readTree(body).single()
        val schema = schemas["Collateral"] as Map<*, *>
        (schema["required"] as List<*>).forEach {
            assertThat(value.has(it as String)).describedAs("required $it").isTrue()
        }
        assertThat(((schema["properties"] as Map<*, *>)["marketValue"] as Map<*, *>)["\$ref"])
            .isEqualTo("#/components/schemas/MoneyResponse")
        assertMoney(value, "marketValue")
        val listed = responseSchema("/api/v1/lending/loans/{id}/collateral", "get", "200")
        assertThat((listed["items"] as Map<*, *>)["\$ref"]).isEqualTo("#/components/schemas/Collateral")
        assertThat(responseSchema("/api/v1/lending/loans/{id}/collateral", "post", "201")["\$ref"])
            .isEqualTo("#/components/schemas/Collateral")
        assertThat(responseSchema("/api/v1/lending/collateral/{id}/decision", "post", "200")["\$ref"])
            .isEqualTo("#/components/schemas/Collateral")
    }

    @Test
    @TestSecurity(user = "wire-it-officer", roles = ["ROLE_CREDIT_RISK"])
    fun `loan transition serializes principal Money and lifecycle routes declare Loan`() {
        val id = LoanId(UUID.randomUUID())
        val now = OffsetDateTime.parse("2026-10-10T12:00:00Z")
        val loan = Loan(
            id = id,
            applicationId = LoanApplicationId.random(),
            partyId = UUID.randomUUID(),
            principal = Money.of("100.00", "EUR"),
            nominalAnnualRate = BigDecimal("0.05"),
            termPeriods = 12,
            method = AmortizationMethod.ANNUITY,
            firstDueDate = LocalDate.of(2026, 11, 1),
            disbursedAt = now,
            createdAt = now,
        )
        val termination = mockk<TerminationService>()
        every { termination.markDelinquent(id, any()) } returns Uni.createFrom().item(loan)
        QuarkusMock.installMockForType(termination, TerminationService::class.java)
        val body = given().contentType("application/json")
            .post("/api/v1/lending/loans/${id.value}/mark-delinquent").then()
            .statusCode(200).extract().body().asString()
        val value = mapper.readTree(body)
        assertMoney(value, "principal")
        assertThat(((schemas["Loan"] as Map<*, *>)["properties"] as Map<*, *>)["principal"])
            .isEqualTo(mapOf("\$ref" to "#/components/schemas/MoneyResponse"))
        listOf(
            "/settle", "/withdrawal", "/mark-delinquent", "/mark-defaulted", "/forbearance",
            "/termination/propose", "/termination/decide", "/accelerate", "/writeoff", "/reschedule",
        ).forEach { suffix ->
            assertThat(responseSchema("/api/v1/lending/loans/{id}$suffix", "post", "200")["\$ref"])
                .describedAs(suffix).isEqualTo("#/components/schemas/Loan")
        }
        listOf("/advance", "/decision").forEach { suffix ->
            assertThat(responseSchema("/api/v1/lending/applications/{id}$suffix", "post", "200")["\$ref"])
                .describedAs(suffix).isEqualTo("#/components/schemas/LoanApplicationResponse")
        }
    }
}
