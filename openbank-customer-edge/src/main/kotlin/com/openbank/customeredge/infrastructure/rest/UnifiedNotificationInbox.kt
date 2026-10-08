// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.quarkus.logging.Log
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID
import java.util.concurrent.Executors

/** Merges only edge-authorized party feeds; a failed profile returns no partial inbox. */
internal class UnifiedNotificationInbox(
    private val upstream: UpstreamClient,
    private val mapper: ObjectMapper,
    private val serviceUrl: String,
) {
    private data class PartyPage(val items: List<ObjectNode>, val total: Long, val unread: Long)

    fun list(parties: List<UUID>, limit: Int): Response {
        // Synthetic taint lives in request-thread MDC/baggage. Keep those reads on the caller
        // thread so the existing UpstreamClient propagates the trusted taint to every service.
        val workers = if (currentRequestIsSynthetic()) {
            null
        } else {
            Executors.newFixedThreadPool(minOf(MAX_CONCURRENT_FEEDS, parties.size))
        }
        return try {
            val pages = if (workers == null) {
                parties.map { fetch(it, limit) }
            } else {
                parties.map { party -> workers.submit<PartyPage> { fetch(party, limit) } }.map { it.get() }
            }
            val ordered = pages.flatMap { it.items }.sortedWith(
                compareByDescending<ObjectNode> { it.path("createdAt").asText() }
                    .thenByDescending { it.path("id").asText() },
            ).take(limit)
            val out = mapper.createObjectNode()
            out.set<com.fasterxml.jackson.databind.JsonNode>("items", mapper.valueToTree(ordered))
            out.put("total", pages.sumOf { it.total })
            out.put("unreadCount", pages.sumOf { it.unread })
            out.put("size", limit)
            out.put("page", 0)
            Response.ok(out.toString()).type(MediaType.APPLICATION_JSON).build()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.warn("Unified notification feed unavailable", e)
            Response.status(Response.Status.BAD_GATEWAY)
                .entity(mapOf("code" to "NOTIFICATIONS_UNAVAILABLE")).build()
        } finally {
            workers?.shutdownNow()
        }
    }

    private fun fetch(party: UUID, limit: Int): PartyPage {
        val response = upstream.get(
            "$serviceUrl/api/v1/notifications?partyId=$party&page=0&size=$limit",
            party.toString(),
        )
        if (response.status != HTTP_OK) error("Notification feed unavailable")
        val page = mapper.readTree(response.entity as? String ?: error("Notification feed unavailable"))
        val rows = page.path("items")
        if (!rows.isArray || !page.path("total").isNumber || !page.path("unreadCount").isNumber) {
            error("Notification feed unavailable")
        }
        val items = rows.map { row ->
            if (row !is ObjectNode || row.path("partyId").asText() != party.toString()) {
                error("Notification party mismatch")
            }
            row.deepCopy()
        }
        return PartyPage(items, page.path("total").asLong(), page.path("unreadCount").asLong())
    }

    private companion object {
        const val MAX_CONCURRENT_FEEDS = 8
        const val HTTP_OK = 200
    }
}
