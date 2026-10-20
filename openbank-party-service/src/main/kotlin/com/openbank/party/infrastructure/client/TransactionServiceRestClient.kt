// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.client

import com.openbank.clients.transaction.api.TransactionsApi
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * Typed client for transaction-service's list-by-account endpoint, used by the GDPR Art. 20
 * portability export (ADR-0204 D2) to assemble the subject's transaction history.
 * `GET /api/v1/transactions?accountId=` is `@RolesAllowed(API/VIEWER/OPERATOR/ADMIN)`, so the
 * call carries an M2M bearer minted by the NAMED oidc-client `m2m`.
 *
 * Operations and DTOs are GENERATED from transaction-service's own `openapi.yaml`
 * (`openbank-client-transaction`, ADR-0319). The page wrapper is a `CursorPage`, i.e. rows arrive
 * under `data` - there is no `items` key - which is what the generated `TransactionPage` models.
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
// #10486 batch 5: the transaction list (transaction.list) is minted by the NAMED oidc-client `m2m` -
// Keycloak client `openbank-party` (ROLE_API only) - never the shared `openbank-services` one.
@OidcClientFilter("m2m")
interface TransactionServiceRestClient : TransactionsApi
