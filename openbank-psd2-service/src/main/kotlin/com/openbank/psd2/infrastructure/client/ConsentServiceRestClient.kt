// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.psd2.application.port.out.ConsentServiceClient
import com.openbank.psd2.application.port.out.ConsentSnapshot
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.LocalDate

/**
 * Typed client for consent-service's real REST surface (`/api/v1/consents`), replacing the
 * always-`ACTIVE`/always-`true` [StubConsentServiceClient] for the load-bearing AIS read/validate
 * gate (issue #1500). Every endpoint is `@RolesAllowed(ROLE_API, ROLE_OPERATOR, ROLE_ADMIN)`
 * plus an OPA `@Authorize` action on consent-service, so calls carry an M2M bearer via
 * [OidcClientRequestReactiveFilter] (oidc-client `openbank-services` → ROLE_OPERATOR), the same
 * pattern account-service's `TransactionServiceRestClient` uses.
 *
 * Partial DTOs: Quarkus' rest-client Jackson has `FAIL_ON_UNKNOWN_PROPERTIES=false`, so we bind only
 * the fields the facade needs; `@JsonIgnoreProperties(ignoreUnknown = true)` makes that explicit and
 * robust against a consent-service response-schema addition.
 */
@RegisterRestClient(configKey = "consent-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/consents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface ConsentServiceRestClient {

    @GET
    @Path("/{id}")
    fun getById(@PathParam("id") id: String): Uni<ConsentRestResponse>

    @POST
    @Path("/{id}/validate")
    fun validate(@PathParam("id") id: String, request: ValidateConsentRestRequest): Uni<ConsentValidationRestResponse>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ConsentRestResponse(val id: String, val partyId: String, val status: String)

/**
 * Wire contract of consent-service's `POST /{id}/validate` body (`ValidateConsentRequest`):
 * `requiredScope` is a `ConsentScope` enum on the provider — sent here as its exact name string
 * (the psd2 facade already passes `ACCOUNTS_READ`/`BALANCES_READ`/… which are the enum names).
 */
data class ValidateConsentRestRequest(val granteeId: String, val requiredScope: String, val accountIban: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ConsentValidationRestResponse(val valid: Boolean, val reason: String?, val code: String?)

/**
 * Real [ConsentServiceClient] adapter. The AIS read/validate path — `getConsent`, `getConsentStatus`,
 * `validateConsent` — calls consent-service for real; the [ResilientConsentServiceClient] wrapper
 * layers fault-tolerance + fail-closed fallbacks (deny / `UNKNOWN`) over these.
 *
 * `createConsent`/`revokeConsent` still delegate to [StubConsentServiceClient]: the XS2A
 * consent-creation flow (consent-service's `POST /consents` returns a `PENDING_SCA` consent needing
 * SCA activation, and `DELETE /consents/{id}` is keyed on `partyId`, which the psd2 port does not
 * carry) is a distinct, larger behavior change scoped as a documented follow-up to #1500. Closing
 * the always-`true` validation gate — the actual security hole — does not depend on it.
 */
@ApplicationScoped
class RestConsentServiceClient(
    @RestClient private val rest: ConsentServiceRestClient,
    private val consentCreateRevokeFallback: StubConsentServiceClient,
) : ConsentServiceClient {

    override suspend fun getConsent(consentId: String): ConsentSnapshot {
        val r = rest.getById(consentId).awaitSuspending()
        return ConsentSnapshot(consentId = r.id, partyId = r.partyId, status = r.status)
    }

    override suspend fun getConsentStatus(consentId: String): String = getConsent(consentId).status

    override suspend fun validateConsent(consentId: String, granteeId: String, scope: String, iban: String?): Boolean =
        rest.validate(consentId, ValidateConsentRestRequest(granteeId, scope, iban)).awaitSuspending().valid

    @Suppress("LongParameterList")
    override suspend fun createConsent(
        partyId: String,
        granteeId: String,
        granteeName: String,
        scopes: Set<String>,
        accountIbans: List<String>?,
        validUntil: LocalDate,
        redirectUri: String?,
        tppTransactionId: String?,
        ipAddress: String?,
    ): String = consentCreateRevokeFallback.createConsent(
        partyId, granteeId, granteeName, scopes, accountIbans, validUntil, redirectUri, tppTransactionId, ipAddress,
    )

    override suspend fun revokeConsent(consentId: String, granteeId: String) =
        consentCreateRevokeFallback.revokeConsent(consentId, granteeId)
}
