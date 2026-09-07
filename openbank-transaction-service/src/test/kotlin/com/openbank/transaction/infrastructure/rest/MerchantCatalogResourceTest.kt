// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.transaction.infrastructure.image.LogoImages
import com.openbank.transaction.infrastructure.persistence.entity.MerchantCatalogEntity
import com.openbank.transaction.infrastructure.persistence.entity.MerchantLocationEntity
import com.openbank.transaction.infrastructure.persistence.entity.MerchantLogoEntity
import com.openbank.transaction.infrastructure.persistence.repository.MerchantCatalogRepository
import com.openbank.transaction.infrastructure.persistence.repository.MerchantLocationRepository
import com.openbank.transaction.infrastructure.persistence.repository.MerchantLogoRepository
import com.openbank.transaction.infrastructure.persistence.repository.TransactionDescriptorRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.core.Request
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.SecurityContext
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.imageio.ImageIO

/**
 * The catalogue is only useful if what an operator writes is what the lookup later reads, and if
 * the worklist points at merchants that are actually missing. Both are asserted here against the
 * REAL `MerchantDescriptor.normalise` — mocking it would test the mock.
 */
class MerchantCatalogResourceTest {

    private lateinit var catalog: MerchantCatalogRepository
    private lateinit var transactions: TransactionDescriptorRepository
    private lateinit var logos: MerchantLogoRepository
    private lateinit var locations: MerchantLocationRepository
    private lateinit var locationResource: MerchantLocationResource
    private lateinit var resource: MerchantCatalogResource

