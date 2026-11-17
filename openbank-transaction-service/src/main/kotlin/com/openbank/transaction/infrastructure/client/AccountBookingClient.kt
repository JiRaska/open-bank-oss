// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.transaction.infrastructure.client

import com.openbank.libs.market.BookingContext
import com.openbank.libs.synthetic.SyntheticTaint
import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.transaction.application.port.out.AccountBookingPort
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
@Path("/api/v1/accounts")
interface AccountBookingClient {
    @GET
    @Path("/{accountId}")
    fun read(
        @PathParam("accountId") accountId: UUID,
        @HeaderParam(SyntheticTaint.KAFKA_HEADER) taint: String?,
    ): Uni<AccountBookingView>
}

data class AccountBookingView(val id: UUID, val bookingContext: BookingContext? = null)

@ApplicationScoped
class AccountBookingAdapter(@RestClient private val client: AccountBookingClient) : AccountBookingPort {
    override suspend fun context(accountId: UUID, synthetic: Boolean): BookingContext? {
        val account = client.read(accountId, if (synthetic) SyntheticTaint.headerValue() else null).awaitSuspending()
        check(account.id == accountId) { "Account booking response is bound to a different account" }
        return account.bookingContext
    }
}
