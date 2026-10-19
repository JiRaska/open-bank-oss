// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.client

import com.openbank.clients.transaction.api.TransactionsApi
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * REST client for `openbank-transaction-service` (ADR-0108), GENERATED from its own `openapi.yaml`
 * (`openbank-client-transaction`, ADR-0319): books the settled funds after the
 * scheme confirms ACSC for an MT103. OIDC service-to-service auth is propagated by
 * the named oidc-client `m2m` (swift's own Keycloak client, #10486); [ClearingSimulatorClient]
 * still uses the shared default client.
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
// #10486: this client's bearer is minted by the NAMED oidc-client `m2m` - Keycloak client
// `openbank-swift` (ROLE_API only) - never the shared `openbank-services` default client.
@OidcClientFilter("m2m")
interface TransactionServiceClient : TransactionsApi
