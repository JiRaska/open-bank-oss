// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.openbank.lending.application.port.out.CatalogLoanProfile
import com.openbank.lending.application.port.out.CatalogLoanProfilePort
import com.openbank.lending.domain.model.CatalogLoanSnapshot
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.util.UUID

@RegisterRestClient(configKey = "product-catalog")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Produces(MediaType.APPLICATION_JSON)
@Path("/api/v2")
interface ProductCatalogLoanClient {
    @GET
    @Path("/products/{offeringId}")
    fun published(@PathParam("offeringId") offeringId: UUID): Uni<CatalogLoanRevisionResponse>
}

@ApplicationScoped
class RestCatalogLoanProfilePort(@RestClient private val catalog: ProductCatalogLoanClient) : CatalogLoanProfilePort {
    override fun resolvePublished(offeringId: UUID): Uni<CatalogLoanProfile> =
        catalog.published(offeringId).map { revision -> revision.toProfile(offeringId) }
}

internal fun CatalogLoanRevisionResponse.toProfile(expectedOfferingId: UUID): CatalogLoanProfile {
    require(offeringId == expectedOfferingId) { "catalog returned a revision for a different offering" }
    require(state == "PUBLISHED") { "catalog offering is not published" }
    require(schemaRef.id == LOAN_SCHEMA_ID) { "catalog offering is not a loan product" }
    val hash = requireNotNull(contentHash) { "published catalog revision has no content hash" }
    require(HASH.matches(hash)) { "catalog revision has an invalid content hash" }
    val attributes = content.attributes
    val currency = attributes.requiredText("currency")
    require(CURRENCY.matches(currency)) { "catalog currency is not ISO-4217" }
    val tenor = attributes.requiredInt("tenorMonths")
    require(tenor in MIN_TENOR_MONTHS..MAX_TENOR_MONTHS) { "catalog tenor is outside the supported range" }
    val method = runCatching { AmortizationMethod.valueOf(attributes.requiredText("amortizationMethod")) }
        .getOrElse { throw IllegalArgumentException("catalog amortization method is unsupported") }
    val rate = attributes.requiredDecimal("nominalAnnualRate")
    require(rate.signum() >= 0) { "catalog nominal rate cannot be negative" }
    val minimum = attributes.optionalDecimal("minPrincipalAmount")
    val maximum = attributes.optionalDecimal("maxPrincipalAmount")
    require(minimum == null || maximum == null || minimum <= maximum) { "catalog principal range is inverted" }
    return CatalogLoanProfile(
        snapshot = CatalogLoanSnapshot(expectedOfferingId, id, hash, schemaRef.version),
        currency = currency,
        tenorMonths = tenor,
        method = method,
        nominalAnnualRate = rate,
        minPrincipal = minimum,
        maxPrincipal = maximum,
    )
}

private fun JsonNode.requiredText(field: String): String =
    path(field).takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
        ?: throw IllegalArgumentException("catalog attribute '$field' is required")

private fun JsonNode.requiredInt(field: String): Int = requireNotNull(
    path(field).takeIf { it.isInt }?.intValue(),
) { "catalog attribute '$field' must be an integer" }

private fun JsonNode.optionalDecimal(field: String): BigDecimal? =
    path(field).takeIf { it.isTextual }?.asText()?.let { value ->
        require(DECIMAL.matches(value)) { "catalog attribute '$field' is not a canonical decimal" }
        value.toBigDecimal()
    }

private fun JsonNode.requiredDecimal(field: String): BigDecimal =
    requireNotNull(optionalDecimal(field)) { "catalog attribute '$field' is required" }

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogLoanRevisionResponse(
    val id: UUID,
    val offeringId: UUID,
    val schemaRef: CatalogLoanSchemaRefResponse,
    val state: String,
    val content: CatalogLoanRevisionContentResponse,
    val contentHash: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogLoanSchemaRefResponse(val id: String, val version: Int)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogLoanRevisionContentResponse(val attributes: JsonNode)

private const val LOAN_SCHEMA_ID = "org.openbank.banking.loan"
private const val MIN_TENOR_MONTHS = 1
private const val MAX_TENOR_MONTHS = 480
private val CURRENCY = Regex("^[A-Z]{3}$")
private val DECIMAL = Regex("^(0|[1-9][0-9]*)(\\.[0-9]+)?$")
private val HASH = Regex("^[0-9a-f]{64}$")
