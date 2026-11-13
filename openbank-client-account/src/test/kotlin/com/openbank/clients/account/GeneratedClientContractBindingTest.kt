// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clients.account

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.clients.account.api.AccountsApi
import com.openbank.clients.account.model.AccountResponse
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Method

/**
 * ADR-0319 D3: the generated client is bound to the contract by LITERAL paths.
 *
 * The expected routes are written out, never derived from the generated annotations: a test that read
 * both sides off `@Path` would move with the generator and could not fail. The second half deserialises
 * every single-account body the committed consumer pacts recorded for account-service into the generated
 * [AccountResponse], so a spec change that makes the generated type reject what the provider is known to
 * send fails here, at build time.
 */
class GeneratedClientContractBindingTest {

    // Unknown keys tolerated, as under the Quarkus default the consumers run with. Types, enum values and
    // formats of every KNOWN key are still bound strictly.
    private val mapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    @Test
    fun `facade operations resolve to the literal provider routes`() {
        assertThat(route("getAccount")).isEqualTo("GET /api/v1/accounts/{accountId}")
        assertThat(route("getAccountByIban")).isEqualTo("GET /api/v1/accounts/iban/{iban}")
    }

    @Test
    fun `facade operations return a raw Mutiny Response`() {
        assertThat(method("getAccount").returnType).isEqualTo(Uni::class.java)
        assertThat(method("getAccount").genericReturnType.typeName).contains("jakarta.ws.rs.core.Response")
        assertThat(method("getAccountByIban").genericReturnType.typeName).contains("jakarta.ws.rs.core.Response")
    }

    @Test
    fun `generated AccountResponse accepts every single-account body the consumer pacts recorded`() {
        val bodies = recorded()
        // A large drop means pacts were lost.
        assertThat(bodies.size).isGreaterThanOrEqualTo(MIN_SINGLE_ACCOUNT_BODIES)
        bodies.forEach { (pact, body) ->
            val account = mapper.treeToValue(body, AccountResponse::class.java)
            if (body.has("partyId")) {
                assertThat(account.partyId).describedAs("partyId in %s", pact).isNotNull()
            }
        }
    }

    private fun recorded(): Map<String, JsonNode> {
        val dir = File(System.getProperty("openbank.pacts.dir") ?: error("openbank.pacts.dir not set by the build"))
        val out = linkedMapOf<String, JsonNode>()
        val pacts = dir.listFiles { f -> f.name.endsWith("-openbank-account-service.json") }!!
        pacts.sortedBy { it.name }.forEach { f ->
            mapper.readTree(f).path("interactions").forEach { i ->
                val req = i.path("request")
                val res = i.path("response")
                val path = req.path("path").asText()
                val single = SINGLE_BY_ID.matches(path) || SINGLE_BY_IBAN.matches(path)
                val ok = res.path("status").asInt() == HTTP_OK && res.path("body").isObject
                if (req.path("method").asText() == "GET" && single && ok) {
                    out["${f.name}#${i.path("description").asText()}"] = res.path("body")
                }
            }
        }
        return out
    }

    private fun method(name: String): Method = AccountsApi::class.java.methods.single { it.name == name }

    private fun route(name: String): String {
        val m = method(name)
        check(m.isAnnotationPresent(GET::class.java)) { "$name is not GET" }
        val prefix = AccountsApi::class.java.getAnnotation(Path::class.java)?.value.orEmpty()
        return "GET " + prefix + m.getAnnotation(Path::class.java)?.value.orEmpty()
    }

    private companion object {
        const val HTTP_OK = 200
        const val MIN_SINGLE_ACCOUNT_BODIES = 3
        val SINGLE_BY_ID = Regex("^/api/v1/accounts/[0-9a-fA-F-]{36}$")
        val SINGLE_BY_IBAN = Regex("^/api/v1/accounts/iban/[A-Z0-9]+$")
    }
}
