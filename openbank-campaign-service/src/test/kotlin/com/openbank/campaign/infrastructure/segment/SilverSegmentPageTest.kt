// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.segment

import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SegmentRule
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Optional
import java.util.UUID

class SilverSegmentPageTest {
    @Test
    fun `snapshot uses one ordered query and gives the caller bounded batches`(): Unit = runBlocking {
        val ids = (1..501).map { UUID(0L, it.toLong()) }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var queries = 0
        var sql = ""
        server.createContext("/") { exchange ->
            queries++
            sql = exchange.requestBody.bufferedReader().readText()
            val bytes = ids.joinToString("\n") { """{"aggregate_id":"$it"}""" }.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val evaluator = SilverSegmentEvaluator(
                "http://127.0.0.1:${server.address.port}/",
                Optional.empty(),
                Optional.empty(),
                "openbank_analytics",
            )
            val segment = Segment("actives", 1, listOf(SegmentRule.PartyStatusIs("ACTIVE")))
            val batches = mutableListOf<List<UUID>>()

            evaluator.stream(segment) { batches += it }

            assertThat(queries).isEqualTo(1)
            assertThat(sql).contains("SELECT DISTINCT aggregate_id", "ORDER BY aggregate_id FORMAT JSONEachRow")
            assertThat(sql).doesNotContain("LIMIT")
            assertThat(batches.map { it.size }).containsExactly(500, 1)
            assertThat(batches.flatten()).containsExactlyElementsOf(ids)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `preview count stays at the source and accepts quoted ClickHouse UInt64`(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var sql = ""
        server.createContext("/") { exchange ->
            sql = exchange.requestBody.bufferedReader().readText()
            val bytes = """{"cohort_size":"123456789012"}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val evaluator = SilverSegmentEvaluator(
                "http://127.0.0.1:${server.address.port}/",
                Optional.empty(),
                Optional.empty(),
                "openbank_analytics",
            )
            val segment = Segment("actives", 1, listOf(SegmentRule.PartyStatusIs("ACTIVE")))

            assertThat(evaluator.count(segment)).isEqualTo(123456789012L)
            assertThat(sql).contains("count(DISTINCT aggregate_id)")
            assertThat(sql).doesNotContain("SELECT DISTINCT aggregate_id")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `page binds its limit and cursor in the source query`(): Unit = runBlocking {
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val captured = mutableListOf<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            val sql = exchange.requestBody.bufferedReader().readText()
            captured += query to sql
            val body = if (query.contains("param_p_cursor=")) "" else """{"aggregate_id":"$first"}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val evaluator = SilverSegmentEvaluator(
                "http://127.0.0.1:${server.address.port}/",
                Optional.empty(),
                Optional.empty(),
                "openbank_analytics",
            )
            val segment = Segment("actives", 1, listOf(SegmentRule.PartyStatusIs("ACTIVE")))

            assertThat(evaluator.page(segment, null, 1).partyIds).containsExactly(first)
            assertThat(evaluator.page(segment, first, 1).partyIds).isEmpty()
            assertThat(captured).hasSize(2)
            assertThat(captured[0].first).contains("param_p_limit=1")
            assertThat(captured[0].second).contains("ORDER BY aggregate_id LIMIT {p_limit:UInt32}")
            assertThat(captured[1].first).contains("param_p_cursor=")
            assertThat(captured[1].second).contains("aggregate_id > {p_cursor:String}")
        } finally {
            server.stop(0)
        }
    }
}
