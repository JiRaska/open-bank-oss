// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.reconcile

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.libs.analytics.AggregateKey
import java.net.URI
import java.time.Clock
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Plain-JUnit tests for [HttpReconciliationSource] (ADR-0026). A subclass stubs the HTTP [fetch] seam
 * with canned per-service JSON, so endpoint parsing, fan-out merging and the version/count projections
 * are verified exactly — no server, module stays offline.
 */
class HttpReconciliationSourceTest {

    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    /** Captures requested URIs and returns canned bodies keyed by URI. Token acquisition is disabled. */
    private inner class FakeSource(spec: String, private val bodies: Map<String, String>) : HttpReconciliationSource() {
        val requested = mutableListOf<URI>()
        var fetchCalls = 0

        init {
            endpointsSpec = spec
            mapper = this@HttpReconciliationSourceTest.mapper
            clock = Clock.systemUTC()
        }

        override fun bearerToken(): String? = null

        override suspend fun fetch(uri: URI, authHeader: String?): String {
            fetchCalls++
            requested += uri
            return bodies[uri.toString()] ?: error("no canned body for $uri")
        }
    }

    @Test
    fun `parses endpoint spec skipping blanks and malformed entries`() {
        val source = FakeSource("account=http://acct:8081, , balance=http://bal:8082,broken,trailing=", emptyMap())
        assertThat(source.endpoints()).containsExactlyInAnyOrderEntriesOf(
            mapOf("account" to "http://acct:8081", "balance" to "http://bal:8082"),
        )
    }

    @Test
    fun `currentVersions and rowCountsByType merge across services`(): Unit = runBlocking {
        val accountBody = """
            {"service":"account","generatedAt":"2026-05-30T02:30:00Z",
             "countsByType":{"account":2,"account_pocket":3},
             "aggregates":[
               {"aggregateType":"account","aggregateId":"a1","maxVersion":7},
               {"aggregateType":"account","aggregateId":"a2","maxVersion":2}]}
        """.trimIndent()
        val balanceBody = """
            {"service":"balance","generatedAt":"2026-05-30T02:30:00Z",
             "countsByType":{"balance":1},
             "aggregates":[{"aggregateType":"balance","aggregateId":"b1","maxVersion":5}]}
        """.trimIndent()
        val source = FakeSource(
            "account=http://acct:8081,balance=http://bal:8082/",
            mapOf(
                "http://acct:8081/api/v1/analytics/reconciliation-summary" to accountBody,
                "http://bal:8082/api/v1/analytics/reconciliation-summary" to balanceBody,
            ),
        )

        assertThat(source.currentVersions()).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                AggregateKey("account", "a1") to 7L,
                AggregateKey("account", "a2") to 2L,
                AggregateKey("balance", "b1") to 5L,
            ),
        )
        assertThat(source.rowCountsByType()).containsExactlyInAnyOrderEntriesOf(
            mapOf("account" to 2L, "account_pocket" to 3L, "balance" to 1L),
        )
    }

    @Test
    fun `an unreachable service is excluded, not fatal`(): Unit = runBlocking {
        val good = """
            {"service":"account","generatedAt":"2026-05-30T02:30:00Z",
             "countsByType":{"account":1},
             "aggregates":[{"aggregateType":"account","aggregateId":"a1","maxVersion":1}]}
        """.trimIndent()
        val source = object : HttpReconciliationSource() {
            init {
                endpointsSpec = "account=http://acct:8081,down=http://down:9999"
                mapper = this@HttpReconciliationSourceTest.mapper
                clock = Clock.systemUTC()
            }
            override fun bearerToken(): String? = null
            override suspend fun fetch(uri: URI, authHeader: String?): String {
                if (uri.host == "down") throw IllegalStateException("connection refused")
                return good
            }
        }
        assertThat(source.currentVersions()).containsOnlyKeys(AggregateKey("account", "a1"))
    }

    @Test
    fun `the two port reads coalesce into one fetch per service within a pass`(): Unit = runBlocking {
        val body = """
            {"service":"account","generatedAt":"2026-05-30T02:30:00Z",
             "countsByType":{"account":1},
             "aggregates":[{"aggregateType":"account","aggregateId":"a1","maxVersion":1}]}
        """.trimIndent()
        val source = FakeSource(
            "account=http://acct:8081",
            mapOf("http://acct:8081/api/v1/analytics/reconciliation-summary" to body),
        )
        source.currentVersions()
        source.rowCountsByType()
        assertThat(source.fetchCalls).isEqualTo(1)
    }
}
