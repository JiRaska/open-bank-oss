// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

import com.openbank.clients.transaction.api.TransactionsApi
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * RestClient binding to `openbank-transaction-service` (`POST /api/v1/transactions`), GENERATED from its own
 * `openapi.yaml` (`openbank-client-transaction`, ADR-0319).
 * Uses the named oidc-client `m2m` (sepa-instant's own Keycloak client, #10486) for M2M token
 * injection; [AmlServiceClient] selects the same named client since #10486 batch 3. Idempotency is carried by the request BODY' `idempotencyKey` (transaction-service reads no
 * `Idempotency-Key` header, so the header this client used to send changed nothing).
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
// #10486: this client's bearer is minted by the NAMED oidc-client `m2m` - Keycloak client
// `openbank-sepa-instant` (ROLE_API only) - never the shared `openbank-services` default client.
@OidcClientFilter("m2m")
interface TransactionServiceClient : TransactionsApi
