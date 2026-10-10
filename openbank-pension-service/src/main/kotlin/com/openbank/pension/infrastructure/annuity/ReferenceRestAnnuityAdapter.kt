// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.annuity

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.pension.application.annuity.AnnuityApplication
import com.openbank.pension.application.annuity.AnnuityProviderAdapter
import com.openbank.pension.application.annuity.AnnuityQuoteRequest
import com.openbank.pension.application.annuity.PartnerCancellationReason
import com.openbank.pension.application.annuity.PartnerPolicyState
import com.openbank.pension.application.annuity.PartnerPolicyStatus
import com.openbank.pension.domain.annuity.AnnuityOffer
import com.openbank.pension.domain.annuity.AnnuityType
import com.openbank.pension.domain.annuity.ApprovedPartner
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import org.eclipse.microprofile.config.ConfigProvider
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** The partner's credential, from CONFIGURATION keyed by partner id — never from the registry. */
fun interface PartnerCredentials {
    fun bearerToken(partnerId: String): String?
}

/**
 * Client of the published reference protocol (`annuity-partner-protocol-v1.yaml`, #12383): one
 * adapter for EVERY partner that implements it; the partner is chosen by the registry entry's
 * `endpointUrl`. Fails CLOSED: no credential configured for the partner → no call is made.
 */
@ApplicationScoped
class ReferenceRestAnnuityAdapter internal constructor(
    private val credentials: PartnerCredentials,
    private val http: HttpClient,
    private val requestTimeout: Duration,
) : AnnuityProviderAdapter {

    /** CDI: credentials from `openbank.pension.annuity.partners.<partnerId>.token`. */
    constructor() : this(
        PartnerCredentials { id ->
            ConfigProvider.getConfig().getOptionalValue(
                "openbank.pension.annuity.partners.$id.token",
                String::class.java,
            )
                .orElse(null)
        },
        HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build(),
        REQUEST_TIMEOUT,
    )

    override val kind: String = KIND

    override suspend fun quote(provider: ApprovedPartner, request: AnnuityQuoteRequest): List<AnnuityOffer> {
        val body = WireQuoteRequest(
            requestId = request.requestId,
            premium = request.premium,
            currency = request.currency,
            jurisdiction = request.jurisdiction,
            birthDate = request.birthDate,
            startDate = request.startDate,
            annuityTypes = request.types.sortedBy { it.ordinal },
            guaranteeMonths = request.guaranteeMonths,
            termMonths = request.termMonths,
            jointLife = request.jointLifeBirthDate?.let { WireJointLife(it, requireNotNull(request.survivorShare)) },
        )
        val response: WireQuoteResponse = call(provider, "POST", "/annuity/v1/quotes", body, null)
        return response.offers.mapNotNull { it.toDomain(provider) }
    }

    override suspend fun purchase(provider: ApprovedPartner, application: AnnuityApplication): PartnerPolicyStatus {
        val body = WirePolicyApplication(
            requestId = application.requestId,
            offerId = application.offerId,
            premium = application.premium,
            currency = application.currency,
            holderReference = application.holderReference,
            birthDate = application.birthDate,
            premiumReference = application.premiumReference,
        )
        return call<WirePolicyStatus>(
            provider,
            "POST",
            "/annuity/v1/policies",
            body,
            application.idempotencyKey,
        ).toDomain()
    }

    override suspend fun status(provider: ApprovedPartner, applicationRef: String): PartnerPolicyStatus =
        call<WirePolicyStatus>(
            provider,
            "GET",
            "/annuity/v1/policies/${segment(applicationRef)}",
            null,
            null,
        ).toDomain()

    override suspend fun cancel(
        provider: ApprovedPartner,
        applicationRef: String,
        reason: PartnerCancellationReason,
        idempotencyKey: String,
    ): PartnerPolicyStatus = call<WirePolicyStatus>(
        provider,
        "POST",
        "/annuity/v1/policies/${segment(applicationRef)}/cancellation",
        WireCancellation(reason.name),
        idempotencyKey,
    ).toDomain()

    private suspend inline fun <reified T> call(
        provider: ApprovedPartner,
        method: String,
        path: String,
        body: Any?,
        idempotencyKey: String?,
    ): T {
        val base = checkNotNull(provider.terms.endpointUrl) { "partner ${provider.partnerId} has no endpointUrl" }
        val token = checkNotNull(credentials.bearerToken(provider.partnerId)) {
            "no credential configured for partner ${provider.partnerId}"
        }
        val builder = HttpRequest.newBuilder(URI(base.trimEnd('/') + path))
            .timeout(requestTimeout)
            .header("Authorization", "Bearer $token")
            .header("Accept", JSON)
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        if (body == null) {
            builder.GET()
        } else {
            builder.header(
                "Content-Type",
                JSON,
            ).method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
        }
        val response = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await()
        check(response.statusCode() in HTTP_OK_RANGE) {
            "partner ${provider.partnerId} answered HTTP ${response.statusCode()} on $method $path"
        }
        return MAPPER.readValue(response.body())
    }

    private fun segment(ref: String) = URLEncoder.encode(ref, StandardCharsets.UTF_8)

    companion object {
        const val KIND = "reference-rest"
        private const val JSON = "application/json"
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val HTTP_OK_RANGE = 200..299

        /** Wire mapper: tolerant of fields a partner adds (forward compatibility), strict on types. */
        internal val MAPPER: ObjectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)

        /** For tests: an adapter with explicit collaborators. */
        internal fun create(credentials: PartnerCredentials, http: HttpClient = HttpClient.newHttpClient()) =
            ReferenceRestAnnuityAdapter(credentials, http, REQUEST_TIMEOUT)
    }
}