    @BeforeEach
    fun setUp() {
        catalog = mockk()
        transactions = mockk()
        logos = mockk()
        locations = mockk()
        resource = MerchantCatalogResource(catalog, transactions, logos)
        locationResource = MerchantLocationResource(locations)
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

    private fun pngBytes(colour: Color = Color.RED): ByteArray {
        val image = BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = colour
        g.fillRect(0, 0, 80, 80)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun logoEntity(key: String, hash: String) = MerchantLogoEntity().also {
        it.descriptorKey = key
        it.bytes64 = byteArrayOf(1, 2, 3)
        it.bytes128 = byteArrayOf(4, 5, 6, 7)
        it.contentType = LogoImages.CONTENT_TYPE
        it.contentHash = hash
        it.updatedAt = Instant.parse("2026-09-06T08:00:00Z")
    }

    private fun unconditionalRequest(): Request = mockk {
        every { evaluatePreconditions(any<jakarta.ws.rs.core.EntityTag>()) } returns null
    }

    private fun operator(name: String = "op-1"): SecurityContext = mockk {
        every { userPrincipal } returns mockk { every { this@mockk.name } returns name }
    }

    /**
     * The same normalisation the catalogue write applies, on the read side. An app follows the URL
     * this service put in its own response, but an operator (or a support tool) pastes a raw
     * descriptor, and a route that only accepted the normalised form would 404 on the merchant it
     * was just shown.
     */
    @Test
    fun `a raw descriptor resolves to the same logo as its normalised key`() {
        coEvery { logos.findByKey("ALZACZ") } returns logoEntity("ALZACZ", "a".repeat(64))

        val response = runBlocking { resource.logo("ALZA.CZ A.S. PRAHA 4", 64, unconditionalRequest()) }

        assertThat(response.status).isEqualTo(200)
        coVerify { logos.findByKey("ALZACZ") }
    }

    /**
     * Size is an enumeration, not a number. Left open, a client could ask for 4096 and the service
     * would either serve a stored variant that does not exist or start rendering on the request
     * path — a resize per request, chosen by the caller, is a denial of service with a friendly
     * name.
     */
    @Test
    fun `an unsupported size is a 400, not a served variant`() {
        val response = runBlocking { resource.logo("ALZACZ", 512, unconditionalRequest()) }

        assertThat(response.status).isEqualTo(400)
    }

    @Test
    fun `the two supported sizes serve the two stored variants`() {
        coEvery { logos.findByKey("ALZACZ") } returns logoEntity("ALZACZ", "b".repeat(64))

        val small = runBlocking { resource.logo("ALZACZ", 64, unconditionalRequest()) }
        val large = runBlocking { resource.logo("ALZACZ", 128, unconditionalRequest()) }

        assertThat(small.entity as ByteArray).hasSize(3)
        assertThat(large.entity as ByteArray).hasSize(4)
    }

    /**
     * The conditional path carries the cache directives too. Without them a 304 would tell the
     * client the bytes are unchanged and simultaneously that it may not keep them, so the next
     * render asks again — the revalidation would never stop.
     */
    @Test
    fun `a matching If-None-Match answers 304 with no body and keeps the cache directives`() {
        val hash = "c".repeat(64)
        coEvery { logos.findByKey("ALZACZ") } returns logoEntity("ALZACZ", hash)
        val conditional: Request = mockk {
            every { evaluatePreconditions(any<jakarta.ws.rs.core.EntityTag>()) } returns
                Response.notModified()
        }

        val response = runBlocking { resource.logo("ALZACZ", 64, conditional) }

        assertThat(response.status).isEqualTo(304)
        assertThat(response.entity).isNull()
        assertThat(response.headers.getFirst("Cache-Control").toString()).contains("max-age=31536000")
        assertThat(response.entityTag?.value).isEqualTo(hash)
    }

    @Test
    fun `a merchant with no stored logo is a 404, never a placeholder image`() {
        coEvery { logos.findByKey(any()) } returns null

        val response = runBlocking { resource.logo("ALZACZ", 64, unconditionalRequest()) }

        assertThat(response.status).isEqualTo(404)
    }

    /**
     * The upload is re-encoded, so what is stored is never the bytes that arrived. Asserting the
     * stored bytes DIFFER from the upload is the only way to see that from outside: an
     * implementation that passed the file through would still produce a 201 and a plausible hash.
     */
    @Test
    fun `an uploaded image is re-encoded before it is stored`() {
        val upload = pngBytes()
        val saved = slot<MerchantLogoEntity>()
        coEvery { logos.upsert(capture(saved)) } returns true

        val response = runBlocking {
            resource.putLogo("ALZA.CZ A.S.", null, "trademark", null, operator(), upload)
        }

        assertThat(response.status).isEqualTo(201)
        assertThat(saved.captured.descriptorKey).isEqualTo("ALZACZ")
        assertThat(saved.captured.bytes64).isNotEqualTo(upload)
        assertThat(saved.captured.bytes128).isNotEqualTo(upload)
        assertThat(saved.captured.contentType).isEqualTo(LogoImages.CONTENT_TYPE)
        assertThat(saved.captured.licence).isEqualTo("trademark")
        assertThat(saved.captured.uploadedBy).isEqualTo("op-1")
    }

    /**
     * An absent body must be the caller's error. JAX-RS injects null for one, so a non-nullable
     * parameter would make the commonest mistake — `curl -X PUT` with no file — a 500 (root
     * CLAUDE.md: a non-nullable JAX-RS parameter is a 500 and a body guard is dead code).
     */
    @Test
    fun `a missing request body is a 400, not a 500`() {
        val response = runBlocking { resource.putLogo("ALZACZ", null, null, null, operator(), null) }

        assertThat(response.status).isEqualTo(400)
    }

    @Test
    fun `an upload that is not a raster image is refused with the reason`() {
        val response = runBlocking {
            resource.putLogo("ALZACZ", null, null, null, operator(), "<svg/>".toByteArray())
        }

        assertThat(response.status).isEqualTo(400)
        @Suppress("UNCHECKED_CAST")
        assertThat((response.entity as Map<String, String>)["message"]).contains("not a PNG, JPEG or GIF")
    }

    /**
     * A logo for a merchant the catalogue does not hold is unreachable — nothing would ever look it
     * up — so it is a 404 about the missing catalogue entry rather than a silently orphaned row.
     */
    @Test
    fun `a logo for an unknown merchant is a 404`() {
        coEvery { logos.upsert(any()) } returns null

        val response = runBlocking {
            resource.putLogo("NEVERSEEN", null, null, null, operator(), pngBytes())
        }

        assertThat(response.status).isEqualTo(404)
    }

    @Test
    fun `deleting a logo that is not there is a 404`() {
        coEvery { logos.deleteByKey(any()) } returns false

        val response = runBlocking { resource.deleteLogo("ALZACZ") }

        assertThat(response.status).isEqualTo(404)
    }

    /**
     * EXACT is earned, not asserted. A coordinate that is not tied to the device which took the
     * payment cannot be about where the money was spent, and letting an operator type EXACT would
     * be the chain-pin mistake rewritten one table lower.
     */
    @Test
    fun `a location claiming EXACT without a terminal is refused`() {
        val response = runBlocking {
            locationResource.upsertLocation(
                "BILLA",
                "BRNO",
                MerchantLocationRequest(
                    lat = 49.1951,
                    lon = 16.6068,
                    precision = "EXACT",
                ),
            )
        }

        assertThat(response.status).isEqualTo(400)
        @Suppress("UNCHECKED_CAST")
        assertThat((response.entity as Map<String, String>)["message"]).contains("requires a terminalId")
    }

    @Test
    fun `a location with a terminal may claim EXACT`() {
        val saved = slot<MerchantLocationEntity>()
        coEvery { locations.upsert(capture(saved)) } returns true

        val response = runBlocking {
            locationResource.upsertLocation(
                "BILLA",
                "BRNO",
                MerchantLocationRequest(
                    lat = 49.1951,
                    lon = 16.6068,
                    precision = "EXACT",
                    terminalId = "T-00042",
                ),
            )
        }

        assertThat(response.status).isEqualTo(201)
        assertThat(saved.captured.geoPrecision).isEqualTo("EXACT")
        assertThat(saved.captured.terminalId).isEqualTo("T-00042")
    }

    /**
     * The town an operator types must key the same row the read path derives from a descriptor —
     * folded and upper-cased. Stored verbatim, a carefully entered location would be one the lookup
     * can never find.
     */
    @Test
    fun `an operator-typed town is folded the way the descriptor parser folds it`() {
        val saved = slot<MerchantLocationEntity>()
        coEvery { locations.upsert(capture(saved)) } returns true

        runBlocking {
            locationResource.upsertLocation("BILLA", "Plzeň", MerchantLocationRequest(lat = 49.7475, lon = 13.3776))
        }

        assertThat(saved.captured.cityToken).isEqualTo("PLZEN")
        assertThat(saved.captured.geoPrecision).isEqualTo("CITY")
    }

    /** The catalogue row itself may not be talked into EXACT either — same reason, same table shape. */
    @Test
    fun `an unknown precision on a catalogue upsert falls back to CITY rather than being stored`() {
        val saved = slot<MerchantCatalogEntity>()
        coEvery { catalog.upsert(capture(saved)) } returns true

        runBlocking {
            resource.upsert("BILLA", MerchantUpsertRequest(cleanName = "Billa", geoPrecision = "STREET"))
        }

        assertThat(saved.captured.geoPrecision).isEqualTo("CITY")
    }

    @Test
    fun `locations for a merchant are listed in the form the read path keys on`() {
        val row = MerchantLocationEntity().also {
            it.descriptorKey = "BILLA"
            it.cityToken = "BRNO"
            it.lat = 49.1951
            it.lon = 16.6068
            it.city = "Brno"
            it.country = "CZ"
        }
        coEvery { locations.listForMerchant("BILLA") } returns listOf(row)

        val response = runBlocking { locationResource.listLocations("BILLA PRAHA 4") }

        @Suppress("UNCHECKED_CAST")
        val body = response.entity as List<MerchantLocationResponse>
        assertThat(body).singleElement().satisfies({
            assertThat(it.cityToken).isEqualTo("BRNO")
            assertThat(it.precision).isEqualTo("CITY")
        })
    }

    @Test
    fun `deleting a location that is not there is a 404`() {
        coEvery { locations.deleteByKey(any(), any()) } returns false

        val response = runBlocking { locationResource.deleteLocation("BILLA", "BRNO") }

        assertThat(response.status).isEqualTo(404)
    }

    @Test
    fun `deleting a location normalises both halves of the key`() {
        coEvery { locations.deleteByKey("BILLA", "PLZEN") } returns true

        val response = runBlocking { locationResource.deleteLocation("BILLA A.S. PRAHA", "Plzeň") }

        assertThat(response.status).isEqualTo(204)
        coVerify { locations.deleteByKey("BILLA", "PLZEN") }
    }

    /** A descriptor that normalises away entirely must not become a lookup for the empty key. */
    @Test
    fun `a descriptor that normalises to nothing is refused on every location route`() {
        assertThat(runBlocking { locationResource.listLocations("PRAHA 4") }.status).isEqualTo(400)
        assertThat(runBlocking { locationResource.deleteLocation("PRAHA 4", "BRNO") }.status).isEqualTo(400)
        assertThat(
            runBlocking {
                locationResource.upsertLocation("PRAHA 4", "BRNO", MerchantLocationRequest(lat = 1.0, lon = 2.0))
            }.status,
        ).isEqualTo(400)
    }
}
