// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.client

import com.openbank.clients.account.api.AccountsApi
import com.openbank.libs.web.SyntheticTaintClientFilter
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * Resolves a `creditorIban` to an internal `accountId` (`GET /api/v1/accounts/iban/{iban}`) — the
 * same lookup `openbank-domestic-payment`'s `AccountServiceClient` uses to tell an in-house payee
 * from a genuinely external one. A `DOMESTIC`/`INTERNAL` standing order whose creditor resolves
 * here never leaves the bank, so [StandingOrderDueConsumer] routes it straight to
 * transaction-service instead of the CZ clearing rail — the same rule
 * `com.openbank.libs.domain.payment.SettlementScope` encodes for same-day booking.
 *
 * The operations and DTOs are GENERATED from account-service's own `openapi.yaml`
 * (`openbank-client-account`, ADR-0319, #13169); this interface only attaches what is specific to this
 * consumer. Callers wrap it in `AccountClient`, never call the generated operations directly: 404 (no such
 * IBAN, or genuinely a different bank) is read there as a normal answer, not an error.
 */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter::class)
interface AccountServiceClient : AccountsApi
