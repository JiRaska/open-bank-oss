// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.transaction.domain.model.MerchantDescriptor
import com.openbank.transaction.infrastructure.image.LogoImages
import com.openbank.transaction.infrastructure.ingest.LogoFetcher
import com.openbank.transaction.infrastructure.persistence.entity.GeoPrecision
import com.openbank.transaction.infrastructure.persistence.entity.MerchantCatalogEntity
import com.openbank.transaction.infrastructure.persistence.entity.MerchantLogoEntity
import com.openbank.transaction.infrastructure.persistence.repository.MerchantCatalogRepository
import com.openbank.transaction.infrastructure.persistence.repository.MerchantLogoRepository
import com.openbank.transaction.infrastructure.persistence.repository.TransactionDescriptorRepository
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.CacheControl
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Request
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.SecurityContext
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
    private val transactions: TransactionDescriptorRepository,
    private val logos: MerchantLogoRepository,
    private val fetcher: LogoFetcher,
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

    /**
     * The merchant's logo, as a square PNG.
     *
     * **Why this bank serves the bytes itself.** The alternative — putting a logo CDN's URL in the
     * statement response and letting the app load it — hands that CDN the customer's IP address
     * together with the merchant they paid, every time they open their transaction list. That is a
     * spending profile leaving the bank through an `<img>` tag, with no consent and no contract
     * covering it. The bytes are ingested once and served from here, so a logo tells nobody
     * anything.
     *
     * Cached hard and keyed by content: the URL carries the content hash, so a corrected logo is a
     * different URL and reaches clients immediately, and `If-None-Match` makes the repeat request a
     * 304 with no body.
     */
    @GET
    @Path("/{descriptorKey}/logo")
    @Produces(LogoImages.CONTENT_TYPE)
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "merchant.list", resource = "")
    @Operation(summary = "The merchant's logo as a square PNG")
    suspend fun logo(
        @PathParam("descriptorKey") descriptorKey: String,
        @QueryParam("size") @DefaultValue("64") size: Int,
        @Context request: Request,
    ): Response {
        val key = MerchantDescriptor.normalise(descriptorKey)
            ?: return badRequest("descriptorKey normalises to nothing identifying")
        if (size != LogoImages.SIZE_SMALL && size != LogoImages.SIZE_LARGE) {
            return badRequest("size must be ${LogoImages.SIZE_SMALL} or ${LogoImages.SIZE_LARGE}")
        }
        val logo = logos.findByKey(key) ?: return Response.status(Response.Status.NOT_FOUND).build()
        val tag = EntityTag(logo.contentHash)
        // Conditional first: an unchanged logo is by far the common case once an app has rendered
        // the statement once, and a 304 costs no bytes on the wire.
        request.evaluatePreconditions(tag)?.let { return it.cacheControl(immutable()).tag(tag).build() }
        val bytes = if (size == LogoImages.SIZE_LARGE) logo.bytes128 else logo.bytes64
        return Response.ok(bytes, logo.contentType)
            .tag(tag)
            .cacheControl(immutable())
            .header("Content-Length", bytes.size)
            .build()
    }

    /**
     * Store or replace one merchant's logo from raw image bytes.
     *
     * The upload is decoded and re-encoded rather than stored as sent — see [LogoImages] for why
     * that is the security boundary and not a nicety. Provenance travels with it: `sourceUrl`,
     * `licence` and `attribution` record where a trademark came from and on what terms this bank
     * may show it, which is the paperwork a logo needs and an image file does not carry.
     */
    @PUT
    @Path("/{descriptorKey}/logo")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "merchant.update", resource = "")
    @Operation(summary = "Store or replace a merchant logo from raw image bytes")
    @Suppress("LongParameterList")
    suspend fun putLogo(
        @PathParam("descriptorKey") descriptorKey: String,
        @QueryParam("sourceUrl") sourceUrl: String?,
        @QueryParam("licence") licence: String?,
        @QueryParam("attribution") attribution: String?,
        @Context security: SecurityContext,
        upload: ByteArray?,
    ): Response {
        val key = MerchantDescriptor.normalise(descriptorKey)
            ?: return badRequest("descriptorKey normalises to nothing identifying")
        // Nullable on purpose: JAX-RS injects null for an absent body, and a non-nullable parameter
        // would make that a 500 instead of the 400 it is (fleet rule, see the root CLAUDE.md).
        val bytes = upload ?: return badRequest("request body is required and must be image bytes")
        val rendered = try {
            LogoImages.render(bytes)
        } catch (e: LogoImages.RejectedException) {
            return badRequest(e.message ?: "logo upload was rejected")
        }
        val entity = MerchantLogoEntity().also {
            it.descriptorKey = key
            it.bytes64 = rendered.small
            it.bytes128 = rendered.large
            it.contentType = LogoImages.CONTENT_TYPE
            it.contentHash = rendered.contentHash
            it.sourceUrl = sourceUrl?.trim()?.ifBlank { null }
            it.licence = licence?.trim()?.ifBlank { null }
            it.attribution = attribution?.trim()?.ifBlank { null }
            it.uploadedBy = security.userPrincipal?.name
            it.updatedAt = Instant.now()
        }
        val created = logos.upsert(entity)
            ?: return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("message" to "no catalogue entry under key '$key' to attach a logo to"))
                .build()
        val status = if (created) Response.Status.CREATED else Response.Status.OK
        return Response.status(status).entity(MerchantLogoResponse(key, rendered.contentHash)).build()
    }

    @DELETE
    @Path("/{descriptorKey}/logo")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "merchant.delete", resource = "")
    @Operation(summary = "Remove a merchant logo")
    suspend fun deleteLogo(@PathParam("descriptorKey") descriptorKey: String): Response {
        val key = MerchantDescriptor.normalise(descriptorKey)
            ?: return badRequest("descriptorKey normalises to nothing identifying")
        return if (logos.deleteByKey(key)) {
            Response.noContent().build()
        } else {
            Response.status(Response.Status.NOT_FOUND).build()
        }
    }

    /**
     * A year, immutable. Safe only because the URL the client follows carries the content hash:
     * new bytes are a new URL, so nothing cached can ever be stale.
     */
    private fun immutable(): CacheControl = CacheControl().also {
        it.isPrivate = false
        it.maxAge = LOGO_MAX_AGE_SECONDS
    }

    /**
     * Ingest a logo from a URL the operator names, instead of uploading the file by hand.
     *
     * **Why this is worth the risk it carries.** Without it the catalogue is filled one file at a
     * time, and a catalogue that is filled by hand is one that stays at thirty rows — which is the
     * failure the whole `merchant_catalog` history is about. With it, an operator working the
     * unmatched worklist can resolve a merchant in one action.
     *
     * The risk is server-side request forgery, and [LogoFetcher] is where it is fenced: a host
     * allowlist that is empty by default, HTTPS only, every resolved address checked to be publicly
     * routable, and redirects refused rather than followed. Read that class before changing anything
     * here — the guards are not interchangeable and each one is what makes another meaningful.
     *
     * What arrives is bytes, and they go through exactly the same decode / dimension-check /
     * re-encode as an upload. Nothing about "we fetched it ourselves" makes the content trustworthy.
     */
    @POST
    @Path("/{descriptorKey}/logo/fetch")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "merchant.update", resource = "")
    @Operation(summary = "Ingest a merchant logo from an allowlisted source URL")
    suspend fun fetchLogo(
        @PathParam("descriptorKey") descriptorKey: String,
        @Context security: SecurityContext,
        request: MerchantLogoFetchRequest?,
    ): Response {
        val key = MerchantDescriptor.normalise(descriptorKey)
            ?: return badRequest("descriptorKey normalises to nothing identifying")
        // Nullable body + explicit check: JAX-RS injects null for an absent body, and a non-nullable
        // parameter would turn the commonest mistake into a 500 (fleet rule).
        val sourceUrl = request?.sourceUrl?.trim()?.ifBlank { null }
            ?: return badRequest("sourceUrl is required")

        val fetched = try {
            fetcher.fetch(sourceUrl)
        } catch (e: LogoFetcher.RefusedException) {
            return badRequest(e.message ?: "the source URL was refused")
        }
        val rendered = try {
            LogoImages.render(fetched.bytes)
        } catch (e: LogoImages.RejectedException) {
            return badRequest("fetched content was rejected: ${e.message}")
        }

        val entity = MerchantLogoEntity().also {
            it.descriptorKey = key
            it.bytes64 = rendered.small
            it.bytes128 = rendered.large
            it.contentType = LogoImages.CONTENT_TYPE
            it.contentHash = rendered.contentHash
            // The URL AS FETCHED, not as typed: it is licence evidence, so it has to be the thing
            // that actually answered.
            it.sourceUrl = fetched.sourceUrl
            it.licence = request.licence?.trim()?.ifBlank { null }
            it.attribution = request.attribution?.trim()?.ifBlank { null }
            it.uploadedBy = security.userPrincipal?.name
            it.updatedAt = Instant.now()
        }
        val created = logos.upsert(entity)
            ?: return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("message" to "no catalogue entry under key '$key' to attach a logo to"))
                .build()
        val status = if (created) Response.Status.CREATED else Response.Status.OK
        return Response.status(status).entity(MerchantLogoResponse(key, rendered.contentHash)).build()
    }

    /**
     * Whether logo fetching is configured, and from where.
     *
     * The operator screen needs this to decide whether to offer the button at all: a feature that is
     * off by design and a feature that is broken look identical from a 400, and an allowlist an
     * operator cannot see is one they will guess at.
     */
    @GET
    @Path("/logo-sources")
    @RolesAllowed(Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "merchant.list", resource = "")
    @Operation(summary = "Whether logo fetching is enabled, and the hosts it may fetch from")
    fun logoSources(): Response =
        Response.ok(MerchantLogoSources(fetcher.isEnabled(), fetcher.allowedHosts().sorted())).build()

    private fun badRequest(message: String): Response =
        Response.status(Response.Status.BAD_REQUEST).entity(mapOf("message" to message)).build()

    private companion object {
        const val MAX_PAGE_SIZE = 200
        const val MAX_SCAN = 20_000
        const val LOGO_MAX_AGE_SECONDS = 31_536_000
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
    /**
     * What the coordinates on the catalogue row can answer. Defaults to CITY, which is the honest
     * answer for a chain — one pin cannot be where a purchase happened in any of forty towns.
     */
    val geoPrecision: String? = null,
)

