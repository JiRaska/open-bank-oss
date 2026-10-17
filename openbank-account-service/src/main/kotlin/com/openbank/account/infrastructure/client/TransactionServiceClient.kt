// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import com.openbank.account.application.port.out.WelcomeBonusPort
import com.openbank.clients.transaction.api.TransactionsApi
import com.openbank.clients.transaction.model.InitiateTransactionRequest
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Typed client for transaction-service's payment-initiation endpoint, GENERATED from its own
 * `openapi.yaml` (`openbank-client-transaction`, ADR-0319). `POST
 * /api/v1/transactions` is `@RolesAllowed(API, OPERATOR)`, so the call carries an M2M bearer via
 * the named oidc-client `m2m`: account-service's OWN Keycloak client `openbank-account` (ROLE_API
 * only), which transaction-service's OPA grants `transaction.create` by identity (#10486).
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
// #10486: this client's bearer is minted by the NAMED oidc-client `m2m` - Keycloak client
// `openbank-account` (ROLE_API only) - never the shared `openbank-services` default client.
@OidcClientFilter("m2m")
interface TransactionServiceRestClient : TransactionsApi

@ApplicationScoped
class TransactionServiceClient(@RestClient private val client: TransactionServiceRestClient, private val clock: Clock) :
    WelcomeBonusPort {

    // Incoming credit with no source account: transaction-service posts the deposit journal and
    // credits the beneficiary pocket. idempotencyKey is derived from the account so the saga's
    // own idempotency guard makes a repeated grant a no-op.
    override suspend fun grantWelcomeBonus(accountId: UUID, amount: BigDecimal, currency: String) {
        client.initiateTransaction(
            InitiateTransactionRequest(
                idempotencyKey = "welcome-bonus-$accountId",
                type = "CREDIT",
                targetAccountId = accountId,
                amount = amount,
                currencyCode = currency,
                description = "Vítací bonus za založení účtu",
                valueDate = LocalDate.now(clock),
            ),
        ).awaitSuspending()
    }
}
