// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.client

import com.openbank.interest.application.port.out.AccountDirectoryPort
import com.openbank.interest.application.port.out.AccountPage
import com.openbank.interest.application.port.out.AccountSnapshot
import com.openbank.interest.application.port.out.BalanceSnapshot
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * **Fail-open** [AccountDirectoryPort] adapter over [AccountServiceClient]. Every upstream failure
 * degrades (empty page / null balance) and is logged, never thrown — see the port's contract for
 * why a degraded accrual tick beats a crashed scheduler.
 */
@ApplicationScoped
class AccountDirectoryAdapter : AccountDirectoryPort {

    @Inject
    @RestClient
    lateinit var client: AccountServiceClient

    private val log = Logger.getLogger(AccountDirectoryAdapter::class.java)

    override fun listActiveAccounts(cursor: String?, limit: Int): Uni<AccountPage> = client.listActive(limit, cursor)
        .map { resp ->
            AccountPage(
                items = resp.data.map {
                    AccountSnapshot(
                        id = it.id,
                        productId = it.productId.toString(),
                        accountType = it.accountType,
                        currency = it.currencyCode,
                    )
                },
                nextCursor = resp.pagination.nextCursor.takeIf { resp.pagination.hasNextPage },
            )
        }
        .onFailure().recoverWithItem { e ->
            log.warnf(
                "account-service /active unavailable (cursor=%s); accruing nobody this page: %s",
                cursor,
                e.message,
            )
            AccountPage(emptyList(), null)
        }

    override fun bookedBalance(accountId: UUID): Uni<BalanceSnapshot?> = client.getBalance(accountId)
        .map { resp ->
            val booked = resp.currentBalance
            if (booked == null) null else BalanceSnapshot(booked, resp.currencyCode ?: "CZK")
        }
        .onFailure().recoverWithItem { e ->
            log.warnf("account-service balance unavailable for %s; skipping its accrual: %s", accountId, e.message)
            null
        }
}
