// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.libs.product.replay.DiffOptions
import com.openbank.libs.product.replay.ReplayRange
import com.openbank.libs.security.Roles
import com.openbank.productcatalog.application.CatalogForbiddenException
import com.openbank.productcatalog.application.CatalogNotFoundException
import com.openbank.productcatalog.application.CatalogPreconditionRequiredException
import com.openbank.productcatalog.application.GenericCatalogService
import com.openbank.productcatalog.application.RevisionBehaviourDiffService
import com.openbank.productcatalog.domain.catalog.CatalogSchema
import com.openbank.productcatalog.domain.catalog.EligibilityOperator
import com.openbank.productcatalog.domain.catalog.EligibilityRule
import com.openbank.productcatalog.domain.catalog.LocalizedText
import com.openbank.productcatalog.domain.catalog.MarketContext
import com.openbank.productcatalog.domain.catalog.OfferingRelationship
import com.openbank.productcatalog.domain.catalog.PriceCadence
import com.openbank.productcatalog.domain.catalog.PriceComponent
import com.openbank.productcatalog.domain.catalog.PriceKind
import com.openbank.productcatalog.domain.catalog.PriceRole
import com.openbank.productcatalog.domain.catalog.ProductOffering
import com.openbank.productcatalog.domain.catalog.ProductRevision
import com.openbank.productcatalog.domain.catalog.ProductSpecification
import com.openbank.productcatalog.domain.catalog.RelationshipKind
import com.openbank.productcatalog.domain.catalog.RevisionContent
import com.openbank.productcatalog.domain.catalog.SchemaRef
import com.openbank.productcatalog.domain.catalog.TaxTreatment
import com.openbank.productcatalog.generated.api.CatalogV2Api
import com.openbank.productcatalog.generated.model.BehaviourDiffRequest
import com.openbank.productcatalog.generated.model.LintProductIrRequest
import com.openbank.productcatalog.generated.model.OfferingRequest
import com.openbank.productcatalog.generated.model.PublishRequest
import com.openbank.productcatalog.generated.model.RevisionRequest
import com.openbank.productcatalog.generated.model.SpecificationRequest
import com.openbank.productcatalog.generated.model.ValidateCatalogRequest
import com.openbank.productcatalog.infrastructure.catalog.CatalogJson
import com.openbank.productcatalog.infrastructure.catalog.CatalogSchemaProfile
import com.openbank.productcatalog.infrastructure.catalog.toPlainTree
import com.openbank.productcatalog.infrastructure.security.CatalogRoles
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.Response
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import com.openbank.productcatalog.generated.model.BehaviourDiffResponse as GeneratedBehaviourDiffResponse
import com.openbank.productcatalog.generated.model.CatalogSchema as GeneratedCatalogSchema
import com.openbank.productcatalog.generated.model.IrSignature as GeneratedIrSignature
import com.openbank.productcatalog.generated.model.LintProductIrResponse as GeneratedLintProductIrResponse
import com.openbank.productcatalog.generated.model.MarketContext as GeneratedMarketContext
import com.openbank.productcatalog.generated.model.Offering as GeneratedOffering
import com.openbank.productcatalog.generated.model.ProductIrProblem as GeneratedProductIrProblem
import com.openbank.productcatalog.generated.model.ProductRevision as GeneratedProductRevision
import com.openbank.productcatalog.generated.model.RevisionApprovalPolicy as GeneratedRevisionApprovalPolicy
import com.openbank.productcatalog.generated.model.RevisionApprovalRole as GeneratedRevisionApprovalRole
import com.openbank.productcatalog.generated.model.RevisionContent as GeneratedRevisionContent
import com.openbank.productcatalog.generated.model.SchemaRef as GeneratedSchemaRef
import com.openbank.productcatalog.generated.model.SchemaViolation as GeneratedSchemaViolation
import com.openbank.productcatalog.generated.model.Specification as GeneratedSpecification
import com.openbank.productcatalog.generated.model.ValidateCatalogResponse as GeneratedValidateCatalogResponse

data class SchemaRefDto(val id: String, val version: Int)

