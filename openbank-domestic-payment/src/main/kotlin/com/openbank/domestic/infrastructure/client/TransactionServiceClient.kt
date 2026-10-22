// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.clients.transaction.api.TransactionsApi
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * RestClient binding to `openbank-transaction-service`, GENERATED from its own `openapi.yaml`
 * (`openbank-client-transaction`, ADR-0319). Callers go through the
 * [com.openbank.clients.transaction.TransactionClient] facade, never the raw generated operations.
 *
 * The bearer is minted by the NAMED oidc-client `m2m` (Keycloak client `openbank-domestic-payment`,
 * ROLE_API only, #10486) through the filter, not by hand (#13224). The filter works on the Temporal
 * activity path because every activity runs its body on a duplicated Vert.x context
 * (`DomesticPaymentActivitiesImpl.vtx`); `DomesticTransactionClientWorkerThreadIT` drives the call from
 * a plain, non-Vert.x thread and asserts the header reaches the wire.
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
interface TransactionServiceClient : TransactionsApi
