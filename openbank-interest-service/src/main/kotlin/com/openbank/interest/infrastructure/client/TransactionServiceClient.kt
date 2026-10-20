// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.client

import com.openbank.clients.transaction.api.TransactionsApi
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * REST client for `openbank-transaction-service`, GENERATED from its own `openapi.yaml`
 * (`openbank-client-transaction`, ADR-0319) — books the withholding-tax remittance cash leg
 * to the finanční úřad (#999). OIDC service-to-service auth is propagated by
 * the NAMED oidc-client `ledger` (interest-service's own Keycloak client `openbank-interest`,
 * ROLE_API only; #10486 batch 2 - the name predates this second use); this consumer runs on a plain reactive `@Incoming` handler
 * (not a Temporal activity), so the filter-based token attachment is safe here — same reasoning
 * as sdd-service's `TransactionServiceClient`.
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
// #10486 batch 2: same per-service identity as LedgerRestClient - transaction-service's OPA grants
// `service-account-openbank-interest` transaction.create by identity, never the shared client.
@OidcClientFilter("ledger")
interface TransactionServiceClient : TransactionsApi
