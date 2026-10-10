// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.questionnaire.StrategyInstrumentMapping
import com.openbank.pension.domain.questionnaire.StrategyInstrumentMappingPort
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Path("/api/v2")
@Produces(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "product-catalog")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterProvider(ProductCatalogHostHeaderFilter::class)
interface StrategyCatalogRestClient {
    @GET
    @Path("/offerings")
    suspend fun offerings(): List<JsonNode>

    @GET
    @Path("/products/{offeringId}")
    suspend fun published(
        @PathParam("offeringId") offeringId: UUID,
        @QueryParam("effectiveAt") effectiveAt: OffsetDateTime,
    ): JsonNode

    @GET
    @Path("/offerings/{offeringId}/revisions/{revisionId}/pension-approvals")
    suspend fun pensionApprovals(
        @PathParam("offeringId") offeringId: UUID,
        @PathParam("revisionId") revisionId: UUID,
    ): List<JsonNode>
}

/** Narrow seam keeps HTTP annotations away from the mapping rules and their tests. */
interface StrategyCatalogRead {
    suspend fun offerings(): List<JsonNode>

    suspend fun published(offeringId: UUID, at: OffsetDateTime): JsonNode

    suspend fun pensionApprovals(offeringId: UUID, revisionId: UUID): List<JsonNode>
}

@ApplicationScoped
class StrategyInstrumentCatalogAdapter(@RestClient private val client: StrategyCatalogRestClient) :
    StrategyInstrumentMappingPort {
    private val resolver = StrategyInstrumentCatalogResolver(
        object : StrategyCatalogRead {
            override suspend fun offerings() = client.offerings()

            override suspend fun published(offeringId: UUID, at: OffsetDateTime) = client.published(offeringId, at)

            override suspend fun pensionApprovals(offeringId: UUID, revisionId: UUID) =
                client.pensionApprovals(offeringId, revisionId)
        },
    )

    override suspend fun effectivePublished(
        jurisdiction: String,
        productLine: ProductLine,
        strategyCode: String,
        at: Instant,
    ) = resolver.effectivePublished(jurisdiction, productLine, strategyCode, at)
}

/** Every catalog read must succeed; only a genuine 404 from the effective projection is absent. */
class StrategyInstrumentCatalogResolver(private val catalog: StrategyCatalogRead) : StrategyInstrumentMappingPort {
    @Suppress("LongMethod") // One fail-closed parse of the published projection and its review evidence.
    override suspend fun effectivePublished(
        jurisdiction: String,
        productLine: ProductLine,
        strategyCode: String,
        at: Instant,
    ): List<StrategyInstrumentMapping> {
        require(jurisdiction.isNotBlank() && strategyCode.isNotBlank())
        val ids = catalog.offerings().map { offering ->
            UUID.fromString(offering.requiredText("id"))
        }
        check(ids.size == ids.distinct().size) { "catalog returned duplicate offerings" }
        val matching = ids.mapNotNull { id ->
            val revision = try {
                catalog.published(id, at.atOffset(ZoneOffset.UTC))
            } catch (e: WebApplicationException) {
                if (e.response?.status == HTTP_NOT_FOUND) return@mapNotNull null
                throw e
            }
            val schema = revision.path("schemaRef")
            if (schema.requiredText("id") != RETIREMENT_SCHEMA) return@mapNotNull null
            val version = schema.path("version")
            check(version.isIntegralNumber) { "invalid retirement catalog schema version" }
            val attributes = revision.path("content").path("attributes")
            val line = if (version.intValue() >= 2) {
                attributes.requiredText("productLine")
            } else {
                attributes.optionalText("productLine")
            }
            val pack = if (version.intValue() >= 2) {
                attributes.requiredText("jurisdictionPackId")
            } else {
                attributes.optionalText("jurisdictionPackId")
            }
            val strategy = if (version.intValue() >= 2) {
                attributes.requiredText("fundStrategy")
            } else {
                attributes.optionalText("fundStrategy")
            }
            if (line != productLine.name || pack != "$jurisdiction/${productLine.name}" || strategy != strategyCode) {
                return@mapNotNull null
            }
            check(version.intValue() >= 2) {
                "retirement mapping requires catalog schema v2"
            }
            check(revision.requiredText("state") == "PUBLISHED") { "catalog projection is not published" }
            check(revision.requiredText("offeringId") == id.toString()) {
                "catalog revision belongs to another offering"
            }
            // The attribute is maker-authored. Require the catalog's independent,
            // immutable publication evidence; this does not establish reviewer expertise.
            val makerId = revision.requiredText("makerId")
            val checkerId = revision.requiredText("checkerId")
            check(makerId != checkerId) { "retirement mapping lacks independent approval" }
            revision.requiredText("reason")
            val contentHash = revision.requiredText("contentHash")
            check(CONTENT_HASH.matches(contentHash)) { "retirement mapping lacks publication hash" }
            check(attributes.requiredText("reviewStatus") == "LEGAL_AND_COMMERCIAL_REVIEWED") {
                "retirement mapping is not reviewed"
            }
            val revisionId = UUID.fromString(revision.requiredText("id"))
            requireEffectiveInterval(revision, at)
            requirePensionApprovals(id, revisionId, revision)
            val classes = attributes.path("instrumentClasses")
            check(classes.isArray && classes.size() > 0) { "retirement mapping has no instrument classes" }
            val classNames = classes.map { item ->
                check(item.isTextual && item.textValue().isNotBlank()) { "invalid instrument class" }
                item.textValue()
            }
            check(classNames.size == classNames.distinct().size) { "duplicate instrument class" }
            val number = revision.path("number")
            check(number.isIntegralNumber && number.longValue() > 0) { "invalid catalog revision number" }
            StrategyInstrumentMapping(
                jurisdiction,
                productLine,
                strategyCode,
                "$revisionId:${number.longValue()}",
                classNames.toSet(),
            )
        }
        check(matching.size <= 1) { "ambiguous published retirement strategy mapping" }
        return matching
    }

