// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * Hop 1 of the VoP name resolution (ADR-0171 §4): IBAN → the owning party's id.
 * `openbank-account-service` holds no holder name of its own — only the link to a party.
 *
 * We deliberately do NOT send the `X-Customer-Party-Id` header account-service uses for
 * owner-scoping: VoP is a service-to-service check on behalf of a payer who is *not* the account
 * owner, so the M2M token (ROLE_API) is the right identity and owner-scoping must not apply.
 */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface AccountServiceClient {

    @GET
    @Path("/iban/{iban}")
    fun getAccountByIban(@PathParam("iban") iban: String): Uni<AccountSummary>
}

/**
 * Subset of account-service's `AccountResponse` that VoP acts on — a local mirror, never a shared
 * type, so account-service's DTO can evolve without breaking us. Fields are nullable here even
 * where account-service declares them non-null: an unknown-field-tolerant mirror must not blow up
 * on a partial payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountSummary(val id: String? = null, val partyId: String? = null, val status: String? = null)