/**
 * One catalogue row as an operator sees it.
 *
 * [logoUrl] is PROVENANCE — where the stored bitmap was obtained — and is deliberately not what a
 * customer client receives; that one is derived from [logoContentHash] and points at this service.
 * [logoContentHash] is null exactly when no logo has been ingested, which is how the operator
 * screen knows whether to offer "upload" or "replace".
 */
data class MerchantAdminResponse(
    val descriptorKey: String,
    val cleanName: String,
    val logoUrl: String?,
    val logoContentHash: String?,
    val geoPrecision: String,
    val category: String?,
    val lat: Double?,
    val lon: Double?,
    val city: String?,
    val country: String?,
    val updatedAt: Instant,
)

data class MerchantPage(val data: List<MerchantAdminResponse>, val total: Long)

data class UnmatchedDescriptor(val descriptorKey: String, val occurrences: Int)

/**
 * Where to fetch a logo from, and under what terms it may be shown.
 *
 * `licence` and `attribution` are the operator's assertion about the source, not something the fetch
 * can establish — an image file does not carry its own licence.
 */
data class MerchantLogoFetchRequest(
    val sourceUrl: String? = null,
    val licence: String? = null,
    val attribution: String? = null,
)

/** Whether logo fetching is configured, and the hosts it is allowed to reach. */
data class MerchantLogoSources(val enabled: Boolean, val allowedHosts: List<String>)

/** What a logo write returns: the key it landed under and the hash that now identifies its bytes. */
data class MerchantLogoResponse(val descriptorKey: String, val contentHash: String)

private fun MerchantUpsertRequest.toEntity(key: String) = MerchantCatalogEntity().also {
    it.descriptorKey = key
    it.cleanName = cleanName.trim()
    it.logoUrl = logoUrl?.trim()?.ifBlank { null }
    it.category = category?.trim()?.ifBlank { null }?.uppercase()
    it.lat = lat
    it.lon = lon
    it.geoPrecision = geoPrecision?.trim()?.uppercase()?.takeIf { p -> p == GeoPrecision.EXACT } ?: GeoPrecision.CITY
    it.city = city?.trim()?.ifBlank { null }
    it.country = country?.trim()?.ifBlank { null }?.uppercase()
    it.updatedAt = Instant.now()
}

private fun MerchantCatalogEntity.toAdminResponse() = MerchantAdminResponse(
    descriptorKey = descriptorKey,
    cleanName = cleanName,
    logoUrl = logoUrl,
    logoContentHash = logoEtag,
    geoPrecision = geoPrecision,
    category = category,
    lat = lat,
    lon = lon,
    city = city,
    country = country,
    updatedAt = updatedAt,
)
