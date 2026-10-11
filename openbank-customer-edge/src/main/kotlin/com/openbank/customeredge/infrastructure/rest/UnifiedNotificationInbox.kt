// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.quarkus.logging.Log
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal const val UNIFIED_NOTIFICATION_TIMEOUT_SECONDS = 10L

/** Merges only edge-authorized party feeds; a failed profile returns no partial inbox. */
internal class UnifiedNotificationInbox(
    private val upstream: UpstreamClient,
    private val mapper: ObjectMapper,
    private val serviceUrl: String,
    private val aggregateTimeoutNanos: Long = TimeUnit.SECONDS.toNanos(UNIFIED_NOTIFICATION_TIMEOUT_SECONDS),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private data class Item(val node: ObjectNode, val createdAt: Instant)
    private data class PartyPage(val items: List<Item>, val total: Long, val unread: Long)

    fun list(parties: List<UUID>, limit: Int): Response {
        // Synthetic taint lives in request-thread MDC/baggage. Keep those reads on the caller
        // thread so the existing UpstreamClient propagates the trusted taint to every service.
        val synthetic = currentRequestIsSynthetic()
        val deadline = nanoTime() + aggregateTimeoutNanos
        val futures = mutableListOf<Future<PartyPage>>()
        return try {
            val pages = if (synthetic) {
                syntheticPages(parties, limit, deadline)
            } else {
                concurrentPages(parties, limit, deadline, futures)
            }
            require(remaining(deadline) > 0) { "Notification aggregate deadline exceeded" }
            val ordered = pages.flatMap { it.items }.sortedWith(
                compareByDescending<Item> { it.createdAt }
                    .thenByDescending { it.node.path("id").asText() },
            ).take(limit).map { it.node }
            val out = mapper.createObjectNode()
            out.set<com.fasterxml.jackson.databind.JsonNode>("items", mapper.valueToTree(ordered))
            out.put("total", pages.sumOf { it.total })
            out.put("unreadCount", pages.sumOf { it.unread })
            out.put("size", limit)
            out.put("page", 0)
            Response.ok(out.toString()).type(MediaType.APPLICATION_JSON).build()
        } catch (e: InterruptedException) {
            futures.forEach { it.cancel(true) }
            Thread.currentThread().interrupt()
            unavailable(e)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            futures.forEach { it.cancel(true) }
            unavailable(e)
        }
    }

    private fun unavailable(e: Exception): Response {
        Log.warn("Unified notification feed unavailable", e)
        return Response.status(Response.Status.BAD_GATEWAY)
            .entity(mapOf("code" to "NOTIFICATIONS_UNAVAILABLE")).build()
    }

    private fun syntheticPages(parties: List<UUID>, limit: Int, deadline: Long): List<PartyPage> =
        parties.map { party ->
            require(remaining(deadline) > 0) { "Notification aggregate deadline exceeded" }
            fetchBounded(party, limit, deadline, true)
        }

    private fun concurrentPages(
        parties: List<UUID>,
        limit: Int,
        deadline: Long,
        futures: MutableList<Future<PartyPage>>,
    ): List<PartyPage> {
        val completion = ExecutorCompletionService<PartyPage>(workers)
        val pending = parties.iterator()
        val pages = mutableListOf<PartyPage>()
        repeat(minOf(parties.size, MAX_CONCURRENT_FEEDS)) {
            submitNext(pending, limit, deadline, futures, completion)
        }
        repeat(parties.size) {
            val completed = completion.poll(remaining(deadline), TimeUnit.NANOSECONDS)
                ?: error("Notification aggregate deadline exceeded")
            pages += completed.get()
            submitNext(pending, limit, deadline, futures, completion)
        }
        return pages
    }

    private fun submitNext(
        pending: Iterator<UUID>,
        limit: Int,
        deadline: Long,
        futures: MutableList<Future<PartyPage>>,
        completion: ExecutorCompletionService<PartyPage>,
    ) {
        if (!pending.hasNext()) return
        val party = pending.next()
        while (true) {
            require(remaining(deadline) > 0) { "Notification aggregate deadline exceeded" }
            try {
                futures += completion.submit { fetchBounded(party, limit, deadline, false) }
                return
            } catch (_: RejectedExecutionException) {
                // A full replica queue waits within the deadline, without moving an upstream
                // read onto the caller thread.
                Thread.sleep(1)
            }
        }
    }

    private fun fetchBounded(party: UUID, limit: Int, deadline: Long, synthetic: Boolean): PartyPage {
        if (!upstreamPermits.tryAcquire(remaining(deadline), TimeUnit.NANOSECONDS)) {
            error("Notification aggregate deadline exceeded")
        }
        return try {
            require(remaining(deadline) > 0) { "Notification aggregate deadline exceeded" }
            fetch(party, limit, deadline, synthetic)
        } finally {
            upstreamPermits.release()
        }
    }

    private fun fetch(party: UUID, limit: Int, deadline: Long, synthetic: Boolean): PartyPage {
        val url = "$serviceUrl/api/v1/notifications?partyId=$party&page=0&size=$limit"
        val response = if (synthetic) {
            // Synthetic taint is thread-local, so these reads stay on the caller. The HTTP
            // timeout shrinks with the remaining aggregate budget to avoid sequential waves.
            upstream.get(url, party.toString(), TimeUnit.NANOSECONDS.toMillis(remaining(deadline)).coerceAtLeast(1))
        } else {
            upstream.get(url, party.toString())
        }
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
            Item(row.deepCopy(), Instant.parse(row.path("createdAt").asText()))
        }
        return PartyPage(items, page.path("total").asLong(), page.path("unreadCount").asLong())
    }

    private fun remaining(deadline: Long): Long = (deadline - nanoTime()).coerceAtLeast(0)

    private companion object {
        const val MAX_CONCURRENT_FEEDS = 16
        const val MAX_QUEUED_FEEDS = 128
        const val HTTP_OK = 200

        // Each request submits at most 16 tasks. A full queue waits within the aggregate
        // deadline rather than running an upstream read on the HTTP request thread.
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
