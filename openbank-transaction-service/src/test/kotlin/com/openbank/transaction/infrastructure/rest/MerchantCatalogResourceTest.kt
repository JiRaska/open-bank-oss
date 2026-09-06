// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.transaction.infrastructure.persistence.entity.MerchantCatalogEntity
import com.openbank.transaction.infrastructure.persistence.repository.MerchantCatalogRepository
import com.openbank.transaction.infrastructure.persistence.repository.TransactionDescriptorRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The catalogue is only useful if what an operator writes is what the lookup later reads, and if
 * the worklist points at merchants that are actually missing. Both are asserted here against the
 * REAL `MerchantDescriptor.normalise` — mocking it would test the mock.
 */
class MerchantCatalogResourceTest {

    private lateinit var catalog: MerchantCatalogRepository
    private lateinit var transactions: TransactionDescriptorRepository
    private lateinit var resource: MerchantCatalogResource

    @BeforeEach
    fun setUp() {
        catalog = mockk()
        transactions = mockk()
        resource = MerchantCatalogResource(catalog, transactions)
        coEvery { catalog.upsert(any()) } returns true
        coEvery { catalog.findByKey(any()) } returns null
        coEvery { catalog.deleteByKey(any()) } returns true
    }

    private fun entity(key: String) = MerchantCatalogEntity().also {
        it.descriptorKey = key
        it.cleanName = "x"
        it.updatedAt = Instant.parse("2026-09-05T10:00:00Z")
    }

    /**
     * The load-bearing property. An operator pastes what they see on a statement line; the lookup
     * queries by the NORMALISED key. Writing the raw string would create a row nothing ever hits —
     * a catalogue that looks maintained and enriches nothing.
     */
    @Test
    fun `a raw acquirer descriptor is normalised before it is written`() {
        val saved = slot<MerchantCatalogEntity>()
        coEvery { catalog.upsert(capture(saved)) } returns true

        runBlocking { resource.upsert("ALZA.CZ A.S. PRAHA 4", MerchantUpsertRequest(cleanName = "Alza.cz")) }

        assertThat(saved.captured.descriptorKey).isEqualTo("ALZACZ")
    }

    @Test
    fun `deleting also normalises, so the same paste removes the row it created`() {
        runBlocking { resource.delete("  alza.cz a.s. praha 4 ") }

        coVerify { catalog.deleteByKey("ALZACZ") }
    }

    @Test
    fun `a key that normalises to nothing is refused, not written`() {
        val response = runBlocking { resource.upsert("  ...  ", MerchantUpsertRequest(cleanName = "x")) }

        assertThat(response.status).isEqualTo(400)
        coVerify(exactly = 0) { catalog.upsert(any()) }
    }

    @Test
    fun `a blank trading name is refused`() {
        val response = runBlocking { resource.upsert("BILLA", MerchantUpsertRequest(cleanName = "   ")) }

        assertThat(response.status).isEqualTo(400)
        coVerify(exactly = 0) { catalog.upsert(any()) }
    }

    /**
     * Both coordinates or neither. The column constraint says so; refusing here means the operator
     * is told why instead of getting a 500, and a half-pair can never become a pin at latitude 0.
     */
    @Test
    fun `half a coordinate pair is refused in either direction`() {
        listOf(
            MerchantUpsertRequest(cleanName = "x", lat = 50.0, lon = null),
            MerchantUpsertRequest(cleanName = "x", lat = null, lon = 14.0),
        ).forEach {
            assertThat(runBlocking { resource.upsert("BILLA", it) }.status).isEqualTo(400)
        }
        coVerify(exactly = 0) { catalog.upsert(any()) }
    }

    @Test
    fun `create answers 201 and replace answers 200`() {
        coEvery { catalog.upsert(any()) } returns true
        assertThat(runBlocking { resource.upsert("BILLA", MerchantUpsertRequest(cleanName = "Billa")) }.status)
            .isEqualTo(201)

        coEvery { catalog.upsert(any()) } returns false
        assertThat(runBlocking { resource.upsert("BILLA", MerchantUpsertRequest(cleanName = "Billa")) }.status)
            .isEqualTo(200)
    }

    @Test
    fun `deleting an absent entry is a 404, not a silent success`() {
        coEvery { catalog.deleteByKey(any()) } returns false

        assertThat(runBlocking { resource.delete("BILLA") }.status).isEqualTo(404)
    }

    /**
     * The worklist's whole point: rank what is missing, and never suggest something already there.
     */
    @Test
    fun `the worklist ranks by frequency and excludes what the catalogue already resolves`() {
        coEvery { transactions.recentDescriptions(any()) } returns listOf(
            "BILLA PRAHA 4",
            "BILLA PRAHA 4",
            "BILLA PRAHA 4",
            "KAVARNA U DVOU KOCEK",
            "KAVARNA U DVOU KOCEK",
            "ALZA.CZ A.S.",
        )
        coEvery { catalog.findByDescriptors(any()) } returns mapOf("BILLA" to entity("BILLA"))

        @Suppress("UNCHECKED_CAST")
        val out = runBlocking { resource.unmatchedDescriptors(25, 2000) }.entity as List<UnmatchedDescriptor>

        assertThat(out.map { it.descriptorKey }).doesNotContain("BILLA")
        assertThat(out.first().descriptorKey).isEqualTo("KAVARNAUDVOUKOCEK")
        assertThat(out.first().occurrences).isEqualTo(2)
    }

    @Test
    fun `the worklist survives an empty window`() {
        coEvery { transactions.recentDescriptions(any()) } returns emptyList()

        @Suppress("UNCHECKED_CAST")
        val out = runBlocking { resource.unmatchedDescriptors(25, 2000) }.entity as List<UnmatchedDescriptor>

        assertThat(out).isEmpty()
        coVerify(exactly = 0) { catalog.findByDescriptors(any()) }
    }

    /** A caller cannot turn the bounded window into a full table scan. */
    @Test
    fun `scan and limit are capped`() {
        coEvery { transactions.recentDescriptions(any()) } returns emptyList()

        runBlocking { resource.unmatchedDescriptors(10_000, 999_999) }

        coVerify { transactions.recentDescriptions(20_000) }
    }
}
