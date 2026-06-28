// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import com.openbank.account.application.port.out.FxConversionPort
import com.openbank.account.application.port.out.FxConversionResult
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.util.UUID

@RegisterRestClient(configKey = "fx-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/fx")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface FxServiceRestClient {

    @POST
    @Path("/convert")
    fun convert(
        @HeaderParam("Idempotency-Key") idempotencyKey: String,
        request: FxConvertRequest,
    ): Uni<FxConvertResponse>
}

data class FxConvertRequest(
    val partyId: UUID,
    val partyName: String,
    val accountId: UUID,
    val fromCurrency: String,
    val toCurrency: String,
    val fromAmountMinorUnits: Long,
)

data class FxConvertResponse(
    val id: UUID,
    val fromCurrency: String,
    val toCurrency: String,
    val fromAmountMinorUnits: Long,
    val toAmountMinorUnits: Long,
    val appliedRate: BigDecimal,
    val status: String,
)

@ApplicationScoped
class FxServiceClient(@RestClient private val client: FxServiceRestClient) : FxConversionPort {

    override suspend fun convert(
        idempotencyKey: String,
        accountId: UUID,
        partyId: UUID,
        partyName: String,
        fromCurrency: String,
        toCurrency: String,
        fromAmountMinorUnits: Long,
    ): FxConversionResult {
        val response = client.convert(
            idempotencyKey = idempotencyKey,
            request = FxConvertRequest(
                partyId = partyId,
                partyName = partyName,
                accountId = accountId,
                fromCurrency = fromCurrency,
                toCurrency = toCurrency,
                fromAmountMinorUnits = fromAmountMinorUnits,
            ),
        ).awaitSuspending()
        return FxConversionResult(
            id = response.id,
            fromCurrency = response.fromCurrency,
            toCurrency = response.toCurrency,
            fromAmountMinorUnits = response.fromAmountMinorUnits,
            toAmountMinorUnits = response.toAmountMinorUnits,
            appliedRate = response.appliedRate,
        )
    }
}
