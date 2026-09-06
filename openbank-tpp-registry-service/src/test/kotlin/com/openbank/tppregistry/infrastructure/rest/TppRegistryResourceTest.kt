// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.libs.idempotency.IdempotencyRecord
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.tppregistry.application.port.`in`.BlacklistTppCommand
import com.openbank.tppregistry.application.port.`in`.CheckTppAuthorizationQuery
import com.openbank.tppregistry.application.port.`in`.GetTppQuery
import com.openbank.tppregistry.application.port.`in`.ListTppsQuery
import com.openbank.tppregistry.application.port.`in`.RegisterTppCommand
import com.openbank.tppregistry.application.port.`in`.TppRegistryUseCase
import com.openbank.tppregistry.domain.model.EbaRegisterSyncState
import com.openbank.tppregistry.domain.model.TppAuthorizationResult
import com.openbank.tppregistry.domain.model.TppEntry
import com.openbank.tppregistry.domain.model.TppRole
import com.openbank.tppregistry.domain.model.TppStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Pure-JVM exercise of the resource's own branching: the required-query-parameter guards, the
 * authorized/denied status split, the idempotency replay short-circuits and the list defaults.
 * No Quarkus — the class is instantiated directly with mocked collaborators.
 */
class TppRegistryResourceTest {

    private val svc = mockk<TppRegistryUseCase>()
    private val store = mockk<IdempotencyStore>(relaxed = true)
    private val mapper = ObjectMapper().registerModule(JavaTimeModule())
    private val resource = TppRegistryResource(svc, store, mapper)

    private val now = OffsetDateTime.of(2026, 3, 4, 5, 6, 7, 0, ZoneOffset.UTC)

    private fun entry(tppId: String = "CZ-CNB-1") = TppEntry(
        id = UUID.fromString("11111111-2222-3333-4444-555555555555"),
        tppId = tppId,
        name = "Acme",
        countryCode = "CZ",
        nca = "CNB",
        roles = setOf(TppRole.AISP),
        status = TppStatus.ACTIVE,
        qwacSubjectDn = null,
        qsealSubjectDn = null,
        qwacExpiresAt = null,
        qsealExpiresAt = null,
        registeredAt = now,
        updatedAt = now,
        blacklistedAt = null,
        blacklistReason = null,
    )

    private fun registerCmd(tppId: String = "CZ-CNB-1") = RegisterTppCommand(
        tppId = tppId,
        name = "Acme",
        countryCode = "CZ",
        nca = "CNB",
        roles = setOf(TppRole.AISP),
        qwacSubjectDn = null,
        qsealSubjectDn = null,
    )

    // --- checkAuthorization -------------------------------------------------

