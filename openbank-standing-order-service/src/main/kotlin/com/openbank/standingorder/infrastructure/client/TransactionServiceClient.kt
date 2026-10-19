// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.client

import com.openbank.clients.transaction.api.TransactionsApi
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * REST client for transaction-service's `POST /api/v1/transactions` (#889 follow-up). A
 * `DOMESTIC`/`INTERNAL` order whose creditor IBAN resolves to an account of ours
 * ([AccountServiceClient]) is an own-account or same-bank move, so it books here as `type=TRANSFER`
 * with no `rail` — exactly the signal `com.openbank.libs.domain.payment.SettlementScope` reads as
 * "stays in the bank", giving it same-day value/booking (transaction-service's own fix, #5225)
 * rather than the CERTIS cutoff/calendar a genuinely external CZ payment needs.
 *
 * Idempotency is body-based here (`InitiateTransactionRequest.idempotencyKey`), not a header —
 * `TransactionResource.initiateTransaction` has no `Idempotency-Key` header param, unlike
 * sepa-payment's create endpoint.
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
// #10486: this client's bearer is minted by the NAMED oidc-client `m2m` - Keycloak client
// `openbank-standing-order` (ROLE_API only) - never the shared `openbank-services` default client.
@OidcClientFilter("m2m")
interface TransactionServiceClient : TransactionsApi