// ---- wire types (annuity-partner-protocol-v1.yaml) ----

internal data class WireJointLife(val birthDate: LocalDate, val survivorShare: BigDecimal)

internal data class WireQuoteRequest(
    val requestId: String,
    val premium: BigDecimal,
    val currency: String,
    val jurisdiction: String,
    val birthDate: LocalDate,
    val startDate: LocalDate,
    val annuityTypes: List<AnnuityType>,
    val guaranteeMonths: Int?,
    val termMonths: Int?,
    val jointLife: WireJointLife?,
)

internal data class WireQuoteResponse(val offers: List<WireOffer> = emptyList())

internal data class WireOffer(
    val offerId: String?,
    val annuityType: String?,
    val premium: BigDecimal?,
    val currency: String?,
    val monthlyAmount: BigDecimal?,
    val guaranteeMonths: Int? = null,
    val termMonths: Int? = null,
    val indexationRate: BigDecimal? = null,
    val survivorShare: BigDecimal? = null,
    val oneOffFee: BigDecimal? = null,
    val annualFeeRate: BigDecimal? = null,
    val validUntil: Instant?,
    val illustrative: Boolean?,
) {
    /** Normalisation: an offer missing a required field, or of an unknown type, is dropped — never guessed. */
    fun toDomain(provider: ApprovedPartner): AnnuityOffer? {
        val type = AnnuityType.entries.firstOrNull { it.name == annuityType } ?: return null
        val id = offerId ?: return null
        val amount = premium ?: return null
        val ccy = currency ?: return null
        val monthly = monthlyAmount ?: return null
        val until = validUntil ?: return null
        val indicative = illustrative ?: return null
        return runCatching {
            AnnuityOffer(
                offerId = id,
                partnerId = provider.partnerId,
                partnerName = provider.terms.legalName,
                type = type,
                premium = amount,
                currency = ccy,
                monthlyAmount = monthly,
                guaranteeMonths = guaranteeMonths ?: 0,
                termMonths = termMonths,
                indexationRate = indexationRate ?: BigDecimal.ZERO,
                survivorShare = survivorShare,
                oneOffFee = oneOffFee ?: BigDecimal.ZERO,
                annualFeeRate = annualFeeRate ?: BigDecimal.ZERO,
                validUntil = until,
                illustrative = indicative,
            )
        }.getOrNull()
    }
}

internal data class WirePolicyApplication(
    val requestId: String,
    val offerId: String,
    val premium: BigDecimal,
    val currency: String,
    val holderReference: String,
    val birthDate: LocalDate,
    val premiumReference: String,
)

internal data class WireCancellation(val reason: String)

internal data class WirePolicyStatus(
    val applicationRef: String,
    val state: PartnerPolicyState,
    val policyRef: String? = null,
    val monthlyAmount: BigDecimal? = null,
    val issuedOn: LocalDate? = null,
    val reason: String? = null,
    val refundRef: String? = null,
) {
    fun toDomain() = PartnerPolicyStatus(applicationRef, state, policyRef, monthlyAmount, issuedOn, reason, refundRef)
}
