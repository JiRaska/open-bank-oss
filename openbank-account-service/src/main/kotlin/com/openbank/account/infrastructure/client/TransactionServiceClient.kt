// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import com.openbank.account.application.port.out.WelcomeBonusPort
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Minimal typed client for transaction-service's payment-initiation endpoint. `POST
 * /api/v1/transactions` is `@RolesAllowed(OPERATOR)`, so the call carries an M2M bearer via
 * [OidcClientRequestReactiveFilter] (oidc-client openbank-services → ROLE_OPERATOR).
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface TransactionServiceRestClient {

    @POST
    fun initiate(request: InitiateTransactionBody): Uni<TransactionAck>
}

data class InitiateTransactionBody(
    val idempotencyKey: String,
    val type: String,
    val sourceAccountId: UUID? = null,
    val targetAccountId: UUID? = null,
    val amount: BigDecimal,
    val currencyCode: String,
    val description: String,
    val valueDate: String,
)

data class TransactionAck(val id: UUID, val status: String)

@ApplicationScoped
class TransactionServiceClient(@RestClient private val client: TransactionServiceRestClient, private val clock: Clock) :
    WelcomeBonusPort {

    // Incoming credit with no source account: transaction-service posts the deposit journal and
    // credits the beneficiary pocket. idempotencyKey is derived from the account so the saga's
    // own idempotency guard makes a repeated grant a no-op.
    override suspend fun grantWelcomeBonus(accountId: UUID, amount: BigDecimal, currency: String) {
        client.initiate(
            InitiateTransactionBody(
                idempotencyKey = "welcome-bonus-$accountId",
                type = "CREDIT",
                targetAccountId = accountId,
                amount = amount,
                currencyCode = currency,
                description = "Vítací bonus za založení účtu",
                valueDate = LocalDate.now(clock).toString(),
            ),
        ).awaitSuspending()
    }
}