    private fun JsonNode.requiredText(name: String): String {
        val value = path(name)
        check(value.isTextual && value.textValue().isNotBlank()) { "catalog field $name is missing or blank" }
        return value.textValue()
    }

    private fun JsonNode.optionalText(name: String): String? = path(name).takeIf(JsonNode::isTextual)?.textValue()

    private fun requireEffectiveInterval(revision: JsonNode, at: Instant) {
        val effectiveFrom = OffsetDateTime.parse(revision.requiredText("effectiveFrom")).toInstant()
        val effectiveTo = revision.path("effectiveTo").takeUnless(JsonNode::isNull)
            ?.takeUnless(JsonNode::isMissingNode)
            ?.let { OffsetDateTime.parse(it.textValue() ?: error("invalid effectiveTo")).toInstant() }
        check(effectiveFrom <= at && (effectiveTo == null || at < effectiveTo)) {
            "retirement mapping is not effective at the requested instant"
        }
    }

    private suspend fun requirePensionApprovals(offeringId: UUID, revisionId: UUID, revision: JsonNode) {
        // The catalog freezes this digest at publication after checking distinct legal and product
        // actors under its DB lock. The consumer checks both immutable role records against that
        // exact revision/content/effective-interval digest, without exposing actor identities.
        val approvalDigest = revision.requiredText("pensionApprovalDigest")
        check(CONTENT_HASH.matches(approvalDigest)) { "retirement mapping lacks frozen approval digest" }
        val approvals = catalog.pensionApprovals(offeringId, revisionId)
        check(approvals.size == REQUIRED_APPROVAL_ROLES.size) {
            "retirement mapping lacks independent role approvals"
        }
        val approvalRoles = approvals.map { approval ->
            check(approval.requiredText("digest") == approvalDigest) {
                "retirement mapping approval is stale or belongs to another revision"
            }
            OffsetDateTime.parse(approval.requiredText("approvedAt"))
            approval.requiredText("role")
        }
        check(approvalRoles.toSet() == REQUIRED_APPROVAL_ROLES && approvalRoles.distinct().size == approvals.size) {
            "retirement mapping lacks independent role approvals"
        }
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val RETIREMENT_SCHEMA = "org.openbank.retirement.pension-savings"
        val CONTENT_HASH = Regex("^[0-9a-f]{64}$")
        val REQUIRED_APPROVAL_ROLES = setOf("LEGAL_COUNSEL", "PRODUCT_OWNER")
    }
}