@Suppress("TooManyFunctions")
@ApplicationScoped
class GenericCatalogResource(
    private val service: GenericCatalogService,
    private val catalogJson: CatalogJson,
    private val mapper: ObjectMapper,
    private val schemaProfile: CatalogSchemaProfile,
    private val identity: SecurityIdentity,
    private val clock: Clock,
    private val behaviourDiff: RevisionBehaviourDiffService,
) : CatalogV2Api {
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read")
    override suspend fun listProductTypesV2(): Response =
        Response.ok(service.listSchemas().map(::schemaResponse)).build()

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read", resource = "#id")
    override suspend fun getProductTypeVersionV2(id: String, version: Int): Response =
        Response.ok(schemaResponse(service.findSchema(SchemaRef(id, version)))).build()

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.AUTHOR)
    @Authorize(action = "catalog.author", resource = "#id")
    override suspend fun validateProductAttributesV2(
        id: String,
        version: Int,
        request: ValidateCatalogRequest,
    ): Response {
        val attributes = mapper.valueToTree<JsonNode>(request.attributes)
        schemaProfile.requireValidInstance(attributes)
        val violations = service.validate(SchemaRef(id, version), attributes)
        return Response.ok(
            GeneratedValidateCatalogResponse(
                violations.isEmpty(),
                violations.map { GeneratedSchemaViolation(it.instancePath, it.schemaPath, it.keyword, it.message) },
            ),
        ).build()
    }

    /**
     * ADR-0352 phase 5b: dry-run lint for the Studio IR editor. The bound and the parser are the ones
     * a revision write applies to `behaviour` (see [toContent]), so the editor and the write agree.
     */
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.AUTHOR)
    @Authorize(action = "catalog.author")
    override suspend fun lintProductIrV2(request: LintProductIrRequest): Response {
        val node = mapper.valueToTree<JsonNode>(request.ir)
        schemaProfile.requireValidInstance(node)
        val result = service.lintIr(catalogJson.toObject(node))
        return Response.ok(
            GeneratedLintProductIrResponse(
                valid = result.ir != null,
                problems = result.problems.map { GeneratedProductIrProblem(it.path, it.message) },
                productCode = result.ir?.productCode,
                irVersion = result.ir?.irVersion,
                revisionHash = result.ir?.revisionHash,
                canonicalIr = result.canonicalJson,
            ),
        ).build()
    }

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read", resource = "#offeringId")
    override suspend fun behaviourDiffRevisionV2(
        offeringId: UUID,
        revisionId: UUID,
        request: BehaviourDiffRequest,
    ): Response {
        val portfolioNode = mapper.valueToTree<JsonNode>(request.portfolio)
        schemaProfile.requireValidInstance(portfolioNode)
        val zone = runCatching { ZoneId.of(request.zone ?: "UTC") }.getOrElse {
            throw IllegalArgumentException("zone must be an IANA zone id")
        }
        val diff = behaviourDiff.diff(
            offeringId = offeringId,
            revisionId = revisionId,
            baseRevisionId = request.baseRevisionId,
            portfolio = RevisionBehaviourDiffService.parsePortfolio(catalogJson.toObject(portfolioNode).toPlainTree()),
            range = ReplayRange(request.from, request.to, zone),
            options = request.materialityThreshold?.let { DiffOptions(BigDecimal(it)) } ?: DiffOptions(),
        )

        // The generated model types the free-form object as Map<String, Any>; the document's null members
        // (a recorded portfolio's `seed`) serialise as JSON null exactly as in canonicalJson.
        @Suppress("UNCHECKED_CAST")
        val tree = diff.tree as Map<String, Any>
        return Response.ok(GeneratedBehaviourDiffResponse(diff = tree, canonicalJson = diff.canonicalJson)).build()
    }

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.AUTHOR)
    @Authorize(action = "catalog.author")
    override suspend fun createSpecificationV2(request: SpecificationRequest): Response {
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

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read")
    override suspend fun listSpecificationsV2(): Response =
        Response.ok(service.listSpecifications().map(::specificationResponse)).build()

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read", resource = "#id")
    override suspend fun getSpecificationV2(id: UUID): Response {
        val found = service.findSpecification(id)
        return Response.ok(specificationResponse(found)).tag(EntityTag(found.revision.toString())).build()
    }

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.AUTHOR)
    @Authorize(action = "catalog.author")
    override suspend fun createOfferingV2(request: OfferingRequest): Response {
        val created = service.createOffering(request.specificationId, request.code, request.market.toDomain(), actor())
        return Response.status(Response.Status.CREATED)
            .tag(EntityTag(created.revision.toString()))
            .entity(offeringResponse(created))
            .build()
    }

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read")
    override suspend fun listOfferingsV2(specificationId: UUID?): Response =
        Response.ok(service.listOfferings(specificationId).map(::offeringResponse)).build()

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read", resource = "#id")
    override suspend fun getOfferingV2(id: UUID): Response {
        val found = service.findOffering(id)
        return Response.ok(offeringResponse(found)).tag(EntityTag(found.revision.toString())).build()
    }

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.AUTHOR)
    @Authorize(action = "catalog.author", resource = "#id")
    override suspend fun createOfferingRevisionV2(id: UUID, request: RevisionRequest): Response {
        val created = service.createDraft(
            id,
            SchemaRef(request.schemaRef.id, request.schemaRef.version),
            request.toContent(),
            request.effectiveFrom?.toInstant(),
            request.effectiveTo?.toInstant(),
            actor(),
        )
        return Response.status(Response.Status.CREATED)
            .tag(EntityTag(created.revision.toString()))
            .entity(revisionResponse(created))
            .build()
    }

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read", resource = "#id")
    override suspend fun listOfferingRevisionsV2(id: UUID): Response =
        Response.ok(service.listRevisions(id).map(::revisionResponse)).build()

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read", resource = "#offeringId")
    override suspend fun getOfferingRevisionV2(offeringId: UUID, revisionId: UUID): Response {
        val found = service.findRevision(revisionId)
        requireRevisionOwner(found, offeringId)
        return Response.ok(revisionResponse(found)).tag(EntityTag(found.revision.toString())).build()
    }

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read", resource = "#id")
    override suspend fun getRevisionV2(id: UUID): Response {
        val found = service.findRevision(id)
        return Response.ok(revisionResponse(found)).tag(EntityTag(found.revision.toString())).build()
    }

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.AUTHOR)
    @Authorize(action = "catalog.author", resource = "#offeringId")
    override suspend fun replaceOfferingRevisionV2(
        ifMatch: String,
        offeringId: UUID,
        revisionId: UUID,
        request: RevisionRequest,
    ): Response {
        val existing = service.findRevision(revisionId)
        requireRevisionOwner(existing, offeringId)
        require(
            request.schemaRef.id == existing.schemaRef.id && request.schemaRef.version == existing.schemaRef.version,
        ) {
            "schemaRef is immutable within a revision; create a new revision to advance its schema"
        }
        val updated = service.updateDraft(
            revisionId,
            requiredRevision(ifMatch),
            request.toContent(),
            request.effectiveFrom?.toInstant(),
            request.effectiveTo?.toInstant(),
            actor(),
        )
        return Response.ok(revisionResponse(updated)).tag(EntityTag(updated.revision.toString())).build()
    }

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.PUBLISH)
    @Authorize(action = "catalog.publish", resource = "#offeringId")
    override suspend fun publishOfferingRevisionV2(
        offeringId: UUID,
        revisionId: UUID,
        ifMatch: String,
        request: PublishRequest,
    ): Response {
        requireRevisionOwner(service.findRevision(revisionId), offeringId)
        val published = service.publish(revisionId, requiredRevision(ifMatch), actor(), request.reason)
        return Response.ok(revisionResponse(published)).tag(EntityTag(published.revision.toString())).build()
    }

    /**
     * ADR-0352 §7: publishing an IR-bearing revision is its own four-eyes action (`catalog.publish-ir`,
     * `rules.yaml: four_eyes.actions`), distinct from `catalog.publish`, because it also produces the
     * publish-path signature over the IR's canonical hash. Same roles, same maker != checker rule.
     */
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "catalog.publish-ir", resource = "#offeringId")
    override suspend fun publishOfferingRevisionIrV2(
        offeringId: UUID,
        revisionId: UUID,
        ifMatch: String,
        request: PublishRequest,
    ): Response {
        // Enforced HERE, not only in rest.rego: @Authorize is advisory while AUTHZ_ENFORCE=false, and a
        // Keycloak service account can carry ROLE_OPERATOR. Fails closed on POSITIVE evidence: only a
        // verified JWT from an interactive client (azp), with a subject and a non-service-account
        // username matching the principal, may sign. "Does not look like a machine" is not enough.
        if (!ServiceAccountIdentity.isHumanStaff(identity)) {
            throw CatalogForbiddenException("publish-ir requires a verified human checker")
        }
        requireRevisionOwner(service.findRevision(revisionId), offeringId)
        val published = service.publishIr(revisionId, requiredRevision(ifMatch), actor(), request.reason)
        return Response.ok(revisionResponse(published)).tag(EntityTag(published.revision.toString())).build()
    }

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read", resource = "#offeringId")
    override suspend fun getPublishedProductV2(offeringId: UUID, effectiveAt: java.time.OffsetDateTime?): Response =
        Response.ok(
            revisionResponse(service.findPublished(offeringId, effectiveAt?.toInstant() ?: Instant.now(clock))),
        ).build()

    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read", resource = "#productId")
    override suspend fun getPublishedRevisionForLegacyProductV2(
        productId: UUID,
        effectiveAt: java.time.OffsetDateTime?,
    ): Response = Response.ok(
        revisionResponse(
            service.findPublishedForLegacyProduct(productId, effectiveAt?.toInstant() ?: Instant.now(clock)),
        ),
    ).build()

    @Suppress("LongMethod")
    private fun RevisionRequest.toContent(): RevisionContent {
        require(name.isNotEmpty() && name.size <= MAX_LOCALIZED_VALUES) {
            "name must contain between 1 and $MAX_LOCALIZED_VALUES localized values"
        }
        require(description == null || description.size <= MAX_LOCALIZED_VALUES) {
            "description must contain at most $MAX_LOCALIZED_VALUES localized values"
        }
        (name.values + description.orEmpty().values).forEach {
            require(it.length <= MAX_LOCALIZED_TEXT_LENGTH) {
                "localized text must contain at most $MAX_LOCALIZED_TEXT_LENGTH characters"
            }
        }
        val prices = prices.orEmpty()
        val eligibility = eligibility.orEmpty()
        val relationships = relationships.orEmpty()
        val documentCodes = documentCodes.orEmpty()
        require(prices.size <= MAX_COMPONENTS) { "a revision may contain at most $MAX_COMPONENTS prices" }
        require(eligibility.size <= MAX_COMPONENTS) {
            "a revision may contain at most $MAX_COMPONENTS eligibility rules"
        }
        require(relationships.size <= MAX_COMPONENTS) {
            "a revision may contain at most $MAX_COMPONENTS relationships"
        }
        require(documentCodes.size <= MAX_COMPONENTS) {
            "a revision may contain at most $MAX_COMPONENTS document codes"
        }
        documentCodes.forEach {
            require(it.isNotBlank() && it.length <= MAX_CODE_LENGTH) {
                "document codes must be non-blank and at most $MAX_CODE_LENGTH characters"
            }
        }
        val attributesNode = mapper.valueToTree<JsonNode>(attributes)
        schemaProfile.requireValidInstance(attributesNode)
        // Product IR (ADR-0352): the trusted-profile check bounds the document; ProductIrParser validates
        // it fully in the service before the draft is written.
        val behaviourNode = behaviour?.let { mapper.valueToTree<JsonNode>(it) }
        behaviourNode?.let { schemaProfile.requireValidInstance(it) }
        eligibility.forEach { schemaProfile.requireValidInstance(mapper.valueToTree(it.expected)) }
        eligibility.flatMap { it.explanation.values }.forEach {
            require(it.length <= MAX_LOCALIZED_TEXT_LENGTH) {
                "eligibility explanation must contain at most $MAX_LOCALIZED_TEXT_LENGTH characters"
            }
        }
        eligibility.forEach {
            require(it.explanation.isNotEmpty() && it.explanation.size <= MAX_LOCALIZED_VALUES) {
                "eligibility explanation must contain between 1 and $MAX_LOCALIZED_VALUES localized values"
            }
        }
        return RevisionContent(
            name = LocalizedText(name),
            description = description?.let(::LocalizedText),
            attributes = catalogJson.toObject(attributesNode),
            prices = prices.map {
                require(DECIMAL_TEXT.matches(it.value)) {
                    "price value must be a canonical decimal string with at most 20 integer and 18 fractional digits"
                }
                PriceComponent(
                    it.code,
                    PriceKind.valueOf(it.kind.value),
                    it.value.toBigDecimal(),
                    it.currency,
                    it.unit,
                    PriceCadence.valueOf(it.cadence.value),
                    it.taxTreatment?.value?.let(TaxTreatment::valueOf) ?: TaxTreatment.UNSPECIFIED,
                    it.effectiveFrom?.toInstant(),
                    it.effectiveTo?.toInstant(),
                    it.role?.value?.let(PriceRole::valueOf),
                )
            },
            eligibility = eligibility.map {
                EligibilityRule(
                    it.field,
                    EligibilityOperator.valueOf(it.operator.value),
                    catalogJson.toValue(mapper.valueToTree(it.expected)),
                    LocalizedText(it.explanation),
                )
            },
            relationships = relationships.map {
                OfferingRelationship(RelationshipKind.valueOf(it.kind.value), it.targetOfferingId)
            },
            documentCodes = documentCodes.toList(),
            behaviour = behaviourNode?.let { catalogJson.toObject(it) },
        )
    }

    private fun GeneratedMarketContext?.toDomain() = MarketContext(
        brands = this?.brands.orEmpty(),
        countries = this?.countries.orEmpty(),
        channels = this?.channels.orEmpty(),
        segments = this?.segments.orEmpty(),
        locales = this?.locales.orEmpty(),
    )

    private fun schemaResponse(schema: CatalogSchema) = GeneratedCatalogSchema(
        id = schema.ref.id,
        version = schema.ref.version,
        document = mapper.convertValue(catalogJson.toNode(schema.document), Map::class.java)
            .entries.associate { it.key.toString() to it.value as Any },
        sha256 = schema.sha256,
        registeredAt = schema.registeredAt.atOffset(ZoneOffset.UTC),
        approvalPolicy = schema.approvalPolicy?.let { policy ->
            GeneratedRevisionApprovalPolicy(
                roles = policy.roles.map { GeneratedRevisionApprovalRole(code = it.code) },
                requireEffectiveFrom = policy.requireEffectiveFrom,
                requiredAttributes = policy.requiredAttributes,
            )
        },
    )

    private fun specificationResponse(specification: ProductSpecification) = GeneratedSpecification(
        id = specification.id,
        code = specification.code,
        schemaRef = GeneratedSchemaRef(specification.schemaRef.id, specification.schemaRef.version),
        createdAt = specification.createdAt.atOffset(ZoneOffset.UTC),
        revision = specification.revision,
    )

    private fun offeringResponse(offering: ProductOffering) = GeneratedOffering(
        id = offering.id,
        specificationId = offering.specificationId,
        code = offering.code,
        market = GeneratedMarketContext(
            brands = offering.market.brands,
            countries = offering.market.countries,
            channels = offering.market.channels,
            segments = offering.market.segments,
            locales = offering.market.locales,
        ),
        revision = offering.revision,
    )

    private fun revisionResponse(revision: ProductRevision) = GeneratedProductRevision(
        id = revision.id,
        offeringId = revision.offeringId,
        number = revision.number,
        schemaRef = GeneratedSchemaRef(revision.schemaRef.id, revision.schemaRef.version),
        state = GeneratedProductRevision.State.valueOf(revision.state.name),
        content = mapper.treeToValue(
            catalogJson.toContentNode(revision.content),
            GeneratedRevisionContent::class.java,
        ),
        effectiveFrom = revision.effectiveFrom?.atOffset(ZoneOffset.UTC),
        effectiveTo = revision.effectiveTo?.atOffset(ZoneOffset.UTC),
        makerId = revision.makerId,
        checkerId = revision.checkerId,
        reason = revision.reason,
        contentHash = revision.contentHash,
        createdAt = revision.createdAt.atOffset(ZoneOffset.UTC),
        updatedAt = revision.updatedAt.atOffset(ZoneOffset.UTC),
        revision = revision.revision,
        approvalDigest = revision.approvalDigest,
        approvedEffectiveTo = revision.approvedEffectiveTo?.atOffset(ZoneOffset.UTC),
        // Deprecated wire aliases of the two fields above (#13429); removed with the compatibility window.
        pensionApprovalDigest = revision.approvalDigest,
        pensionApprovedEffectiveTo = revision.approvedEffectiveTo?.atOffset(ZoneOffset.UTC),
        irSignature = revision.irSignature?.let {
            GeneratedIrSignature(
                revisionHash = it.revisionHash,
                keyId = it.keyId,
                algorithm = it.algorithm,
                signature = it.signature,
                signedAt = it.signedAt.atOffset(ZoneOffset.UTC),
            )
        },
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
        val DECIMAL_TEXT = Regex("^(0|[1-9][0-9]{0,19})(\\.[0-9]{1,18})?$")
        const val MAX_LOCALIZED_VALUES = 32
        const val MAX_LOCALIZED_TEXT_LENGTH = 4_096
        const val MAX_COMPONENTS = 256
        const val MAX_CODE_LENGTH = 128
    }
}
