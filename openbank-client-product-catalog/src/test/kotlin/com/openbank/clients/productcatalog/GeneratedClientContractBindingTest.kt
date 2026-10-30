// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clients.productcatalog

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.clients.productcatalog.api.CatalogEventsApi
import com.openbank.clients.productcatalog.api.CatalogV2Api
import com.openbank.clients.productcatalog.model.BehaviourDiffRequest
import com.openbank.clients.productcatalog.model.BehaviourDiffResponse
import com.openbank.clients.productcatalog.model.CatalogEventPage
import com.openbank.clients.productcatalog.model.LintProductIrResponse
import com.openbank.clients.productcatalog.model.Offering
import com.openbank.clients.productcatalog.model.ProductRevision
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Method
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0319 D3: the generated client is bound to the contract by LITERAL paths.
 *
 * The expected paths below are written out, never derived from the generated annotations — a
 * test that read both sides off `@Path` would move with the generator and could not fail (root
 * CLAUDE.md, Pact section, "the asymmetry IS the test"). The second half deserialises the
 * provider responses a real consumer pact recorded into the generated DTOs, so a spec change that
 * makes the generated types reject what the provider is known to send fails here, at build time.
 */
class GeneratedClientContractBindingTest {

    private val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        // Strict on purpose: the generated DTOs must cover the recorded bodies field for field.
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    @Test
    fun `generated operations resolve to the literal provider routes`() {
        assertThat(route(CatalogEventsApi::class.java, "listCatalogEvents")).isEqualTo("GET /api/v2/events")
        assertThat(route(CatalogV2Api::class.java, "getRevisionV2")).isEqualTo("GET /api/v2/revisions/{id}")
        assertThat(route(CatalogV2Api::class.java, "getOfferingV2")).isEqualTo("GET /api/v2/offerings/{id}")
    }

    @Test
    fun `generated operations use the fleet's Mutiny client mode`() {
        assertThat(method(CatalogEventsApi::class.java, "listCatalogEvents").returnType).isEqualTo(Uni::class.java)
        assertThat(method(CatalogV2Api::class.java, "getRevisionV2").returnType).isEqualTo(Uni::class.java)
    }

    @Test
    fun `generated DTOs accept every provider response the interest-service pact recorded`() {
        val bodies = interactions("openbank-interest-service-openbank-product-catalog.json")
        assertThat(bodies).containsOnlyKeys("/api/v2/events", REVISION_PATH, OFFERING_PATH)

        val page = mapper.treeToValue(bodies.getValue("/api/v2/events"), CatalogEventPage::class.java)
        assertThat(page.items).hasSize(1)
        assertThat(page.nextCursor).isEqualTo("MQ")

        val revision = mapper.treeToValue(bodies.getValue(REVISION_PATH), ProductRevision::class.java)
        assertThat(revision.state).isEqualTo(ProductRevision.State.PUBLISHED)
        assertThat(revision.content.attributes).containsKey("interest")

        val offering = mapper.treeToValue(bodies.getValue(OFFERING_PATH), Offering::class.java)
        assertThat(offering.specificationId.toString()).isEqualTo("10000000-0000-0000-0000-000000000010")
    }

    @Test
    fun `ADR-0352 Studio DTOs round-trip the provider's wire shape`() {
        val base = UUID.randomUUID()
        val request = BehaviourDiffRequest(
            portfolio = mapOf("accounts" to emptyList<Any>()),
            from = LocalDate.parse("2026-01-01"),
            to = LocalDate.parse("2026-03-31"),
            baseRevisionId = base,
            materialityThreshold = "0.50",
        )
        // Quarkus' default (`quarkus.jackson.write-dates-as-timestamps=false`): ISO dates on the wire.
        val quarkusLike = mapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val wire = quarkusLike.writeValueAsString(request)
        assertThat(wire).contains("\"from\":\"2026-01-01\"", "\"baseRevisionId\":\"$base\"")
        assertThat(quarkusLike.readValue(wire, BehaviourDiffRequest::class.java)).isEqualTo(request)

        val response = mapper.readValue(
            """{"diff":{"schema":"openbank.product-behaviour-diff.v1","valid":true},"canonicalJson":"{}"}""",
            BehaviourDiffResponse::class.java,
        )
        assertThat(response.diff["schema"]).isEqualTo("openbank.product-behaviour-diff.v1")
        assertThat(response.canonicalJson).isEqualTo("{}")

        val lint = mapper.readValue(
            """{"valid":false,"problems":[{"path":"/bogus","message":"unknown key"}],""" +
                """"productCode":null,"irVersion":null,"revisionHash":null,"canonicalIr":null}""",
            LintProductIrResponse::class.java,
        )
        assertThat(lint.valid).isFalse()
        assertThat(lint.problems.single().path).isEqualTo("/bogus")
    }

    private fun interactions(pact: String): Map<String, JsonNode> {
        val dir = System.getProperty("openbank.pacts.dir") ?: error("openbank.pacts.dir not set by the build")
        val root = mapper.readTree(File(dir, pact))
        return root.path("interactions").associate {
            it.path("request").path("path").asText() to
                it.path("response").path("body")
        }
    }

    private fun method(type: Class<*>, name: String): Method = type.methods.single { it.name == name }

    private fun route(type: Class<*>, name: String): String {
        val m = method(type, name)
        assertThat(m.isAnnotationPresent(GET::class.java)).`as`("$name is a GET").isTrue()
        val prefix = type.getAnnotation(Path::class.java)?.value.orEmpty()
        return "GET " + prefix + m.getAnnotation(Path::class.java)?.value.orEmpty()
    }

    private companion object {
        const val REVISION_PATH = "/api/v2/revisions/30000000-0000-0000-0000-000000000010"
        const val OFFERING_PATH = "/api/v2/offerings/20000000-0000-0000-0000-000000000010"
    }
}
