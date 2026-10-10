// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.usecase.LendingService
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
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
import java.time.LocalDate
import java.util.UUID

/** Real HTTP serialization of a schedule row; only the use case is replaced with a deterministic fixture. */
@QuarkusTest
@QuarkusTestResource(
    value = com.openbank.libs.testing.containers.PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class InstallmentMoneyWireIT {
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
}
