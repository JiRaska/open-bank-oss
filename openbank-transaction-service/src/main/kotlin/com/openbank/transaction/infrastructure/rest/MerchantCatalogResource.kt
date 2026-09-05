// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.transaction.domain.model.MerchantDescriptor
import com.openbank.transaction.infrastructure.persistence.entity.MerchantCatalogEntity
import com.openbank.transaction.infrastructure.persistence.repository.MerchantCatalogRepository
import com.openbank.transaction.infrastructure.persistence.repository.PanacheTransactionRepository
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Instant

/**
 * Operator maintenance of the merchant enrichment catalogue (#8573).
 *
 * **Why this exists.** The enrichment pipeline behind `merchant` / `merchantCategory` was complete
 * end to end — descriptor normalisation, one catalogue query per page, the edge forwarding and
 * declaring both fields, four app screens rendering them — and starved: `merchant_catalog` held the
 * ~30 rows one migration seeded and **had no writer anywhere in the codebase**. Adding a merchant
 * meant a database migration and a deploy, so in practice the catalogue never grew and
 * `merchant` was absent for, in the edge's own words, "most transactions".
 *
 * **[unmatchedDescriptors] is the half that makes the rest usable.** A bare CRUD screen is a blank
 * form: an operator has no way to know which merchants are worth adding. This ranks the normalised
 * descriptors that customers actually saw and the catalogue could not resolve, so the work is a
 * worklist ordered by how many people it affects rather than a guess.
 *
 * **Operator-facing only.** The entity's boundary is unchanged: rows hold public business data — a
 * trading name, a logo, a shop location — and never anything customer-derived. Nothing here is
 * reachable through customer-edge, and every route requires OPERATOR or ADMIN.
 */
@Path("/api/v1/merchants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Merchant catalogue", description = "Operator maintenance of merchant enrichment data")
class MerchantCatalogResource(
    private val catalog: MerchantCatalogRepository,
    private val transactions: PanacheTransactionRepository,
) {

    @GET
    @RolesAllowed(Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "merchant.list", resource = "")
    @Operation(summary = "List catalogue entries, most recently updated first")
    suspend fun list(
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("50") size: Int,
    ): Response {
        val capped = size.coerceIn(1, MAX_PAGE_SIZE)
        val rows = catalog.listPaged(page.coerceAtLeast(0), capped)
        return Response.ok(MerchantPage(rows.map { it.toAdminResponse() }, catalog.countAll())).build()
    }

    /**
     * The descriptors customers saw most often that the catalogue could not resolve.
     *
     * Normalisation is [MerchantDescriptor.normalise], i.e. Kotlin rather than SQL, so this cannot
     * be an anti-join: it reads a bounded window of recent descriptions, normalises them, drops the
     * ones already in the catalogue, and ranks what is left. Bounded deliberately — an operator
     * wants the top of the list, and an unbounded scan of a transaction table is not worth a
     * complete answer nobody reads to the end.
     */
    @GET
    @Path("/unmatched")
    @RolesAllowed(Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "merchant.list", resource = "")
    @Operation(summary = "Most frequent acquirer descriptors with no catalogue entry")
    suspend fun unmatchedDescriptors(
        @QueryParam("limit") @DefaultValue("25") limit: Int,
        @QueryParam("scan") @DefaultValue("2000") scan: Int,
    ): Response {
        val window = transactions.recentDescriptions(scan.coerceIn(1, MAX_SCAN))
        val counts = window.mapNotNull { MerchantDescriptor.normalise(it) }
            .groupingBy { it }.eachCount()
        if (counts.isEmpty()) return Response.ok(emptyList<UnmatchedDescriptor>()).build()
        val known = catalog.findByDescriptors(counts.keys).keys
        val ranked = counts.filterKeys { it !in known }
            .entries.sortedByDescending { it.value }
            .take(limit.coerceIn(1, MAX_PAGE_SIZE))
            .map { UnmatchedDescriptor(it.key, it.value) }
        return Response.ok(ranked).build()
    }

    /**
     * Create or replace one entry. Idempotent by key — see
     * [MerchantCatalogRepository.upsert] for why this is not split into POST and PATCH.
     *
     * The key is normalised on the way in, so an operator who pastes a raw acquirer descriptor
     * (`ALZA.CZ A.S. PRAHA 4`) gets the row the lookup will actually hit rather than a dead entry
     * under a key nothing produces.
     */
    @PUT
    @Path("/{descriptorKey}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "merchant.update", resource = "")
    @Operation(summary = "Create or replace a catalogue entry")
    suspend fun upsert(@PathParam("descriptorKey") descriptorKey: String, request: MerchantUpsertRequest): Response {
        val key = MerchantDescriptor.normalise(descriptorKey)
            ?: return badRequest("descriptorKey normalises to nothing identifying")
        if (request.cleanName.isBlank()) return badRequest("cleanName is required")
        // Both coordinates or neither — the column constraint enforces it, and refusing here means
        // an operator sees why rather than a 500 from the database.
        if ((request.lat == null) != (request.lon == null)) {
            return badRequest("lat and lon must be given together or not at all")
        }
        val created = catalog.upsert(request.toEntity(key))
        val body = catalog.findByKey(key)?.toAdminResponse()
        return Response.status(if (created) Response.Status.CREATED else Response.Status.OK).entity(body).build()
    }

    @DELETE
    @Path("/{descriptorKey}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "merchant.delete", resource = "")
    @Operation(summary = "Remove a catalogue entry")
    suspend fun delete(@PathParam("descriptorKey") descriptorKey: String): Response {
        val key = MerchantDescriptor.normalise(descriptorKey)
            ?: return badRequest("descriptorKey normalises to nothing identifying")
        return if (catalog.deleteByKey(key)) {
            Response.noContent().build()
        } else {
            Response.status(Response.Status.NOT_FOUND).build()
        }
    }

    private fun badRequest(message: String): Response =
        Response.status(Response.Status.BAD_REQUEST).entity(mapOf("message" to message)).build()

    private companion object {
        const val MAX_PAGE_SIZE = 200
        const val MAX_SCAN = 20_000
    }
}

data class MerchantUpsertRequest(
    val cleanName: String = "",
    val logoUrl: String? = null,
    val category: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val city: String? = null,
    val country: String? = null,
)

data class MerchantAdminResponse(
    val descriptorKey: String,
    val cleanName: String,
    val logoUrl: String?,
    val category: String?,
    val lat: Double?,
    val lon: Double?,
    val city: String?,
    val country: String?,
    val updatedAt: Instant,
)

data class MerchantPage(val data: List<MerchantAdminResponse>, val total: Long)

data class UnmatchedDescriptor(val descriptorKey: String, val occurrences: Int)

private fun MerchantUpsertRequest.toEntity(key: String) = MerchantCatalogEntity().also {
    it.descriptorKey = key
    it.cleanName = cleanName.trim()
    it.logoUrl = logoUrl?.trim()?.ifBlank { null }
    it.category = category?.trim()?.ifBlank { null }?.uppercase()
    it.lat = lat
    it.lon = lon
    it.city = city?.trim()?.ifBlank { null }
    it.country = country?.trim()?.ifBlank { null }?.uppercase()
    it.updatedAt = Instant.now()
}

private fun MerchantCatalogEntity.toAdminResponse() = MerchantAdminResponse(
    descriptorKey = descriptorKey,
    cleanName = cleanName,
    logoUrl = logoUrl,
    category = category,
    lat = lat,
    lon = lon,
    city = city,
    country = country,
    updatedAt = updatedAt,
)
