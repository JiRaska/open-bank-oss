// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.productcatalog.application.CatalogNotFoundException
import com.openbank.productcatalog.application.CatalogPreconditionRequiredException
import com.openbank.productcatalog.application.GenericCatalogService
import com.openbank.productcatalog.domain.catalog.CatalogSchema
import com.openbank.productcatalog.domain.catalog.EligibilityOperator
import com.openbank.productcatalog.domain.catalog.EligibilityRule
import com.openbank.productcatalog.domain.catalog.LocalizedText
import com.openbank.productcatalog.domain.catalog.MarketContext
import com.openbank.productcatalog.domain.catalog.OfferingRelationship
import com.openbank.productcatalog.domain.catalog.PriceCadence
import com.openbank.productcatalog.domain.catalog.PriceComponent
import com.openbank.productcatalog.domain.catalog.PriceKind
import com.openbank.productcatalog.domain.catalog.ProductRevision
import com.openbank.productcatalog.domain.catalog.ProductSpecification
import com.openbank.productcatalog.domain.catalog.RelationshipKind
import com.openbank.productcatalog.domain.catalog.RevisionContent
import com.openbank.productcatalog.domain.catalog.SchemaRef
import com.openbank.productcatalog.domain.catalog.TaxTreatment
import com.openbank.productcatalog.infrastructure.catalog.CatalogJson
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class SchemaRefDto(val id: String, val version: Int)
data class SpecificationRequest(val code: String, val schemaRef: SchemaRefDto)
data class OfferingRequest(val specificationId: UUID, val code: String, val market: MarketContext = MarketContext())
data class PriceRequest(
    val code: String,
    val kind: PriceKind,
    val value: String,
    val currency: String? = null,
    val unit: String,
    val cadence: PriceCadence,
    val taxTreatment: TaxTreatment = TaxTreatment.UNSPECIFIED,
    val effectiveFrom: Instant? = null,
    val effectiveTo: Instant? = null,
)
data class EligibilityRequest(
    val field: String,
    val operator: EligibilityOperator,
    val expected: JsonNode,
    val explanation: Map<String, String>,
)
data class RelationshipRequest(val kind: RelationshipKind, val targetOfferingId: UUID)
data class RevisionRequest(
    val name: Map<String, String>,
    val description: Map<String, String>? = null,
    val attributes: JsonNode,
    val prices: List<PriceRequest> = emptyList(),
    val eligibility: List<EligibilityRequest> = emptyList(),
    val relationships: List<RelationshipRequest> = emptyList(),
    val documentCodes: List<String> = emptyList(),
    val effectiveFrom: Instant? = null,
    val effectiveTo: Instant? = null,
)
data class PublishRequest(val reason: String)
data class ValidateRequest(val attributes: JsonNode)

