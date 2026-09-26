// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.transaction.domain.model.MerchantDescriptor
import com.openbank.transaction.infrastructure.persistence.entity.GeoPrecision
import com.openbank.transaction.infrastructure.persistence.entity.MerchantLocationEntity
import com.openbank.transaction.infrastructure.persistence.repository.MerchantLocationRepository
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Instant

/**
 * Operator maintenance of per-town merchant locations.
 *
 * **Why this exists.** `merchant_catalog` carries ONE coordinate per merchant, and V16 seeded it for
 * chains: `BILLA` sat at a Prague address, so every Billa purchase in the country resolved there —
 * on a screen captioned "where you spent". Nothing was broken; a brand simply has no single
 * location, and the data was answering a question it could not answer. These rows are the answer it
 * can give: one location per town the merchant trades in, picked by the town the transaction's own
 * acquirer descriptor named.
 *
 * Separate from [MerchantCatalogResource] because they are separate subjects — the catalogue says
 * WHO was paid, this says WHERE — and because one class holding both had grown past the point where
 * a reader can hold it in their head.
 */
@Path("/api/v1/merchants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Merchant catalogue", description = "Operator maintenance of merchant enrichment data")
class MerchantLocationResource(private val locations: MerchantLocationRepository) {

    /**
     * The per-town locations recorded for one merchant.
     *
     * A chain's catalogue row carries one representative pin for the whole brand; these are the rows
     * that make a purchase resolve to the town it actually happened in.
     */
    @GET
    @Path("/{descriptorKey}/locations")
    @RolesAllowed(Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "merchant.list", resource = "")
    @Operation(summary = "Per-town locations for one merchant")
    suspend fun listLocations(@PathParam("descriptorKey") descriptorKey: String): Response {
        val key = MerchantDescriptor.normalise(descriptorKey)
            ?: return badRequest("descriptorKey normalises to nothing identifying")
        return Response.ok(locations.listForMerchant(key).map { it.toResponse() }).build()
    }

    /**
     * Create or replace one town's location for a merchant.
     *
     * `cityToken` is the town **as the acquirer descriptor spells it** — folded and upper-cased,
     * `PLZEN` and not `Plzeň` — because that is the token the read path derives from the descriptor
     * and matches on. It is normalised here the same way, so an operator may type either.
     */
    @PUT
    @Path("/{descriptorKey}/locations/{cityToken}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "merchant.update", resource = "")
    @Operation(summary = "Create or replace one town's location for a merchant")
    suspend fun upsertLocation(
        @PathParam("descriptorKey") descriptorKey: String,
        @PathParam("cityToken") cityToken: String,
        request: MerchantLocationRequest,
    ): Response {
        val key = MerchantDescriptor.normalise(descriptorKey)
            ?: return badRequest("descriptorKey normalises to nothing identifying")
        val town = normaliseCityToken(cityToken)
            ?: return badRequest("cityToken normalises to nothing identifying")
        val precision = request.precision?.trim()?.uppercase() ?: GeoPrecision.CITY
        if (precision != GeoPrecision.CITY && precision != GeoPrecision.EXACT) {
            return badRequest("precision must be ${GeoPrecision.CITY} or ${GeoPrecision.EXACT}")
        }
        // EXACT is earned, not asserted. Only a location tied to the device that took the payment is
        // about where the money was spent; without one, a hand-typed "EXACT" is the chain-pin
        // mistake rewritten one table lower. The database enforces this too — refusing here means an
        // operator reads why instead of a 500.
        if (precision == GeoPrecision.EXACT && request.terminalId.isNullOrBlank()) {
            return badRequest("precision EXACT requires a terminalId — a coordinate without a device is CITY at best")
        }
        val created = locations.upsert(
            MerchantLocationEntity().also {
                it.descriptorKey = key
                it.cityToken = town
                it.lat = request.lat
                it.lon = request.lon
                it.city = request.city?.trim()?.ifBlank { null }
                it.country = request.country?.trim()?.ifBlank { null }?.uppercase()
                it.geoPrecision = precision
                it.terminalId = request.terminalId?.trim()?.ifBlank { null }
                it.source = request.source?.trim()?.ifBlank { null }
                it.updatedAt = Instant.now()
            },
        )
        return Response.status(if (created) Response.Status.CREATED else Response.Status.OK).build()
    }

    @DELETE
    @Path("/{descriptorKey}/locations/{cityToken}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "merchant.delete", resource = "")
    @Operation(summary = "Remove one town's location for a merchant")
    suspend fun deleteLocation(
        @PathParam("descriptorKey") descriptorKey: String,
        @PathParam("cityToken") cityToken: String,
    ): Response {
        val key = MerchantDescriptor.normalise(descriptorKey)
            ?: return badRequest("descriptorKey normalises to nothing identifying")
        val town = normaliseCityToken(cityToken)
            ?: return badRequest("cityToken normalises to nothing identifying")
        return if (locations.deleteByKey(key, town)) {
            Response.noContent().build()
        } else {
            Response.status(Response.Status.NOT_FOUND).build()
        }
    }

    /**
     * A town typed by an operator, in the form the descriptor parser produces.
     *
     * [MerchantDescriptor.foldTown] and deliberately NOT `normalise`: normalising a town returns
     * null, because stripping towns is exactly what that function does to reach a merchant key.
     * Reaching for the familiar call here stores nothing and reports "cityToken normalises to
     * nothing identifying" for a perfectly good town — which is how this was written the first time,
     * and what `an operator-typed town is folded the way the descriptor parser folds it` catches.
     */
    private fun normaliseCityToken(raw: String): String? = MerchantDescriptor.foldTown(raw)

    private fun badRequest(message: String): Response =
        Response.status(Response.Status.BAD_REQUEST).entity(mapOf("message" to message)).build()
}

/**
 * One town's location for a merchant.
 *
 * `precision` defaults to CITY and may only be EXACT with a `terminalId`: a coordinate that is not
 * tied to the device which took the payment cannot be about where the money was spent.
 */
data class MerchantLocationRequest(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val city: String? = null,
    val country: String? = null,
    val precision: String? = null,
    val terminalId: String? = null,
    val source: String? = null,
)

data class MerchantLocationResponse(
    val descriptorKey: String,
    val cityToken: String,
    val lat: Double,
    val lon: Double,
    val city: String?,
    val country: String?,
    val precision: String,
    val terminalId: String?,
    val source: String?,
    val updatedAt: Instant,
)

private fun MerchantLocationEntity.toResponse() = MerchantLocationResponse(
    descriptorKey = descriptorKey,
    cityToken = cityToken,
    lat = lat,
    lon = lon,
    city = city,
    country = country,
    precision = geoPrecision,
    terminalId = terminalId,
    source = source,
    updatedAt = updatedAt,
)
