// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.infrastructure.client

import com.openbank.clients.account.api.AccountsApi
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * Hop 1 of the VoP name resolution (ADR-0171 §4): IBAN → the owning party's id.
 * `openbank-account-service` holds no holder name of its own — only the link to a party.
 *
 * The operations and DTOs are GENERATED from account-service's own `openapi.yaml`
 * (`openbank-client-account`, ADR-0319, #13169); this interface only attaches what is specific to this
 * consumer. Callers wrap it in `AccountClient`, never call the generated operations directly.
 *
 * We deliberately do NOT send the `X-Customer-Party-Id` header account-service uses for
 * owner-scoping: VoP is a service-to-service check on behalf of a payer who is *not* the account
 * owner, so the M2M token (ROLE_API) is the right identity and owner-scoping must not apply.
 */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
interface AccountServiceClient : AccountsApi
