// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.infrastructure.client

import com.openbank.clients.transaction.api.TransactionsApi
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * REST client for `openbank-transaction-service`, GENERATED from its own `openapi.yaml`
 * (`openbank-client-transaction`, ADR-0319) — books the debtor-side debit for an authorised
 * SEPA Direct Debit collection (#1000). OIDC service-to-service auth is propagated by
 * the named oidc-client `m2m`; this consumer runs on a plain `@Incoming` suspend handler
 * (not a Temporal activity), so the filter-based token attachment is safe here — unlike
 * domestic-payment's `SettlementAdapter`, which needs an explicit-token workaround specifically
 * because a Temporal activity thread loses the Vert.x context the filter relies on.
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
// #10486: this client's bearer is minted by the NAMED oidc-client `m2m` - Keycloak client
// `openbank-sdd` (ROLE_API only) - never the shared `openbank-services` default client.
@OidcClientFilter("m2m")
interface TransactionServiceClient : TransactionsApi
