// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.libs.web.MDC_SYNTHETIC
import io.quarkus.logging.Log
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.logging.MDC
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal const val UNIFIED_NOTIFICATION_TIMEOUT_SECONDS = 12L

/** Merges only edge-authorized party feeds; a failed profile returns no partial inbox. */
internal class UnifiedNotificationInbox(
    private val upstream: UpstreamClient,
    private val mapper: ObjectMapper,
    private val serviceUrl: String,
    private val aggregateTimeoutNanos: Long = TimeUnit.SECONDS.toNanos(UNIFIED_NOTIFICATION_TIMEOUT_SECONDS),
) {
    private data class PartyPage(val items: List<ObjectNode>, val total: Long, val unread: Long)
    private data class Cursor(val createdAt: Instant, val id: UUID, val profileScope: String) {
        fun encode(): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$createdAt|$id|$profileScope".toByteArray(Charsets.UTF_8))
    }

    fun list(parties: List<UUID>, limit: Int, cursor: String? = null): Response {
        val scope = profileScope(parties)
        val after = decodeCursor(cursor, scope)
        // The request filter is the sole source of synthetic taint. Capture its trusted verdict
        // before worker dispatch, and install only that bit for each worker call.
        val synthetic = currentRequestIsSynthetic()
        return try {
            val deadline = System.nanoTime() + aggregateTimeoutNanos
            val pages = fetchAll(parties, limit, after, synthetic, deadline)
            val ordered = pages.flatMap { it.items }.sortedWith(
                compareByDescending<ObjectNode> { Instant.parse(it.path("createdAt").asText()) }
                    .thenByDescending { it.path("id").asText() },
            ).take(limit)
            check(System.nanoTime() < deadline) { "Notification deadline exceeded" }
            val out = mapper.createObjectNode()
            out.set<com.fasterxml.jackson.databind.JsonNode>("items", mapper.valueToTree(ordered))
            out.put("total", pages.sumOf { it.total })
            out.put("unreadCount", pages.sumOf { it.unread })
            out.put("size", limit)
            out.put("page", 0)
            val last = ordered.lastOrNull()
            if (ordered.size == limit && last != null) {
                val lastAt = Instant.parse(last.path("createdAt").asText())
                val lastId = UUID.fromString(last.path("id").asText())
                out.put("nextCursor", Cursor(lastAt, lastId, scope).encode())
            } else {
                out.putNull("nextCursor")
            }
            Response.ok(out.toString()).type(MediaType.APPLICATION_JSON).build()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.warn("Unified notification feed unavailable", e)
            Response.status(Response.Status.BAD_GATEWAY)
                .entity(mapOf("code" to "NOTIFICATIONS_UNAVAILABLE")).build()
        }
    }

    private fun fetchAll(
        parties: List<UUID>,
        limit: Int,
        after: Cursor?,
        synthetic: Boolean,
        deadline: Long,
    ): List<PartyPage> {
        val completion: CompletionService<PartyPage> = ExecutorCompletionService(workers)
        val pending = mutableListOf<Future<PartyPage>>()
        val pages = mutableListOf<PartyPage>()
        var next = 0
        try {
            while (next < parties.size && pending.size < MAX_CONCURRENT_FEEDS) {
                val party = parties[next++]
                pending += completion.submit { fetchWithTaint(party, limit, after, synthetic, deadline) }
            }
            while (pending.isNotEmpty()) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "Notification deadline exceeded" }
                val done = completion.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: error("Notification deadline exceeded")
                pending.remove(done)
                pages += done.get()
                if (next < parties.size) {
                    val party = parties[next++]
                    pending += completion.submit { fetchWithTaint(party, limit, after, synthetic, deadline) }
                }
            }
            return pages
        } finally {
            pending.forEach { it.cancel(true) }
        }
    }

    private fun fetchWithTaint(party: UUID, limit: Int, after: Cursor?, synthetic: Boolean, deadline: Long): PartyPage {
        val previous = MDC.get(MDC_SYNTHETIC)
        if (synthetic) MDC.put(MDC_SYNTHETIC, "true") else MDC.remove(MDC_SYNTHETIC)
        return try {
            fetchBounded(party, limit, after, deadline)
        } finally {
            if (previous == null) MDC.remove(MDC_SYNTHETIC) else MDC.put(MDC_SYNTHETIC, previous)
        }
    }

    private fun fetchBounded(party: UUID, limit: Int, after: Cursor?, deadline: Long): PartyPage {
        if (!upstreamPermits.tryAcquire(remaining(deadline), TimeUnit.NANOSECONDS)) {
            error("Notification deadline exceeded")
        }
        return try {
            fetch(party, limit, after, deadline)
        } finally {
            upstreamPermits.release()
        }
    }

    private fun fetch(party: UUID, limit: Int, after: Cursor?, deadline: Long): PartyPage {
        val keyset = after?.let { "&beforeCreatedAt=${it.createdAt}&beforeId=${it.id}" } ?: ""
        val timeoutMs = TimeUnit.NANOSECONDS.toMillis(remaining(deadline)).coerceAtLeast(1)
        val response = upstream.get(
            "$serviceUrl/api/v1/notifications?partyId=$party&page=0&size=$limit$keyset",
            party.toString(),
            timeoutMs,
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
            row.deepCopy().also { it.remove("recipient") }
        }
        return PartyPage(items, page.path("total").asLong(), page.path("unreadCount").asLong())
    }

    private fun profileScope(parties: List<UUID>): String {
        val canonical = parties.distinct().sorted().joinToString(",")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun decodeCursor(encoded: String?, expectedScope: String): Cursor? {
        if (encoded == null) return null
        if (encoded.length !in 1..MAX_CURSOR_LENGTH) throw BadRequestException("Invalid notification cursor")
        return try {
            val raw = String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8).split('|')
            require(raw.size == CURSOR_FIELD_COUNT && raw[CURSOR_SCOPE_INDEX] == expectedScope)
            Cursor(Instant.parse(raw[0]), UUID.fromString(raw[1]), raw[2])
        } catch (e: IllegalArgumentException) {
            throw BadRequestException("Invalid notification cursor", e)
        }
    }

    private companion object {
        const val MAX_CONCURRENT_FEEDS = 16
        const val MAX_QUEUED_FEEDS = 128
        const val HTTP_OK = 200
        const val MAX_CURSOR_LENGTH = 220
        const val CURSOR_FIELD_COUNT = 3
        const val CURSOR_SCOPE_INDEX = 2

        fun remaining(deadline: Long): Long = (deadline - System.nanoTime()).coerceAtLeast(0)

        // One bounded pool per replica. Reject saturation so work cannot escape the request's
        // deadline by running on its caller thread. Synthetic requests use this same pool after
        // their trusted taint bit is captured and restored around each worker call.
        val upstreamPermits = Semaphore(MAX_CONCURRENT_FEEDS, true)
        val workers = ThreadPoolExecutor(
            MAX_CONCURRENT_FEEDS,
            MAX_CONCURRENT_FEEDS,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(MAX_QUEUED_FEEDS),
            { task ->
                Thread.ofPlatform().name("unified-notification-feed").daemon(true)
                    .inheritInheritableThreadLocals(false).unstarted(task)
            },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }
}
