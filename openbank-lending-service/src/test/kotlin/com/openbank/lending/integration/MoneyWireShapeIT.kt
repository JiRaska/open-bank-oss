// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.path.json.JsonPath
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.time.LocalDate
import java.util.UUID

/**
 * The JSON form of `Money` on this service's REST surface, asserted on the bytes of a real response.
 *
 * `LendingResource` returns domain objects, so whatever the shared `ObjectMapper` does with `Money`
 * IS the `/api/v1` wire. Two readers depend on the exact shape: customer-edge projects a loan with
 * `principal.currency.code` (and silently falls back to a default currency when that path is
 * absent), and the admin console adds `amount` values as numbers. This service therefore pins
 * `openbank.json.money-wire-shape: LEGACY_OBJECT`, and this test is what notices if the pin is
 * dropped, renamed or stops reaching the mapper REST uses — none of which a unit test of the
 * serialiser could see.
 *
 * The expectations are literals. A value derived from the serialiser would move with it.
 */
@QuarkusTest
@QuarkusTestResource(MoneyWireShapeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class MoneyWireShapeIT {

    private val schemas: Map<*, *> = run {
        val document = Yaml().load<Map<String, Any>>(javaClass.classLoader.getResourceAsStream("openapi.yaml")!!)
        (document["components"] as Map<*, *>)["schemas"] as Map<*, *>
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("lending-events-out").toMutableMap()
            props["quarkus.kafka.devservices.enabled"] = "false"
            props["openbank.outbox.dispatch-enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    private fun apply(requestedAmount: String): String = Given {
        contentType("application/json")
        body(
            """
            {"partyId":"${UUID.randomUUID()}","requestedAmount":$requestedAmount,
            "nominalAnnualRate":0.05,"termPeriods":12,"firstDueDate":"${LocalDate.now().plusMonths(1)}"}
            """.trimIndent(),
        )
    } When {
        post("/api/v1/lending/applications")
    } Then {
        statusCode(201)
    } Extract {
        body().asString()
    }

    private val legacyTenThousandEur =
        """"requestedAmount":{"amount":10000.00,"currency":{"code":"EUR","defaultFractionDigits":2},""" +
            """"isNonNegative":true,"isZero":false,"isNegative":false,"isPositive":true}"""

    @Test
    @TestSecurity(user = "wire-it-officer", roles = ["ROLE_LENDING_OFFICER"])
    fun `a response carries Money in the shape this API has always written`() {
        val body = apply("""{"amount":"10000.00","currency":{"code":"EUR"}}""")
        assertThat(body).contains(legacyTenThousandEur)
        val amount = JsonPath.from(body).get<Map<String, Any>>("requestedAmount")
        val response = schemas["MoneyResponse"] as Map<*, *>
        val properties = response["properties"] as Map<*, *>
        assertThat(amount["amount"]).isInstanceOf(Number::class.java)
        assertThat(amount["currency"]).isInstanceOf(Map::class.java)
        assertThat((properties["amount"] as Map<*, *>)["type"]).isEqualTo("number")
        assertThat((properties["currency"] as Map<*, *>)["type"]).isEqualTo("object")
        val application = schemas["LoanApplicationResponse"] as Map<*, *>
        val applicationProperties = application["properties"] as Map<*, *>
        assertThat((applicationProperties["requestedAmount"] as Map<*, *>)["\$ref"])
            .isEqualTo("#/components/schemas/MoneyResponse")
    }

    @Test
    @TestSecurity(user = "wire-it-officer", roles = ["ROLE_LENDING_OFFICER"])
    fun `a request may use the documented shape - a plain currency code, a numeric amount, any scale`() {
        // Requests keep the string currency code. Responses use the separate MoneyResponse schema.
        val request = schemas["Money"] as Map<*, *>
        val requestProperties = request["properties"] as Map<*, *>
        assertThat((requestProperties["currency"] as Map<*, *>)["type"]).isEqualTo("string")
        listOf(
            """{"amount":"10000.00","currency":"EUR"}""",
            """{"amount":10000,"currency":"EUR"}""",
            """{"amount":"1E+4","currency":"eur"}""",
        ).forEach { request ->
            assertThat(apply(request)).describedAs(request).contains(legacyTenThousandEur)
        }
    }

    @Test
    @TestSecurity(user = "wire-it-officer", roles = ["ROLE_LENDING_OFFICER"])
    fun `an amount outside the supported range is a client error`() {
        listOf(
            """{"amount":"1E+40","currency":"EUR"}""",
            """{"amount":1E+40,"currency":"EUR"}""",
            """{"amount":"10000.005","currency":"EUR"}""",
            """{"amount":"10000.00","currency":"XAU"}""",
        ).forEach { request ->
            Given {
                contentType("application/json")
                body(
                    """
                    {"partyId":"${UUID.randomUUID()}","requestedAmount":$request,
                    "nominalAnnualRate":0.05,"termPeriods":12,"firstDueDate":"${LocalDate.now().plusMonths(1)}"}
                    """.trimIndent(),
                )
            } When {
                post("/api/v1/lending/applications")
            } Then {
                statusCode(400)
            }
        }
    }
}
