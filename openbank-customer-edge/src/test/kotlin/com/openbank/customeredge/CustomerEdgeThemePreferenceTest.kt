// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.PaymentSessionStore
import com.openbank.customeredge.infrastructure.rest.ThemePreferenceStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * Unit tests for the theme-preference endpoints (ADR-0190): GET/PUT /preferences/theme.
 * Integration tests (endpoint + OIDC) are covered by the @QuarkusTest suite; here we focus
 * on the shape gates (object-only, size cap) and the party-keyed store wiring, which are
 * purely algorithmic. [ThemePreferenceStore] is Redis-backed in production, so it is faked
 * with an in-memory map — the same mockk style [CustomerEdgeResourceTest] uses for
 * [com.openbank.customeredge.infrastructure.rest.UpstreamClient].
 */
class CustomerEdgeThemePreferenceTest {

    private fun fakeStore(backing: MutableMap<UUID, String>): ThemePreferenceStore {
        val store = mockk<ThemePreferenceStore>()
        every { store.get(any()) } answers { backing[firstArg<UUID>()] }
        every { store.put(any(), any()) } answers { backing[firstArg<UUID>()] = secondArg<String>() }
        return store
    }

    private fun resourceFor(store: ThemePreferenceStore, callerParty: UUID): CustomerEdgeResource =
        CustomerEdgeResource(
            mockk(relaxed = true),
            mockk(relaxed = true),
            PaymentSessionStore(),
            mockk(relaxed = true),
            store,
            Clock.systemUTC(),
        ).apply {
            partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
            jwt = mockk {
                every { getClaim<String>("party_id") } returns callerParty.toString()
                every { subject } returns callerParty.toString()
            }
            objectMapper = ObjectMapper()
        }

    // ── GET /preferences/theme ──────────────────────────────────────────────

    @Test
    fun `GET returns 404 when the caller has no stored theme`() {
        val caller = UUID.randomUUID()
        val resp = resourceFor(fakeStore(mutableMapOf()), caller).getThemePreference()
        assertThat(resp.status).isEqualTo(404)
    }

    @Test
    fun `GET is party-keyed — another party's stored theme is not the caller's (no cross-party reads)`() {
        val caller = UUID.randomUUID()
        val other = UUID.randomUUID()
        val backing = mutableMapOf(other to """{"seed":"#0B5FFF"}""")
        val resp = resourceFor(fakeStore(backing), caller).getThemePreference()
        assertThat(resp.status).isEqualTo(404)
    }

    // ── PUT then GET round-trip ─────────────────────────────────────────────

    @Test
    fun `PUT a valid ThemeSpec object returns 204 and GET reads the same JSON back`() {
        val caller = UUID.randomUUID()
        val backing = mutableMapOf<UUID, String>()
        val r = resourceFor(fakeStore(backing), caller)
        val spec = """{"seed":"#0B5FFF","mode":"dark","contrast":1.15}"""

        assertThat(r.setThemePreference(spec).status).isEqualTo(204)

        val resp = r.getThemePreference()
        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.entity.toString()).isEqualTo(spec)
    }

    @Test
    fun `PUT stores under the JWT party — the client can never choose the key`() {
        val caller = UUID.randomUUID()
        val backing = mutableMapOf<UUID, String>()
        val store = fakeStore(backing)

        resourceFor(store, caller).setThemePreference("""{"mode":"light"}""")

        verify(exactly = 1) { store.put(caller, any()) }
        assertThat(backing.keys).containsExactly(caller)
    }

    @Test
    fun `PUT normalises the spec — surrounding whitespace does not survive the round-trip`() {
        val caller = UUID.randomUUID()
        val r = resourceFor(fakeStore(mutableMapOf()), caller)

        assertThat(r.setThemePreference("""  { "mode" : "dark" }  """).status).isEqualTo(204)

        assertThat(r.getThemePreference().entity.toString()).isEqualTo("""{"mode":"dark"}""")
    }

    // ── PUT shape gate: object-only ─────────────────────────────────────────

    @Test
    fun `PUT a JSON array is rejected with 400 and nothing is stored`() {
        val caller = UUID.randomUUID()
        val store = fakeStore(mutableMapOf())
        val resp = resourceFor(store, caller).setThemePreference("""["a","b"]""")
        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { store.put(any(), any()) }
    }

    @Test
    fun `PUT garbage (not JSON at all) is rejected with 400 and nothing is stored`() {
        val caller = UUID.randomUUID()
        val store = fakeStore(mutableMapOf())
        val resp = resourceFor(store, caller).setThemePreference("not json")
        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { store.put(any(), any()) }
    }

    // ── PUT size gate: 8 KiB cap ────────────────────────────────────────────

    @Test
    fun `PUT a body over 8 KiB is rejected with 413 before parsing and nothing is stored`() {
        val caller = UUID.randomUUID()
        val store = fakeStore(mutableMapOf())
        // A well-formed object, but past the cap — the size gate must fire first.
        val oversized = """{"pad":"${"a".repeat(9_000)}"}"""
        val resp = resourceFor(store, caller).setThemePreference(oversized)
        assertThat(resp.status).isEqualTo(413)
        verify(exactly = 0) { store.put(any(), any()) }
    }

    @Test
    fun `PUT a body just under the cap is accepted`() {
        val caller = UUID.randomUUID()
        // Total length 8 KiB exactly ({"pad":"…"} wrapper is 10 chars): cap is strictly greater-than.
        val atCap = """{"pad":"${"a".repeat(8 * 1024 - 10)}"}"""
        val resp = resourceFor(fakeStore(mutableMapOf()), caller).setThemePreference(atCap)
        assertThat(resp.status).isEqualTo(204)
    }
}
