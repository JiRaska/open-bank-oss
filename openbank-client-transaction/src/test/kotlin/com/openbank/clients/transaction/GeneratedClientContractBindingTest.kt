// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clients.transaction

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.clients.transaction.api.TransactionsApi
import com.openbank.clients.transaction.model.TransactionPage
import com.openbank.clients.transaction.model.TransactionPagePagination
import com.openbank.clients.transaction.model.TransactionResponse
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Method

/**
 * ADR-0319 D3: the generated client is bound to the contract by LITERAL paths.
 *
 * The expected routes below are written out, never derived from the generated annotations - a test
 * that read both sides off `@Path` would move with the generator and could not fail (root
 * CLAUDE.md, Pact section, "the asymmetry IS the test"). The second half deserialises every
 * provider response the committed consumer pacts recorded for transaction-service into the
 * generated DTOs, strictly, so a spec change that makes the generated types reject what the
 * provider is known to send fails here, at build time.
 */
class GeneratedClientContractBindingTest {

    private val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        // Strict on purpose: the generated DTOs must cover the recorded bodies field for field.
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    @Test
    fun `generated operations resolve to the literal provider routes`() {
        assertThat(route("initiateTransaction")).isEqualTo("POST /api/v1/transactions")
        assertThat(route("listTransactions")).isEqualTo("GET /api/v1/transactions")
        assertThat(route("reverseTransaction")).isEqualTo("POST /api/v1/transactions/{transactionId}/reverse")
        assertThat(route("getTransaction")).isEqualTo("GET /api/v1/transactions/{transactionId}")
    }

    @Test
    fun `generated operations use the fleet's Mutiny client mode`() {
        assertThat(method("initiateTransaction").returnType).isEqualTo(Uni::class.java)
        assertThat(method("listTransactions").returnType).isEqualTo(Uni::class.java)
        assertThat(method("reverseTransaction").returnType).isEqualTo(Uni::class.java)
    }

    @Test
    fun `generated DTOs accept every create response the consumer pacts recorded`() {
        val bodies = recorded("POST", 201)
        // Eleven recorded create interactions exist today; a large drop means pacts were lost.
        assertThat(bodies.size).isGreaterThanOrEqualTo(MIN_CREATE_PACTS)
        bodies.forEach { (pact, body) ->
            val tx = mapper.treeToValue(body, TransactionResponse::class.java)
            assertThat(tx.status).describedAs("status in %s", pact).isNotBlank()
            assertThat(tx.id).describedAs("id in %s", pact).isNotNull()
        }
    }

    @Test
    fun `generated DTOs accept the list response the mcp-service pact recorded`() {
        val bodies = recorded("GET", 200)
        assertThat(bodies).isNotEmpty
        bodies.forEach { (pact, body) ->
            assertThat(body.path("data").isArray).describedAs("data in %s", pact).isTrue()
            // The envelope is bound strictly. The ROWS are not: that pact's rows carry a `currency` key
            // that is not a transaction-service field (the wire name is `currencyCode`), so a strict
            // bind would test the pact's mistake, not the contract.
            mapper.treeToValue(body.path("pagination"), TransactionPagePagination::class.java)
            val page = mapper.copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .treeToValue(body, TransactionPage::class.java)
            assertThat(page.data).isNotNull
        }
    }

    // A booked 2xx must never turn into a client-side error because the provider grew: unknown
    // properties are ignored (Quarkus' rest-client mapper) and the response-only vocabularies are
    // plain strings in the spec, so a value this client has never seen still binds.
    @Test
    fun `a response with unknown fields and unknown vocabulary values still binds`() {
        val json = """
            {"id": "4f1d2a5e-0f7f-4c37-9a3c-1c7b1a2f9f01", "status": "COMPLETED", "somethingNew": {"a": 1},
             "category": "GROCERIES", "categorySource": "A_SOURCE_ADDED_LATER",
             "merchant": {"cleanName": "Alza.cz", "source": "ENRICHED_V2",
                          "geo": {"lat": 50.1, "lon": 14.4, "precision": "STREET"}}}
        """.trimIndent()
        val lenient = mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

        val tx = lenient.readValue(json, TransactionResponse::class.java)

        assertThat(tx.status).isEqualTo("COMPLETED")
        assertThat(tx.categorySource).isEqualTo("A_SOURCE_ADDED_LATER")
        assertThat(tx.merchant?.geo?.precision).isEqualTo("STREET")
    }

    private fun recorded(httpMethod: String, status: Int): Map<String, JsonNode> {
        val dir = File(System.getProperty("openbank.pacts.dir") ?: error("openbank.pacts.dir not set by the build"))
        val out = linkedMapOf<String, JsonNode>()
        val pacts = dir.listFiles { f -> f.name.endsWith("-openbank-transaction-service.json") }!!
        pacts.sortedBy { it.name }.forEach { f ->
            mapper.readTree(f).path("interactions").forEach { i ->
                val req = i.path("request")
                val res = i.path("response")
                val matches = req.path("method").asText() == httpMethod &&
                    req.path("path").asText() == TRANSACTIONS_PATH
                if (matches && res.path("status").asInt() == status && res.path("body").isObject) {
                    out["${f.name}#${i.path("description").asText()}"] = res.path("body")
                }
            }
        }
        return out
    }

    private fun method(name: String): Method = TransactionsApi::class.java.methods.single { it.name == name }

    private fun route(name: String): String {
        val m = method(name)
        val verb = when {
            m.isAnnotationPresent(GET::class.java) -> "GET"
            m.isAnnotationPresent(POST::class.java) -> "POST"
            else -> error("$name is neither GET nor POST")
        }
        val prefix = TransactionsApi::class.java.getAnnotation(Path::class.java)?.value.orEmpty()
        return "$verb " + prefix + m.getAnnotation(Path::class.java)?.value.orEmpty()
    }

    private companion object {
        const val MIN_CREATE_PACTS = 9
        const val TRANSACTIONS_PATH = "/api/v1/transactions"
    }
}