    @Test
    fun `checkAuthorization rejects an absent tppId with IllegalArgumentException`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { resource.checkAuthorization(null, "AISP") } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("query parameter 'tppId' is required")
    }

    @Test
    fun `checkAuthorization rejects an absent role with IllegalArgumentException`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { resource.checkAuthorization("CZ-CNB-1", null) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("query parameter 'role' is required")
    }

    @Test
    fun `checkAuthorization upper-cases the role before parsing it`(): Unit = runBlocking {
        val query = slot<CheckTppAuthorizationQuery>()
        coEvery { svc.checkAuthorization(capture(query)) } returns
            TppAuthorizationResult("CZ-CNB-1", true, setOf(TppRole.AISP), null)

        val response = resource.checkAuthorization("CZ-CNB-1", "aisp")

        assertThat(query.captured.requiredRole).isEqualTo(TppRole.AISP)
        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `checkAuthorization answers 403 with the result body when not authorized`(): Unit = runBlocking {
        val denied = TppAuthorizationResult("CZ-CNB-1", false, emptySet(), "TPP not found in registry")
        coEvery { svc.checkAuthorization(any()) } returns denied

        val response = resource.checkAuthorization("CZ-CNB-1", "AISP")

        assertThat(response.status).isEqualTo(403)
        assertThat(response.entity).isEqualTo(denied)
    }

    @Test
    fun `checkAuthorization surfaces an unknown role as IllegalArgumentException`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { resource.checkAuthorization("CZ-CNB-1", "NOT_A_ROLE") } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // --- registerTpp --------------------------------------------------------

    @Test
    fun `registerTpp answers 201 and caches under the composed idempotency key`(): Unit = runBlocking {
        coEvery { store.get(any()) } returns null
        coEvery { svc.registerTpp(any()) } returns entry()

        val response = resource.registerTpp(registerCmd(), "key-1")

        assertThat(response.status).isEqualTo(201)
        assertThat(response.entity).isEqualTo(entry())
        coVerify { store.get("tpp:register:CZ-CNB-1:key-1") }
        coVerify { store.save("tpp:register:CZ-CNB-1:key-1", 201, any()) }
    }

    @Test
    fun `registerTpp replays the cached response and never calls the use case`(): Unit = runBlocking {
        coEvery { store.get("tpp:register:CZ-CNB-1:key-1") } returns
            IdempotencyRecord("tpp:register:CZ-CNB-1:key-1", 201, "{\"tppId\":\"CZ-CNB-1\"}", now)

        val response = resource.registerTpp(registerCmd(), "key-1")

        assertThat(response.status).isEqualTo(201)
        assertThat(response.entity).isEqualTo("{\"tppId\":\"CZ-CNB-1\"}")
        assertThat(response.getHeaderString("X-Idempotency-Replayed")).isEqualTo("true")
        coVerify(exactly = 0) { svc.registerTpp(any()) }
    }

    @Test
    fun `registerTpp with a blank idempotency key does not touch the store at all`(): Unit = runBlocking {
        coEvery { svc.registerTpp(any()) } returns entry()

        val response = resource.registerTpp(registerCmd(), "   ")

        assertThat(response.status).isEqualTo(201)
        coVerify(exactly = 0) { store.get(any()) }
        coVerify(exactly = 0) { store.save(any(), any(), any(), any()) }
    }

    @Test
    fun `registerTpp with no idempotency key does not touch the store at all`(): Unit = runBlocking {
        coEvery { svc.registerTpp(any()) } returns entry()

        resource.registerTpp(registerCmd(), null)

        coVerify(exactly = 0) { store.get(any()) }
        coVerify(exactly = 0) { store.save(any(), any(), any(), any()) }
    }

    // --- listTpps -----------------------------------------------------------

    @Test
    fun `listTpps defaults the limit to 50 and passes filters through uppercased`(): Unit = runBlocking {
        val query = slot<ListTppsQuery>()
        coEvery { svc.listTpps(capture(query)) } returns listOf(entry())

        val response = resource.listTpps("CZ", "pisp", "revoked", null, "cursor-1")

        assertThat(query.captured.limit).isEqualTo(50)
        assertThat(query.captured.role).isEqualTo(TppRole.PISP)
        assertThat(query.captured.status).isEqualTo(TppStatus.REVOKED)
        assertThat(query.captured.countryCode).isEqualTo("CZ")
        assertThat(query.captured.afterCursor).isEqualTo("cursor-1")
        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `listTpps leaves absent filters null rather than defaulting them`(): Unit = runBlocking {
        val query = slot<ListTppsQuery>()
        coEvery { svc.listTpps(capture(query)) } returns emptyList()

        resource.listTpps(null, null, null, 7, null)

        assertThat(query.captured.role).isNull()
        assertThat(query.captured.status).isNull()
        assertThat(query.captured.countryCode).isNull()
        assertThat(query.captured.limit).isEqualTo(7)
    }

    @Test
    fun `listTpps wraps the entries with a matching count`(): Unit = runBlocking {
        coEvery { svc.listTpps(any()) } returns listOf(entry("a"), entry("b"))

        val response = resource.listTpps(null, null, null, null, null)

        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        assertThat(body["count"]).isEqualTo(2)
        assertThat(body["tpps"] as List<*>).hasSize(2)
    }

    // --- getTpp / blacklist / sync -----------------------------------------

    @Test
    fun `getTpp passes the path parameter through as the query`(): Unit = runBlocking {
        coEvery { svc.getTpp(GetTppQuery("CZ-CNB-9")) } returns entry("CZ-CNB-9")

        val response = resource.getTpp("CZ-CNB-9")

        assertThat(response.status).isEqualTo(200)
        assertThat((response.entity as TppEntry).tppId).isEqualTo("CZ-CNB-9")
    }

    @Test
    fun `blacklistTpp substitutes a default reason when the body omits one`(): Unit = runBlocking {
        val cmd = slot<BlacklistTppCommand>()
        coEvery { svc.blacklistTpp(capture(cmd)) } returns entry()

        val response = resource.blacklistTpp("CZ-CNB-1", emptyMap(), null)

        assertThat(cmd.captured.reason).isEqualTo("No reason provided")
        assertThat(cmd.captured.tppId).isEqualTo("CZ-CNB-1")
        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `blacklistTpp forwards the supplied reason and caches under its own key prefix`(): Unit = runBlocking {
        val cmd = slot<BlacklistTppCommand>()
        coEvery { store.get(any()) } returns null
        coEvery { svc.blacklistTpp(capture(cmd)) } returns entry()

        resource.blacklistTpp("CZ-CNB-1", mapOf("reason" to "licence revoked"), "key-2")

        assertThat(cmd.captured.reason).isEqualTo("licence revoked")
        coVerify { store.get("tpp:blacklist:CZ-CNB-1:key-2") }
        coVerify { store.save("tpp:blacklist:CZ-CNB-1:key-2", 200, any()) }
    }

    @Test
    fun `blacklistTpp replays the cached response and never calls the use case`(): Unit = runBlocking {
        coEvery { store.get("tpp:blacklist:CZ-CNB-1:key-2") } returns
            IdempotencyRecord("tpp:blacklist:CZ-CNB-1:key-2", 200, "{}", now)

        val response = resource.blacklistTpp("CZ-CNB-1", mapOf("reason" to "x"), "key-2")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeaderString("X-Idempotency-Replayed")).isEqualTo("true")
        coVerify(exactly = 0) { svc.blacklistTpp(any()) }
    }

    @Test
    fun `triggerEbaSync caches with a 300 second TTL under the sync key`(): Unit = runBlocking {
        val state = EbaRegisterSyncState(now, null, 0, "not implemented")
        coEvery { store.get(any()) } returns null
        coEvery { svc.triggerEbaSync() } returns state

        val response = resource.triggerEbaSync("key-3")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity).isEqualTo(state)
        coVerify { store.get("tpp:sync:key-3") }
        coVerify { store.save("tpp:sync:key-3", 200, any(), 300) }
    }

    @Test
    fun `triggerEbaSync replays the cached response and never calls the use case`(): Unit = runBlocking {
        coEvery { store.get("tpp:sync:key-3") } returns IdempotencyRecord("tpp:sync:key-3", 200, "{}", now)

        val response = resource.triggerEbaSync("key-3")

        assertThat(response.getHeaderString("X-Idempotency-Replayed")).isEqualTo("true")
        coVerify(exactly = 0) { svc.triggerEbaSync() }
    }

    @Test
    fun `getSyncState returns the state the use case reports`(): Unit = runBlocking {
        val state = EbaRegisterSyncState(null, null, 0, null)
        coEvery { svc.getSyncState() } returns state

        val response = resource.getSyncState()

        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity).isEqualTo(state)
    }
}
