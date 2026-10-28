// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.client

import com.openbank.clients.transaction.api.TransactionsApi
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * Where cleared card spend becomes money in the books.
 *
 * The operations and DTOs are GENERATED from transaction-service's own `openapi.yaml`
 * (`openbank-client-transaction`, ADR-0319); this interface only attaches what is specific to this
 * consumer: the config key, the default OIDC client and the synthetic-taint filter. The request is
 * the same shape every other rail uses (`InitiateTransactionRequest`), because card spend is a
 * rail and not a special case: `rail = CARD`, `instructionType = ONE_OFF` (ADR-0103 D2 vocabulary).
 */
@RegisterRestClient(configKey = "transaction-api")
@OidcClientFilter
@RegisterProvider(SyntheticTaintClientFilter::class)
interface TransactionServiceClient : TransactionsApi