@Suppress("TooManyFunctions")
@ApplicationScoped
@Path("/api/v2")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class GenericCatalogResource(
    private val service: GenericCatalogService,
    private val catalogJson: CatalogJson,
    private val identity: SecurityIdentity,
    private val clock: Clock,
) {
    @GET
    @Path("/product-types")
    @Authenticated
    @Authorize(action = "catalog.read")
    suspend fun listTypes(): List<Map<String, Any>> = service.listSchemas().map(::schemaResponse)

    @GET
    @Path("/product-types/{id}/versions/{version}")
    @Authenticated
    @Authorize(action = "catalog.read", resource = "#id")
    suspend fun getType(@PathParam("id") id: String, @PathParam("version") version: Int): Map<String, Any> =
        schemaResponse(service.findSchema(SchemaRef(id, version)))

    @POST
    @Path("/product-types/{id}/versions/{version}/validate")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "catalog.author", resource = "#id")
    suspend fun validate(
        @PathParam("id") id: String,
        @PathParam("version") version: Int,
        request: ValidateRequest,
    ): Response {
        val violations = service.validate(SchemaRef(id, version), request.attributes)
        return Response.ok(mapOf("valid" to violations.isEmpty(), "violations" to violations)).build()
    }

    @POST
    @Path("/specifications")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "catalog.author")
    suspend fun createSpecification(request: SpecificationRequest): Response {
        val now = Instant.now(clock)
        val created = service.createSpecification(
            ProductSpecification(
                code = request.code,
                schemaRef = SchemaRef(request.schemaRef.id, request.schemaRef.version),
                createdAt = now,
            ),
            actor(),
        )
        return Response.status(Response.Status.CREATED)
            .tag(EntityTag(created.revision.toString()))
            .entity(specificationResponse(created))
            .build()
    }

    @GET
    @Path("/specifications/{id}")
    @Authenticated
    @Authorize(action = "catalog.read", resource = "#id")
    suspend fun getSpecification(@PathParam("id") id: UUID): Response {
        val found = service.findSpecification(id)
        return Response.ok(specificationResponse(found)).tag(EntityTag(found.revision.toString())).build()
    }

    @POST
    @Path("/offerings")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "catalog.author")
    suspend fun createOffering(request: OfferingRequest): Response {
        val created = service.createOffering(request.specificationId, request.code, request.market, actor())
        return Response.status(Response.Status.CREATED)
            .tag(EntityTag(created.revision.toString()))
            .entity(created)
            .build()
    }

    @GET
    @Path("/offerings/{id}")
    @Authenticated
    @Authorize(action = "catalog.read", resource = "#id")
    suspend fun getOffering(@PathParam("id") id: UUID): Response {
        val found = service.findOffering(id)
        return Response.ok(found).tag(EntityTag(found.revision.toString())).build()
    }

    @POST
    @Path("/offerings/{id}/revisions")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "catalog.author", resource = "#id")
    suspend fun createRevision(@PathParam("id") id: UUID, request: RevisionRequest): Response {
        val created = service.createDraft(
            id,
            request.toContent(),
            request.effectiveFrom,
            request.effectiveTo,
            actor(),
        )
        return Response.status(Response.Status.CREATED)
            .tag(EntityTag(created.revision.toString()))
            .entity(revisionResponse(created))
            .build()
    }

    @GET
    @Path("/offerings/{offeringId}/revisions/{revisionId}")
    @Authenticated
    @Authorize(action = "catalog.read", resource = "#offeringId")
    suspend fun getRevision(
        @PathParam("offeringId") offeringId: UUID,
        @PathParam("revisionId") revisionId: UUID,
    ): Response {
        val found = service.findRevision(revisionId)
        requireRevisionOwner(found, offeringId)
        return Response.ok(revisionResponse(found)).tag(EntityTag(found.revision.toString())).build()
    }

    @PUT
    @Path("/offerings/{offeringId}/revisions/{revisionId}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "catalog.author", resource = "#offeringId")
    suspend fun updateRevision(
        @PathParam("offeringId") offeringId: UUID,
        @PathParam("revisionId") revisionId: UUID,
        @HeaderParam("If-Match") ifMatch: String?,
        request: RevisionRequest,
    ): Response {
        requireRevisionOwner(service.findRevision(revisionId), offeringId)
        val updated = service.updateDraft(
            revisionId,
            requiredRevision(ifMatch),
            request.toContent(),
            request.effectiveFrom,
            request.effectiveTo,
            actor(),
        )
        return Response.ok(revisionResponse(updated)).tag(EntityTag(updated.revision.toString())).build()
    }

    @POST
    @Path("/offerings/{offeringId}/revisions/{revisionId}/publish")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "catalog.publish", resource = "#offeringId")
    suspend fun publish(
        @PathParam("offeringId") offeringId: UUID,
        @PathParam("revisionId") revisionId: UUID,
        @HeaderParam("If-Match") ifMatch: String?,
        request: PublishRequest,
    ): Response {
        requireRevisionOwner(service.findRevision(revisionId), offeringId)
        val published = service.publish(revisionId, requiredRevision(ifMatch), actor(), request.reason)
        return Response.ok(revisionResponse(published)).tag(EntityTag(published.revision.toString())).build()
    }

    @GET
    @Path("/products/{offeringId}")
    @Authenticated
    @Authorize(action = "catalog.read", resource = "#offeringId")
    suspend fun getPublished(
        @PathParam("offeringId") offeringId: UUID,
        @QueryParam("effectiveAt") effectiveAt: Instant?,
    ): Map<String, Any?> = revisionResponse(
        service.findPublished(offeringId, effectiveAt ?: Instant.now(clock)),
    )

    private fun RevisionRequest.toContent() = RevisionContent(
        name = LocalizedText(name),
        description = description?.let(::LocalizedText),
        attributes = catalogJson.toObject(attributes),
        prices = prices.map {
            PriceComponent(
                it.code,
                it.kind,
                it.value.toBigDecimal(),
                it.currency,
                it.unit,
                it.cadence,
                it.taxTreatment,
                it.effectiveFrom,
                it.effectiveTo,
            )
        },
        eligibility = eligibility.map {
            EligibilityRule(it.field, it.operator, catalogJson.toValue(it.expected), LocalizedText(it.explanation))
        },
        relationships = relationships.map { OfferingRelationship(it.kind, it.targetOfferingId) },
        documentCodes = documentCodes,
    )

    private fun schemaResponse(schema: CatalogSchema): Map<String, Any> = mapOf(
        "id" to schema.ref.id,
        "version" to schema.ref.version,
        "document" to catalogJson.toNode(schema.document),
        "sha256" to schema.sha256,
        "registeredAt" to schema.registeredAt,
    )

    private fun specificationResponse(specification: ProductSpecification): Map<String, Any?> = mapOf(
        "id" to specification.id,
        "code" to specification.code,
        "schemaRef" to specification.schemaRef,
        "createdAt" to specification.createdAt,
        "revision" to specification.revision,
    )

    private fun revisionResponse(revision: ProductRevision): Map<String, Any?> = mapOf(
        "id" to revision.id,
        "offeringId" to revision.offeringId,
        "number" to revision.number,
        "schemaRef" to revision.schemaRef,
        "state" to revision.state,
        "content" to catalogJson.toContentNode(revision.content),
        "effectiveFrom" to revision.effectiveFrom,
        "effectiveTo" to revision.effectiveTo,
        "makerId" to revision.makerId,
        "checkerId" to revision.checkerId,
        "reason" to revision.reason,
        "contentHash" to revision.contentHash,
        "createdAt" to revision.createdAt,
        "updatedAt" to revision.updatedAt,
        "revision" to revision.revision,
    )

    private fun requiredRevision(ifMatch: String?): Long {
        if (ifMatch == null) throw CatalogPreconditionRequiredException("If-Match is required")
        return STRONG_ETAG.matchEntire(ifMatch)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw IllegalArgumentException("If-Match must contain one strong numeric ETag")
    }

    private fun actor(): String = identity.principal.name

    private fun requireRevisionOwner(revision: ProductRevision, offeringId: UUID) {
        if (revision.offeringId != offeringId) {
            throw CatalogNotFoundException("revision ${revision.id} not found for offering $offeringId")
        }
    }

    private companion object {
        val STRONG_ETAG = Regex("\\\"([0-9]+)\\\"")
    }
}
