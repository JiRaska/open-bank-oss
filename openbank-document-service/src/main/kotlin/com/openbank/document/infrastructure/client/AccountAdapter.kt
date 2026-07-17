// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.client

import com.openbank.document.application.port.out.AccountInfo
import com.openbank.document.application.port.out.AccountLookupPort
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * **Fail-open** [AccountLookupPort] adapter (mirrors [ProductCatalogAdapter]'s stance): an
 * unreachable account-service must never block signing — the caller degrades to a template
 * render without the IBAN/product clause rather than failing the ceremony.
 */
@ApplicationScoped
class AccountAdapter : AccountLookupPort {

    @Inject
    @RestClient
    lateinit var client: AccountClient

    private val log = Logger.getLogger(AccountAdapter::class.java)

    // CURRENT, not just "first returned": the framework agreement's Article 2 names THE payment
    // account, and SAVINGS is opened alongside CURRENT for every retail onboarding (#1477 chain)
    // but is not what "the Customer is provided with a payment account" refers to.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun findCurrentAccount(partyId: UUID): AccountInfo? = try {
        client.listByParty(partyId.toString()).awaitSuspending().data
            .firstOrNull { it.accountType == "CURRENT" }
            ?.let { AccountInfo(iban = it.accountNumber, productId = UUID.fromString(it.productId)) }
    } catch (e: Exception) {
        log.warnf(
            "account-service unavailable for party %s; contract will omit account details: %s",
            partyId,
            e.message,
        )
        null
    }
}
