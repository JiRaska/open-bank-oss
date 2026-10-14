// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.mockk.mockk
import io.quarkus.security.identity.AuthenticationRequestContext
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.SecurityIdentityAugmentor
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.enterprise.context.ApplicationScoped
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
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
@TestProfile(MoneyWireShapeIT.IntakeEnabledProfile::class)
@QuarkusTestResource(MoneyWireShapeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class MoneyWireShapeIT {

    private val mapper = ObjectMapper()
    private val api = Yaml().load<Map<String, Any>>(javaClass.classLoader.getResourceAsStream("openapi.yaml")!!)
    private val schemas = (api["components"] as Map<*, *>)["schemas"] as Map<*, *>

    class IntakeEnabledProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "lending.intake.enabled" to "true",
            "lending.intake.caller-principal" to "service-account-openbank-customer-edge",
            "lending.intake.nominal-annual-rate" to "0.05",
        )
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

    private fun assertMoneyResponse(value: JsonNode, currency: String = "EUR") {
        val schema = schemas["MoneyResponse"] as Map<*, *>
        assertObjectMatchesSchema(value, schema)
        assertThat(value.path("amount").decimalValue()).isEqualByComparingTo("10000.00")
        assertThat(value.path("currency").path("code").asText()).isEqualTo(currency)
        assertThat(value.path("currency").path("defaultFractionDigits").asInt()).isEqualTo(2)
        assertThat(value.path("isNonNegative").asBoolean()).isTrue()
        assertThat(value.path("isZero").asBoolean()).isFalse()
        assertThat(value.path("isNegative").asBoolean()).isFalse()
        assertThat(value.path("isPositive").asBoolean()).isTrue()
    }

    private fun assertObjectMatchesSchema(value: JsonNode, schema: Map<*, *>) {
        assertThat(value.isObject).isTrue()
        val required = schema["required"] as List<*>
        required.forEach { name -> assertThat(value.has(name as String)).describedAs("required field $name").isTrue() }
        val properties = schema["properties"] as Map<*, *>
        properties.forEach { (name, rawProperty) ->
            val field = value.path(name as String)
            if (!field.isMissingNode) {
                val property = rawProperty as Map<*, *>
                when (property["type"]) {
                    "number" -> assertThat(field.isNumber).describedAs(name).isTrue()
                    "integer" -> assertThat(field.isIntegralNumber).describedAs(name).isTrue()
                    "string" -> assertThat(field.isTextual).describedAs(name).isTrue()
                    "boolean" -> assertThat(field.isBoolean).describedAs(name).isTrue()
                    "object" -> assertObjectMatchesSchema(field, property)
                    else -> error("unhandled MoneyResponse property type: ${property["type"]}")
                }
            }
        }
    }

    @Test
    @TestSecurity(user = "wire-it-officer", roles = ["ROLE_LENDING_OFFICER"])
    fun `post get and list return Money matching the complete response schema`() {
        val body = apply("""{"amount":"10000.00","currency":{"code":"EUR"}}""")
        val created = mapper.readTree(body)
        assertMoneyResponse(created.path("requestedAmount"))
        val id = created.path("id").asText()
        val partyId = created.path("partyId").asText()
        val fetched = Given { accept("application/json") } When { get("/api/v1/lending/applications/$id") } Then {
            statusCode(200)
        } Extract { body().asString() }
        assertMoneyResponse(mapper.readTree(fetched).path("requestedAmount"))
        val listed = Given { queryParam("partyId", partyId) } When {
            get("/api/v1/lending/applications")
        } Then { statusCode(200) } Extract { body().asString() }
        assertMoneyResponse(mapper.readTree(listed).first().path("requestedAmount"))
        val application = schemas["LoanApplicationResponse"] as Map<*, *>
        val applicationProperties = application["properties"] as Map<*, *>
        assertThat((applicationProperties["requestedAmount"] as Map<*, *>)["\$ref"])
            .isEqualTo("#/components/schemas/MoneyResponse")
    }

    @Test
    @TestSecurity(
        user = "service-account-openbank-customer-edge",
        roles = ["ROLE_OPERATOR"],
        augmentors = [IntakeJwtIdentityAugmentor::class],
        attributes = [SecurityAttribute(key = "money-wire-intake-fixture", value = "synthetic-jwt-only")],
    )
    fun `customer intake returns the same documented application Money response`() {
        val body = Given {
            contentType("application/json")
            header("X-Customer-Party-Id", UUID.randomUUID().toString())
            body("""{"amount":10000.00,"termMonths":12}""")
        } When {
            post("/api/v1/lending/intake/applications")
        } Then {
            statusCode(201)
        } Extract {
            body().asString()
        }
        assertMoneyResponse(mapper.readTree(body).path("requestedAmount"), currency = "CZK")
        val paths = api["paths"] as Map<*, *>
        val intake = paths["/api/v1/lending/intake/applications"] as Map<*, *>
        val post = intake["post"] as Map<*, *>
        val responses = post["responses"] as Map<*, *>
        val created = responses["201"] as Map<*, *>
        val content = created["content"] as Map<*, *>
        val json = content["application/json"] as Map<*, *>
        assertThat((json["schema"] as Map<*, *>)["\$ref"])
            .isEqualTo("#/components/schemas/LoanApplicationResponse")
    }

    @Test
    @TestSecurity(user = "wire-it-officer", roles = ["ROLE_LENDING_OFFICER"])
    fun `the request schema stays unchanged and the reader retains compatible input forms`() {
        // The published request stays numeric with an ISO string; the reader remains tolerant.
        val request = schemas["Money"] as Map<*, *>
        val requestProperties = request["properties"] as Map<*, *>
        assertThat((requestProperties["amount"] as Map<*, *>)["type"]).isEqualTo("number")
        assertThat((requestProperties["currency"] as Map<*, *>)["type"]).isEqualTo("string")
        listOf(
            """{"amount":"10000.00","currency":"EUR"}""",
            """{"amount":10000,"currency":"EUR"}""",
            """{"amount":"1E+4","currency":"eur"}""",
            """{"amount":"10000.00","currency":{"code":"EUR"}}""",
        ).forEach { request ->
            assertMoneyResponse(mapper.readTree(apply(request)).path("requestedAmount"))
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

    @Test
    fun `intake JWT fixture cannot replace authenticated JWT anonymous or unmarked identities`() {
        val augmentor = IntakeJwtIdentityAugmentor()
        val context = mockk<AuthenticationRequestContext>()
        val marker = "money-wire-intake-fixture"
        val markerValue = "synthetic-jwt-only"
        val name = "service-account-openbank-customer-edge"
        val anonymous = QuarkusSecurityIdentity.builder().setAnonymous(true).addAttribute(marker, markerValue).build()
        val unmarked = QuarkusSecurityIdentity.builder().setPrincipal { name }.build()
        val existingJwt = object : JsonWebToken {
            override fun getName(): String = name
            override fun getClaimNames(): Set<String> = setOf("sub")

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any?> getClaim(claimName: String): T = "existing-subject" as T
        }
        val authenticated = QuarkusSecurityIdentity.builder().setPrincipal(existingJwt)
            .addAttribute(marker, markerValue).build()
        listOf(anonymous, unmarked, authenticated).forEach { identity ->
            assertThat(augmentor.augment(identity, context).await().indefinitely()).isSameAs(identity)
        }
        val synthetic = QuarkusSecurityIdentity.builder().setPrincipal { name }
            .addRole("ROLE_OPERATOR").addAttribute(marker, markerValue).build()
        val result = augmentor.augment(synthetic, context).await().indefinitely()
        assertThat(result.roles).containsExactly("ROLE_OPERATOR")
        val principal = result.principal as JsonWebToken
        assertThat(principal.subject).isEqualTo("wire-it-customer-edge-subject")
        assertThat(principal.getClaim<String>("azp")).isEqualTo("openbank-customer-edge")
        assertThat(principal.getClaim<String>("preferred_username")).isEqualTo(name)
    }
}

/** Supplies only the synthetic intake test identity; production authentication is unchanged. */
@ApplicationScoped
class IntakeJwtIdentityAugmentor : SecurityIdentityAugmentor {
    override fun augment(identity: SecurityIdentity, context: AuthenticationRequestContext): Uni<SecurityIdentity> {
        if (identity.isAnonymous || identity.principal is JsonWebToken) {
            return Uni.createFrom().item(identity)
        }
        if (identity.getAttribute<String>("money-wire-intake-fixture") != "synthetic-jwt-only" ||
            identity.principal.name != "service-account-openbank-customer-edge"
        ) {
            return Uni.createFrom().item(identity)
        }
        val claims = mapOf(
            "sub" to "wire-it-customer-edge-subject",
            "azp" to "openbank-customer-edge",
            "preferred_username" to "service-account-openbank-customer-edge",
        )
        val jwt = object : JsonWebToken {
            override fun getName(): String = claims.getValue("preferred_username")
            override fun getClaimNames(): Set<String> = claims.keys

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any?> getClaim(claimName: String): T = claims[claimName] as T
        }
        return Uni.createFrom().item(QuarkusSecurityIdentity.builder(identity).setPrincipal(jwt).build())
    }
}
