// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.transaction.domain.model.MerchantDescriptor
import com.openbank.transaction.infrastructure.image.LogoImages
import com.openbank.transaction.infrastructure.ingest.LogoFetcher
import com.openbank.transaction.infrastructure.persistence.entity.MerchantLogoEntity
import com.openbank.transaction.infrastructure.persistence.repository.MerchantLogoRepository
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
 * The merchant logos this bank stores and serves — reading them, and the two ways one gets in.
 *
 * **Why the bank serves the bytes at all.** The alternative — putting a logo CDN's URL in the
 * statement response and letting the app load it — hands that CDN the customer's IP address together
 * with the merchant they paid, every time they open their transaction list. That is a spending
 * profile leaving the bank through an `<img>` tag, with no consent and no contract covering it, and
 * the image renders perfectly the whole time.
 *
 * Separate from [MerchantCatalogResource] because they answer different questions — that one is who
 * was paid, this is what their mark looks like — and because one class holding both had grown past
 * where a reader can keep it in their head.
 */
@Path("/api/v1/merchants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Merchant catalogue", description = "Operator maintenance of merchant enrichment data")
class MerchantLogoResource(private val logos: MerchantLogoRepository, private val fetcher: LogoFetcher) {

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
    // PUT, not POST, and the idempotency gate is right to have asked. The operation is an upsert
    // keyed by the descriptor: fetching the same URL twice stores the same bytes and answers with
    // the same hash, so replaying it changes nothing. POST claimed a create-each-time semantic this
    // never had, and an operator retrying after a timeout would have been right to fear it.
    @PUT
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

    /**
     * A year, immutable. Safe only because the URL the client follows carries the content hash:
     * new bytes are a new URL, so nothing cached can ever be stale.
     */
    private fun immutable(): CacheControl = CacheControl().also {
        it.isPrivate = false
        it.maxAge = LOGO_MAX_AGE_SECONDS
    }

    private fun badRequest(message: String): Response =
        Response.status(Response.Status.BAD_REQUEST).entity(mapOf("message" to message)).build()

    private companion object {
        const val LOGO_MAX_AGE_SECONDS = 31_536_000
    }
}

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
